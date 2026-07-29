# Project: Starlink Kit Provisioning Scanner (Android)

## Goal

Build a native Android app in **Kotlin** that provisions Starlink kits in the field:

1. Technician plugs in a Starlink dish (no internet/satellite link required).
2. Phone connects to the dish WiFi. The app automatically detects the dish and reads its **dish ID (UT ID)**, hardware version, software version, and country code via the dish's local gRPC API.
3. Technician scans the barcode(s) on the kit box with the camera (ML Kit) to capture the **kit number** (and optionally dish/router serial labels).
4. The app pairs dish data + scanned data into one record, stores it locally, and uploads it to a **Google Sheet** via a Google Apps Script Web App endpoint whenever any internet connection is available.

The app must work fully offline at capture time. Upload is deferred and automatic.

## Tech stack & constraints

- Language: **Kotlin**, AndroidX. Target JVM 17, Kotlin 1.9+.
- minSdk 26, targetSdk 34 (or current).
- **Coroutines + Flow** for all async work — no raw threads or executors. Use `viewModelScope`, `Dispatchers.IO` for network/DB, structured concurrency throughout.
- **Jetpack Compose** for the entire UI (Material 3, `androidx.compose.material3`). No XML layouts except the manifest and resource files.
- **Architecture**: MVVM. `ViewModel` exposes UI state as `StateFlow<UiState>`; Compose collects with `collectAsStateWithLifecycle()`. Single-activity, Compose Navigation for the three destinations.
- gRPC: `io.grpc:grpc-okhttp`, `grpc-protobuf-lite`, `grpc-kotlin-stub` (OkHttp transport, NOT netty — this is Android). Use the **Kotlin coroutine stub** (`DeviceCoroutineStub`) so calls are `suspend` functions.
- Barcode: **CameraX + ML Kit barcode scanning** (`com.google.mlkit:barcode-scanning`).
- Local storage: **Room** with `suspend` DAO functions and `Flow<List<…>>` queries.
- Deferred upload: **WorkManager** via `CoroutineWorker`, `NetworkType.CONNECTED` constraint.
- HTTP upload: OkHttp (wrap the enqueue call in `suspendCancellableCoroutine`, or use the `okhttp3.coroutines` `await()` extension).
- DI: **Hilt** is recommended but optional — if skipped, use a simple manual `ServiceLocator` object. Keep it consistent.
- Immutable data via `data class`; model UI state with a `sealed interface`.

## Module 1 — Starlink gRPC client

### API facts

- The dish exposes an **unauthenticated plaintext gRPC** server at fixed IP `192.168.100.1:9200`.
- Service: `SpaceX.API.Device.Device`, single method `Handle(Request) returns (Response)`.
- Relevant request: `{"get_device_info":{}}` → response `get_device_info.device_info` contains `id` (format like `ut01000000-00000000-00001234`), `hardware_version`, `software_version`, `country_code`.
- Also useful: `{"get_status":{}}` (contains `device_info` too, plus dish state).
- This API is **local only** — it works with no satellite connection, no account, no internet. It is available shortly after the dish boots.
- The API is unofficial. There are no published .proto files from SpaceX, but the server supports **gRPC reflection**.

### Proto setup

- Obtain .proto files from the community repo `clarkzjw/starlink-grpc-golang` (its `proto/` directory contains protos decoded from the dish protoset). Vendor only the minimal set of protos needed for `Device/Handle` with `get_device_info` and `get_status` into `app/src/main/proto/`.
- If the full proto tree is too tangled, it is acceptable to hand-write a **minimal trimmed .proto** containing only: `Request` (with `get_device_info`, `get_status` oneof fields and their field numbers matching the real API), `Response`, `GetDeviceInfoRequest/Response`, `DeviceInfo`, and the `Device` service. Field numbers MUST match the upstream protos — copy them from the community repo, do not invent them.
- Gradle: `com.google.protobuf` plugin generating `java { option 'lite' }` + `grpc { }` + **`grpckt { }`** (the Kotlin gRPC codegen). Depend on `protobuf-kotlin-lite` and `grpc-kotlin-stub`.

### Client implementation

Create a `StarlinkClient` class:

