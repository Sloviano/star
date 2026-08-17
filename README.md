# Starlink Kit Provisioning Scanner

A native Android app (Kotlin + Jetpack Compose) that provisions Starlink kits in the field:

1. Technician plugs in a Starlink dish (no internet/satellite link required) and connects the phone
   to the dish WiFi.
2. The app auto-detects the dish and reads its **dish ID (UT ID)**, hardware/software version and
   country code over the dish's local **gRPC** API (`192.168.100.1:9200`).
3. The technician scans the kit box barcode(s) with the camera (CameraX + ML Kit) to capture the
   **kit number** (plus optional dish/router serials).
4. Dish data + scanned data are paired into one record, stored locally (Room), and uploaded to a
   **Google Sheet** via a Google Apps Script Web App whenever any internet is available.

Capture works fully offline; upload is deferred and automatic (WorkManager).

## Architecture

MVVM + unidirectional state. `ViewModel`s expose `StateFlow<UiState>`; Compose collects with
`collectAsStateWithLifecycle()`. Single-activity, Compose Navigation, manual `ServiceLocator` DI.

| Module | Where |
|--------|-------|
| 1 — gRPC dish client | `data/starlink/`, `src/main/proto/device_min.proto` |
| 2 — WiFi detection & network binding | `data/network/` |
| 3 — Barcode scanning | `ui/capture/` (CameraX + ML Kit) |
| 4 — Local persistence | `data/local/` (Room) |
| 5 — Deferred upload to Sheets | `data/upload/` (WorkManager + OkHttp + kotlinx.serialization) |

## Build & run

```bash
./gradlew :app:assembleDebug        # build
./gradlew :app:installDebug         # install on a connected device/emulator
./gradlew test                      # JVM unit tests
```

Requires JDK 17. minSdk 26.

## Database migrations (Module 4)

Records live in Room until they upload, so an unsynced record is field work that exists nowhere
else. The database therefore has **no blanket destructive fallback**: only the never-shipped schema
versions 1 and 2 may be recreated (`AppDatabase.LEGACY_VERSIONS`), and any other missing migration
throws at open time instead of dropping the table.

Changing `ScanRecord` means:

1. Bump `version` in `@Database`.
2. Build once — Room writes the new schema JSON to `app/schemas/`. Commit it.
3. Add the `Migration(old, new)` to `AppDatabase.MIGRATIONS`.

Skipping step 3 fails at runtime on any device that has the old schema, which is the point.

## Proto generation (Module 1)

The gRPC stubs are generated from a hand-trimmed subset of the unofficial dish API in
[`app/src/main/proto/device_min.proto`](app/src/main/proto/device_min.proto). Field numbers are
copied verbatim from the community protoset (`clarkzjw/starlink-grpc-golang`, its
`proto/spacex_api/device/*.proto`) and **must** match the real dish — extra fields on the wire are
ignored by the protobuf-lite runtime.

The `com.google.protobuf` Gradle plugin runs `protoc` with the `java{lite}`, `kotlin{lite}`, `grpc`
and `grpckt` (Kotlin coroutine stub) generators — see `app/build.gradle.kts`. Generated code lands
under `app/build/generated/` and is produced automatically by any build; there is no checked-in
generated source to maintain.

## Joining the dish WiFi (Module 2)

Two different mechanisms, because Android offers no single API that is both instant and shared:

| | `StarlinkWifiConnector` | `StarlinkWifiSuggester` |
|---|---|---|
| API | `WifiNetworkSpecifier` + `requestNetwork` | `WifiNetworkSuggestion` |
| Scope | **This app only** — other apps cannot see or use the network | **Device-wide** — an ordinary connection every app shares |
| Triggered by | Tapping the Dish ID signalizer on Capture | Settings ▸ Dish WiFi ▸ Auto-join |
| Timing | Immediate, with a system approval dialog | Whenever the platform decides the AP is worth joining |
| SSID | Prefix match, so `STARLINK-1234` qualifies | **Exact match only** — `WifiNetworkSuggestion.Builder` has no `setSsidPattern` at any API level |
| Lifetime | Only while the capture screen collects the flow | Persists across reboots until withdrawn or the app is uninstalled |

The exact-SSID limitation is why the app remembers which AP it connected to:
`StarlinkWifiConnector.ssidOf()` reads the SSID back from a network *this app itself* brought up —
the one case the platform returns it unredacted without `ACCESS_FINE_LOCATION`, which this app still
never requests. That value is persisted as `dish_ssid` and suggested alongside the stock `STARLINK`.
A technician who has never used the in-app connect therefore gets the default suggested; one who has
gets their real AP.

Registering a suggestion returns success as soon as Android *accepts* it — not when the phone
connects. The first time the AP is actually matched, Android shows the user a notification asking
whether to allow this app's suggestions; declining turns later calls into
`STATUS_NETWORK_SUGGESTIONS_ERROR_APP_DISALLOWED`, which the Settings toggle surfaces with recovery
instructions.

Needs `CHANGE_WIFI_STATE` + `ACCESS_WIFI_STATE` (both install-time, neither is location).

## Testing against a real dish

With the phone (or laptop) on the dish WiFi:

```bash
grpcurl -plaintext -d '{"get_device_info":{}}' \
  192.168.100.1:9200 SpaceX.API.Device.Device/Handle

# richer payload (identity + obstruction/GPS/alerts/throughput):
grpcurl -plaintext -d '{"get_status":{}}' \
  192.168.100.1:9200 SpaceX.API.Device.Device/Handle
```

The API is unauthenticated, plaintext, and local-only — available shortly after the dish boots, with
no satellite link or account. In the app, the **Settings ▸ Diagnostics** section and the Capture
screen's live status surface the same reachability.

> Note: the client requests `get_status` first (rich data) and falls back to `get_device_info` on
> firmware that returns `UNIMPLEMENTED`. Cleartext to `192.168.100.1` is whitelisted in
> `res/xml/network_security_config.xml`.

## Google Sheets upload (Module 5)

Records are saved `PENDING` and uploaded by a unique WorkManager job (`UploadWorker`) with a
`NetworkType.CONNECTED` constraint and exponential backoff. The job is enqueued on every save, on
app start, and from **History ▸ Sync now**. It batches all not-yet-sent records into a single JSON
POST; on success they are marked `SENT`, otherwise they stay `FAILED` and retry — data is never lost.

Deploy the backend and get the endpoint URL by following
[`apps-script/README.md`](apps-script/README.md), then paste the `/exec` URL into
**Settings ▸ Apps Script URL** and tap **Test connection**.

A default endpoint can be baked in via the `DEFAULT_SHEETS_URL` `buildConfigField` in
`app/build.gradle.kts` (empty by default; the runtime Settings value overrides it).
