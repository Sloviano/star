# Apps Script backend (Google Sheets sink)

The app uploads scan records to a Google Sheet through a Google Apps Script **Web App**. The Android
side needs the deployment's `/exec` URL — set it in **Settings ▸ Apps Script URL** — and, if you
enable the shared secret (step 4), a matching `SHEETS_TOKEN` baked into the build.

## 1. Create the sheet

1. Create a new Google Sheet.
2. Rename the first tab to **`Scans`** (must match `SHEET_NAME` in `Code.gs`).
3. Add this header row in row 1:

   | Timestamp | Dish ID | Kit Number | Dish Serial | Upload Key |
   |-----------|---------|------------|-------------|------------|

   **Upload Key** holds each record's idempotency key; `doPost` skips a record whose key is already
   present. Leave the column in place — clearing it lets a retried batch append duplicate rows.
   (An existing sheet with only the first four columns is fine: the script labels the fifth column
   on the next post, and rows already there keep an empty key.)

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
      "timestamp": 1731000000000,
      "dishId": "ut01000000-00000000-00001234",
      "kitNumber": "KIT-123456",
      "dishSerial": "DISH-...",
      "uploadKey": "3f8c…-c21a:47"
    }
  ]
}
```

**Settings ▸ Test connection** posts `{"token": "...", "dryRun": true}` instead: the backend checks
the token and resolves the spreadsheet and `Scans` tab — the part that actually fails in the field —
then replies `{"status":"ok","count":0,"dryRun":true}` without writing a row.

A bare JSON array (no envelope) is still accepted from app builds that predate the token, but only
while `SHARED_SECRET` is `''`.

`doPost` appends one row per element and returns `{"status":"ok","count":N}`, where `N` counts the
rows actually written — records whose `uploadKey` is already in the sheet are skipped, so `N` can be
smaller than the batch (0 when the whole batch was already stored). Any other reply (or a non-200)
makes the app keep the records `PENDING/FAILED` and retry later via WorkManager, so no data is lost
if the backend is misconfigured.

`uploadKey` is `<installId>:<local row id>` and is stable across retries of the same record. That is
what makes a retry safe: the app resends any batch it didn't get a success response for, including
one whose rows already landed before the response was lost.