- Constructor/factory takes an `android.net.Network?` (null → default routing).
- Builds the channel:
  ```kotlin
  private val channel: ManagedChannel =
      OkHttpChannelBuilder.forAddress("192.168.100.1", 9200)
          .usePlaintext()
          .apply { network?.socketFactory?.let { socketFactory(it) } }
          .build()

  private val stub = DeviceGrpcKt.DeviceCoroutineStub(channel)
  ```
- `suspend fun fetchDeviceInfo(): DishInfo` — call on `Dispatchers.IO`, wrap the stub call with `withTimeout(3_000)`, send `get_device_info`, map to a `DishInfo` data class (`dishId`, `hardwareVersion`, `softwareVersion`, `countryCode`). On failure throw a sealed `StarlinkError` (`Unavailable`, `Timeout`, `Unimplemented`, `Unknown`) — do not leak `StatusRuntimeException` upward.
- Firmware fallback: if `get_device_info` throws `UNIMPLEMENTED`, retry with `get_status` and read `device_info` from there.
- Implement `Closeable`/`close()` that calls `channel.shutdownNow()`; the caller (repository) owns lifecycle. Prefer creating a fresh client per detection session and closing it when done.
- Never touch the main thread; everything is `suspend`.
- Add cleartext permission for `192.168.100.1` via `network_security_config.xml` referenced from the manifest.

Wrap this behind a `StarlinkRepository` interface (`suspend fun getDishInfo(network: Network?): Result<DishInfo>`) so the ViewModel depends on the interface, not gRPC — makes testing with a fake trivial.

## Module 2 — Dish WiFi detection & network binding (CRITICAL)

The dish WiFi usually has **no internet**. Android will (a) mark the network "no internet" and (b) route app traffic over mobile data by default, which breaks connectivity to 192.168.100.1. Handle this properly.

Expose dish detection as a cold `Flow` using `callbackFlow`, so the ViewModel just collects it:

```kotlin
fun dishNetworkFlow(cm: ConnectivityManager): Flow<Network> = callbackFlow {
    val request = NetworkRequest.Builder()
        .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
        .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) // key line
        .build()
    val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { trySend(network) }
        override fun onLost(network: Network) { /* emit a sentinel / handle upstream */ }
    }
    cm.requestNetwork(request, callback)
    awaitClose { cm.unregisterNetworkCallback(callback) }
}
```

- Pass the emitted `Network` into `StarlinkClient` (socket-factory binding). Do NOT use `bindProcessToNetwork` — the Sheets upload must stay free to use mobile data simultaneously.
- Poll with retries: attempt `getDishInfo()` every 2–3s with a 3s timeout, because just after power-on the WiFi may be up before the gRPC service is listening. A clean Kotlin pattern:
  ```kotlin
  val dishInfo = retryWhile(times = Int.MAX_VALUE, delayMs = 2_500) {
      repository.getDishInfo(network)
  } // cancel the loop as soon as the state leaves SEARCHING via coroutine cancellation
  ```
- Drive the whole thing from the ViewModel's `StateFlow<CaptureUiState>`; cancel the polling coroutine on success or when the user navigates away (structured concurrency handles this automatically inside `viewModelScope`).
- Do not read SSID (avoids location permission); reachability of the gRPC endpoint IS the detection mechanism.

## Module 3 — Barcode scanning (CameraX + ML Kit)

- Camera permission via `rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission())`, requested at first scan with a rationale, not up front.
- CameraX `Preview` + `ImageAnalysis` inside Compose (`AndroidView` hosting a `PreviewView`, or the CameraX Compose artifact if used). Feed frames to ML Kit `BarcodeScanner`.
- Restrict formats for speed:
  ```kotlin
  val options = BarcodeScannerOptions.Builder()
      .setBarcodeFormats(
          Barcode.FORMAT_CODE_128,
          Barcode.FORMAT_QR_CODE,
          Barcode.FORMAT_DATA_MATRIX,
      ).build()
  ```
