# Apps Script backend (Google Sheets sink)

The app uploads scan records to a Google Sheet through a Google Apps Script **Web App**. The Android
side needs the deployment's `/exec` URL — set it in **Settings ▸ Apps Script URL** — and, if you
enable the shared secret (step 4), a matching `SHEETS_TOKEN` baked into the build.

## 1. Create the sheet

1. Create a new Google Sheet.
2. Rename the first tab to **`Scans`** (must match `SHEET_NAME` in `Code.gs`).
3. Add this header row in row 1:

   | Counter | Dish ID | Kit Number | Dish Serial |
   |---------|---------|------------|-------------|

   **Counter** is the app's row counter (**Settings ▸ Row counter**): a number the technician sets to
   any starting value, advancing by one per saved kit.

   On an existing sheet:

   - Column A used to be **Timestamp**. The script relabels that header on the next post; rows
     written earlier keep their dates, and a record saved before the counter existed still uploads
     its timestamp there rather than a blank.
   - Column E used to be **Upload Key**. Nothing is written there any more — delete the column when
     you like; the script leaves it alone. Removing it also removed duplicate protection, see
     [Duplicate rows](#duplicate-rows).

## 2. Add the script

1. In the sheet: **Extensions ▸ Apps Script**.
2. Replace the contents of `Code.gs` with this repo's [`Code.gs`](./Code.gs). It uses
   `SpreadsheetApp.getActiveSpreadsheet()`, so the script is already bound to this sheet — no sheet ID
   to paste.
3. Save.

## 3. Deploy as a Web App

1. **Deploy ▸ New deployment ▸ Web app**.
2. **Execute as:** *Me*.
3. **Who has access:** *Anyone*. Apps Script offers no other option that the app can post to, so
   the deployment is reachable by anyone holding the URL — set the shared secret in step 4 so the
   URL alone is not enough. Rotate the deployment if the URL leaks.
4. Copy the **Web app URL** — it looks like
   `https://script.google.com/macros/s/AKfyc.../exec`.
5. Paste it into the app's **Settings ▸ Apps Script URL** and tap **Test connection** — a success
   posts an empty batch and the backend replies `{"status":"ok","count":0}`.

## 4. Set the shared secret

The deployment is `Anyone`-access and unauthenticated, so out of the box the `/exec` URL *is* write
access to your sheet — and that URL travels widely (`local.properties`, chat messages, anyone who
can build the app). `SHARED_SECRET` requires every post to also carry a secret.

It is not real authentication: the token ships inside a non-minified APK and anyone holding the APK
can extract it. Treat it as "the URL alone is not enough".

1. Generate a secret: `openssl rand -hex 32`.
2. Put it in the app's git-ignored `local.properties` as `SHEETS_TOKEN=<secret>`, and **build and
   roll out that APK first**.
3. Only then set the same value as `SHARED_SECRET` in `Code.gs` and publish a new version.
4. Confirm with **Settings ▸ Test connection** — the dry run is authenticated, so a mismatched
   token reports `Unauthorized` here rather than silently failing later.

Order matters. Setting `SHARED_SECRET` before the token-carrying build is installed makes the
backend reject uploads from every phone still on the old build. Nothing is lost — those records stay
`PENDING` and retry once the build lands — but technicians stop syncing in the meantime.

Leaving `SHARED_SECRET` as `''` (the default) keeps the endpoint open to unauthenticated posts.
Leaving `SHEETS_TOKEN` unset on the app side just sends an empty token, which only an empty
`SHARED_SECRET` accepts.

To rotate: ship a build with the new `SHEETS_TOKEN`, wait for the fleet to update, then change
`SHARED_SECRET`.

## Payload contract

The app POSTs an envelope carrying the shared secret and one batch (= all not-yet-sent records):

```json
{
  "token": "<SHARED_SECRET>",
  "records": [
    {
      "counter": 128,
      "timestamp": 1731000000000,
      "dishId": "ut01000000-00000000-00001234",
      "kitNumber": "KIT-123456",
      "dishSerial": "DISH-..."
    }
  ]
}
```

`counter` fills column A. `timestamp` is still sent as the fallback for it: a record from before the
counter existed carries `"counter": 0`, and so does every record from an older build, so column A
keeps holding the upload time in those cases instead of going blank.

**Settings ▸ Test connection** posts `{"token": "...", "dryRun": true}` instead: the backend checks
the token and resolves the spreadsheet and `Scans` tab — the part that actually fails in the field —
then replies `{"status":"ok","count":0,"dryRun":true}` without writing a row.

A bare JSON array (no envelope) is still accepted from app builds that predate the token, but only
while `SHARED_SECRET` is `''`.

`doPost` appends one row per element and returns `{"status":"ok","count":N}`, where `N` is the number
of rows written. Any other reply (or a non-200) makes the app keep the records `PENDING/FAILED` and
retry later via WorkManager, so no data is lost if the backend is misconfigured.

### Duplicate rows

Appends are **not** deduplicated. The app resends any batch it didn't get a success response for —
including one whose rows already landed before the response was lost, which happens when the dish AP
drops the connection mid-post or the read times out. Those rows are appended again.

That is the trade for a sheet without a key column: a retry can duplicate a row, whereas not
retrying would lose field work outright. Check for repeated rows after a sync that reported an error
and delete them by hand. To get the protection back, store each record's key (the app can send one
again) and skip keys already seen.
