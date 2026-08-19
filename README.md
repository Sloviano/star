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
| 3 — Kit/dish capture | `ui/capture/` (CameraX + ML Kit barcode **or** text) |
| 4 — Local persistence | `data/local/` (Room) |
| 5 — Deferred upload to Sheets | `data/upload/` (WorkManager + OkHttp + kotlinx.serialization) |

## Build & run

```bash
./gradlew :app:assembleDebug        # build
./gradlew :app:installDebug         # install on a connected device/emulator
./gradlew test                      # JVM unit tests
./gradlew :app:assembleRelease      # the APK you publish (see Releasing)
```

Requires JDK 17. minSdk 26.

## Releasing (Module 6)

**Publish `app/build/outputs/apk/release/app-release.apk`.** Releases up to v1.7 shipped
`app-debug.apk` instead, which is `android:debuggable` — on a technician's phone that hands anyone
with USB access the captured records, the Apps Script URL and the shared secret straight out of app
storage via `run-as`. The release variant closes that, and R8 plus an ABI filter keep it far smaller
than the debug APK it replaces, which matters over field connectivity.

ML Kit's barcode and OCR models are **native**, at roughly 16 MB per ABI, so they dominate the APK.
The release variant therefore ships `armeabi-v7a` and `arm64-v8a` only: carrying x86/x86_64 as well
added ~33 MB that no technician's phone can execute, since those exist for emulators. Debug builds
stay universal so the emulator still works. Dropping `armeabi-v7a` too would save a further ~10 MB,
at the cost of any 32-bit-only device in the fleet.

The tag must encode the versionCode as `v<versionName>+<versionCode>` (e.g. `v1.8+9`) — that is
what `UpdateChecker.parseVersionCode` reads — and the release needs exactly one `.apk` asset.

### The signing key

The in-app updater installs over the existing app, and **Android only allows that when the new APK
carries the same signature**. Every published build so far was signed with this machine's Android
debug keystore, so that certificate is what the whole installed fleet trusts, and
`app/build.gradle.kts` keeps signing releases with it on purpose. A different key means every phone
needs a manual uninstall and reinstall — which discards any records that haven't uploaded yet.

> **Back up `~/.android/debug.keystore`.** It is machine-local, it is in no backup by default, and
> Android Studio silently regenerates it *with a different key* if the file goes missing. Losing it
> costs a manual reinstall on every phone in the field.

Verify before publishing — the certificate digest must match what is already installed:

```bash
apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk
aapt2 dump badging app/build/outputs/apk/release/app-release.apk | grep debuggable   # must be empty
```

To migrate to a proper release key later, set `RELEASE_KEYSTORE`, `RELEASE_KEYSTORE_PASSWORD`,
`RELEASE_KEY_ALIAS` and `RELEASE_KEY_PASSWORD` in `local.properties` — and plan the fleet reinstall,
after every phone has synced.

### Before you publish

**Install the release APK on a real phone and open it.** Not the debug build, and not a build you
only inspected — the minified one you are about to upload. R8 breakage is invisible until the app
runs: v1.8 shipped with ML Kit's registrar constructors stripped and crashed on the first frame of
the capture screen, having passed every check that did not involve launching it. A broken build also
cannot deliver its own fix, because it dies before the update check runs, so every phone that took
it needs a manual sideload.

```bash
./gradlew :app:assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
adb shell am start -n com.starlink.scanner/.MainActivity
adb logcat -d -b crash | grep -c 'FATAL EXCEPTION'                 # must be 0
adb logcat -d | grep -c 'Could not instantiate com.google.mlkit'   # must be 0
```

Then open the capture screen in **both** Barcode and Text mode — each constructs a different ML Kit
client, and only the one you exercise is actually proven. `PipelineManager: OCR process succeeded`
in the log means text mode is really running. Scan against a real dish and kit box when you can.

R8 keep rules for the reflective libraries (ML Kit, protobuf-lite, gRPC, kotlinx.serialization) live
in [`app/src/main/keepRules/rules.keep`](app/src/main/keepRules/rules.keep). Note that
`android.enableR8.fullMode` is turned **off** in `gradle.properties`, deliberately — see the comment
there before turning it back on.

## Capturing the kit number (Module 3)

The kit box carries its number twice — as a Data Matrix label and as printed text — and the capture
screen offers both, chosen with a Barcode | Text toggle. Barcode is the default and the reliable
path; text (OCR) exists for a label that is damaged, smudged or wrapped around a corner, which would
otherwise leave the kit unsaveable: unlike the dish serial, the kit field has no manual-entry
fallback.

The choice applies to the **kit field only** — the dish serial is always read from its Data Matrix
label — so the toggle is hidden once the target advances. It persists across kits (a pallet of bad
labels shouldn't mean re-picking text mode on every box) and is one tap to change.

OCR returns every word on the box, so [`domain/KitNumber.kt`](app/src/main/java/com/starlink/scanner/domain/KitNumber.kt)
decides which one is the kit number. Known kit numbers are `KIT4M0` plus ten more
uppercase-alphanumeric characters (`KIT4M06183988NHK`), and matching is two-tier: that exact shape
first, then any plausible `KIT…` token. The second tier matters — `KIT4M0` is almost certainly a
batch code, and keying only on it would make a future kit generation silently unrecognisable, with
nothing on screen to explain why. Every candidate still goes through the technician's Accept/No
confirmation, and must hold still for two consecutive frames first, because OCR output flickers.

`KitNumber` is a pure function with no ML Kit or Android types precisely so it can be tested against
real values; `TextAnalyzer` stays a dumb pipe and does no filtering of its own.

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