- The kit box has multiple labels. Classify each scanned value by prefix in a pure function (unit-testable):
  ```kotlin
  sealed interface ScannedLabel {
      data class Kit(val value: String) : ScannedLabel
      data class DishSerial(val value: String) : ScannedLabel
      data class RouterSerial(val value: String) : ScannedLabel
      data class Unknown(val value: String) : ScannedLabel
  }
  fun classify(raw: String): ScannedLabel = when { raw.startsWith("KIT") -> … ; … }
  ```
- Emit results as a `Flow<ScannedLabel>` from the analyzer; debounce duplicate identical values within ~2s in the ViewModel. Haptic + beep on an accepted, newly-filled field.
- Scanning UI shows a live checklist: Kit number [required], Dish serial [optional], Router serial [optional]. "Done" enabled once the kit number is captured.

## Module 4 — Local persistence (Room)

`@Entity` `ScanRecord`:

| field | type | notes |
|---|---|---|
| id | `Long` PK, autoGenerate | |
| timestamp | `Long` | capture time (ms) |
| dishId | `String` | from gRPC |
| hardwareVersion | `String` | from gRPC |
| softwareVersion | `String` | from gRPC |
| countryCode | `String` | from gRPC |
| kitNumber | `String` | from scan |
| dishSerial | `String?` | optional scan |
| routerSerial | `String?` | optional scan |
| operator | `String?` | from settings (DataStore) |
| status | `UploadStatus` enum (PENDING/SENT/FAILED) | stored via a `@TypeConverter` |
| attempts | `Int` | upload attempt counter |

DAO (all `suspend` except reactive reads):

```kotlin
@Dao
interface ScanDao {
    @Insert suspend fun insert(record: ScanRecord): Long
    @Query("SELECT * FROM ScanRecord ORDER BY timestamp DESC")
    fun observeAll(): Flow<List<ScanRecord>>
    @Query("SELECT * FROM ScanRecord WHERE status IN ('PENDING','FAILED')")
    suspend fun pending(): List<ScanRecord>
    @Query("UPDATE ScanRecord SET status=:s, attempts=attempts+1 WHERE id=:id")
    suspend fun updateStatus(id: Long, s: UploadStatus)
    @Query("SELECT COUNT(*) FROM ScanRecord WHERE status IN ('PENDING','FAILED')")
    fun pendingCount(): Flow<Int>
}
```

The `pendingCount()` Flow feeds the status strip badge directly.

## Module 5 — Upload to Google Sheets (deferred, batched)

- On every record save AND on app start, enqueue a unique upload job:
  ```kotlin
  WorkManager.getInstance(context).enqueueUniqueWork(
      "upload", ExistingWorkPolicy.KEEP,
      OneTimeWorkRequestBuilder<UploadWorker>()
          .setConstraints(Constraints.Builder()
              .setRequiredNetworkType(NetworkType.CONNECTED).build())
          .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
          .build()
  )
  ```
- `UploadWorker : CoroutineWorker` — `override suspend fun doWork()`:
  1. `dao.pending()` → if empty, `Result.success()`.
  2. Serialize to a JSON **array** (kotlinx.serialization preferred).
  3. POST once to the Apps Script URL via OkHttp (`await()`), `followRedirects(true)` (Apps Script `/exec` replies 302 first).
  4. On HTTP 200 + `{"status":"ok"}` → mark all SENT, `Result.success()`. Else mark FAILED and `Result.retry()`.
- Use kotlinx.serialization `@Serializable` DTOs for the payload; keep them separate from the Room entity.
- The Apps Script URL and operator name live in **DataStore (Preferences)**, exposed as `Flow`, with a BuildConfig default URL.

### Apps Script backend (generate this file too, as `apps-script/Code.gs`)

```javascript
function doPost(e) {
  var sheet = SpreadsheetApp.openById("YOUR_SHEET_ID").getSheetByName("Scans");
  var items = JSON.parse(e.postData.contents);
  if (!Array.isArray(items)) items = [items];
  items.forEach(function(d) {
    sheet.appendRow([
      new Date(d.timestamp), d.dishId, d.kitNumber,
      d.dishSerial || "", d.routerSerial || "",
      d.hardwareVersion || "", d.softwareVersion || "",
      d.countryCode || "", d.operator || ""
    ]);
  });
  return ContentService.createTextOutput(JSON.stringify({status:"ok", count: items.length}))
      .setMimeType(ContentService.MimeType.JSON);
}
```

Include a README note: deploy as Web App, Execute as Me, Access: Anyone; sheet header row: `Timestamp | Dish ID | Kit Number | Dish Serial | Router Serial | HW Version | SW Version | Country | Operator`.

## UI specification

Built entirely in **Jetpack Compose + Material 3**. Each screen is a stateless composable driven by state hoisted from a `ViewModel` (`StateFlow` collected via `collectAsStateWithLifecycle()`); events go up as lambdas. Use `NavHost` for navigation and a `Scaffold` with a bottom `NavigationBar`.

### Design principles (this is a field tool)

- **Outdoor-readable**: high contrast, large type, no thin light-grey text. Assume bright sunlight and a technician standing at a dish.
- **Glove-friendly**: primary touch targets ≥ 56dp height, generous spacing, no tiny icon-only actions for critical steps.
- **One-handed & fast**: the capture loop (dish found → scan → save) needs as few taps as possible; the primary action is always a large button pinned to the bottom.
- **State always visible**: the user should never wonder whether the dish is connected or whether data saved/uploaded. Use a persistent status strip.
- Single accent color, system light/dark support, utilitarian — no custom illustrations needed.

### Design tokens (Compose `MaterialTheme`)

- Define a custom `ColorScheme`: **primary** strong blue (`Color(0xFF1565C0)`); semantic colors success/SENT green (`0xFF2E7D32`), pending amber (`0xFFF9A825`), error/FAILED red (`0xFFC62828`) as extension values on a custom `LocalAppColors` if not in the base scheme.
- **Typography**: Material 3 type scale; screen titles `headlineSmall` bold; field labels `labelLarge` uppercase; **dish ID / kit number values use a monospace `FontFamily.Monospace`** at ~18–20sp so long alphanumerics are verifiable.
- **Spacing**: 16dp screen padding, 12dp between cards, 8dp intra-card. Cards = `Card` with 12dp rounded corners.

### Navigation & shell

Bottom `NavigationBar` with three destinations (thumb-reachable): **Capture** (start), **History**, **Settings**.

A persistent **status strip** composable sits directly under the top app bar on every screen: dish state on the left (colored dot + text — "Dish connected" green / "Searching…" amber / "No dish" grey), pending-upload count on the right (from `pendingCount()` Flow: "3 pending" amber / "All synced" green).

### Capture screen — one composable, `sealed interface CaptureUiState`

Model state as:
```kotlin
sealed interface CaptureUiState {
    data class Waiting(val attempts: Int, val wifiConnected: Boolean) : CaptureUiState
    data class DishFound(val info: DishInfo, val capturedAt: Long) : CaptureUiState
    data class Scanning(val info: DishInfo, val checklist: Checklist) : CaptureUiState
    data class Summary(val record: ScanRecord, val duplicate: Boolean) : CaptureUiState
}
```
Render each state; the bottom primary `Button` changes label/action per state.

**Waiting** — centered instruction "Plug in the dish and connect this phone to the Starlink WiFi." A `CircularProgressIndicator` while polling, subtext "Looking for dish… attempt N". Bottom **Retry** (secondary). If no WiFi at all, swap subtext to "Not connected to any WiFi" + an **Open WiFi settings** button (`Intent(Settings.ACTION_WIFI_SETTINGS)`).

**DishFound** — a `Card` "Dish detected" with a green check. Label/value grid, monospace values: **Dish ID** most prominent; HW/SW version + country code secondary. A "captured 12s ago" freshness line that turns amber past 10 minutes. Bottom primary **Scan kit box →**.

**Scanning** — full-bleed CameraX preview via `AndroidView`. A translucent center reticle. A collapsible bottom sheet (`ModalBottomSheet` or persistent `BottomSheetScaffold`) with checklist chips (`FilterChip`/`AssistChip`) filling live: `Kit number` (required, red outline until filled → green with value), `Dish serial` (optional), `Router serial` (optional). Beep + haptic (`HapticFeedback`) on an accepted new fill. Bottom primary **Done** — `enabled = checklist.kitNumber != null`. Top-right: torch toggle + close (X) returning to DishFound without losing dish info. `keepScreenOn` while in this state.

**Summary** — read-only recap `Card` merging dish + scanned data, monospace values. If a duplicate `dishId + kitNumber` already exists locally, show an amber inline banner "This kit was already recorded" with the prior timestamp. Two stacked bottom buttons: primary **Save** (persist to Room, enqueue upload, show a "Saved ✓" `Snackbar`, reset to Waiting for the next kit); secondary **Discard**. Pending count in the strip increments immediately on save.

### History screen

- `TopAppBar` title "History" + a **Sync now** action that enqueues the UploadWorker; show a spinner while running.
- `LazyColumn` of records from `observeAll()`, newest first. Each row: status chip — PENDING (amber) / SENT (green) / FAILED (red); middle: kit number (bold monospace) over dish ID (secondary monospace, ellipsized); right: relative timestamp ("2m ago") and, if FAILED, the attempt count.
- Tap a row → detail `ModalBottomSheet` with the full record; for FAILED items a **Retry this one** button + last error message.
- Empty state: "No scans yet — capture your first kit."
- Optional `FilterChip` row: All / Pending / Failed.

### Settings screen

- **Apps Script URL** — `OutlinedTextField`, prefilled from DataStore/BuildConfig, with an inline **Test connection** button that POSTs an empty batch and reports 200/redirect success or the failure reason. Validate it looks like `script.google.com/macros/s/…/exec`.
- **Operator name** — `OutlinedTextField`, persisted to DataStore, attached to every record.
- **Diagnostics** section: current dish reachability, last successful upload time, pending/failed counts, and a **Run dish test** button that performs a one-off `getDishInfo()` and shows the raw result — useful when firmware fields differ.
- App version + a note that the Starlink local API is unofficial.

### Permissions & first-run

- First launch: a brief one-card explainer of the flow, then request **Camera** at the point of first scan (with rationale), not up front.
- No location permission requested (SSID intentionally not read); reachability is the detection mechanism.

### Accessibility & robustness

- `contentDescription` on all actionable controls; support TalkBack.
- Support dark mode (vans/evening installs) via the Material 3 dark `ColorScheme`.
- Respect system font scaling; never clip long dish IDs — wrap or horizontally scroll monospace values in Summary rather than truncating silently.

## Error handling & edge cases

- gRPC `Timeout`/`Unavailable` → stay in Waiting, keep polling, show "waiting for dish…".
- Dish found but user leaves WiFi before scanning → keep the `DishInfo` in the ViewModel for the session; warn if older than 10 minutes.
- Same `dishId + kitNumber` saved twice → warn "already recorded, save anyway?".
- Upload failure never blocks or loses data; records stay PENDING/FAILED and retry via WorkManager.
- Firmware differences: `get_device_info` `UNIMPLEMENTED` → fall back to `get_status` (handled in `StarlinkClient`).
- All coroutine work is cancellation-safe; leaving the Capture screen cancels polling automatically via `viewModelScope`.

## Deliverables

1. Complete Android Studio project (Gradle Kotlin DSL, Kotlin) implementing all modules with Compose UI.
2. `apps-script/Code.gs` + `apps-script/README.md`.
3. Root `README.md`: proto generation notes, how to test against a real dish (`grpcurl -plaintext -d '{"get_device_info":{}}' 192.168.100.1:9200 SpaceX.API.Device.Device/Handle`), how to deploy the Apps Script and set the URL.
4. Unit tests (JUnit + coroutines-test + Turbine for Flows): barcode prefix `classify()`, `ScanDao` (Room in-memory), `UploadWorker` payload building (mock web server), and the Capture `ViewModel` state transitions with a fake `StarlinkRepository`.

## Build order (suggested)

1. Project skeleton (Compose, Nav, theme, Room, DataStore) + Capture state machine driven by a **fake** `StarlinkRepository`.
2. Proto vendoring + gRPC Kotlin client, tested against a real dish or a local mock gRPC server.
3. `callbackFlow` network detection + socket-factory binding + polling in the ViewModel.
4. CameraX + ML Kit scanning composable + classification.
5. WorkManager `CoroutineWorker` upload + Apps Script.
6. History/Settings screens, polish, tests.
