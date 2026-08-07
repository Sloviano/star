# Apps Script backend (Google Sheets sink)

The app uploads scan records to a Google Sheet through a Google Apps Script **Web App**. The Android
side only needs the deployment's `/exec` URL — set it in **Settings ▸ Apps Script URL**.

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
3. **Who has access:** *Anyone*. (The endpoint is unauthenticated; the URL itself is the secret.
   Rotate it by creating a new deployment if it leaks.)
4. Copy the **Web app URL** — it looks like
   `https://script.google.com/macros/s/AKfyc.../exec`.
5. Paste it into the app's **Settings ▸ Apps Script URL** and tap **Test connection** — a success
   posts an empty batch and the backend replies `{"status":"ok","count":0}`.

## Payload contract

The app POSTs a JSON **array** of records (one batch = all not-yet-sent records):

```json
[
  {
    "timestamp": 1731000000000,
    "dishId": "ut01000000-00000000-00001234",
    "kitNumber": "KIT-123456",
    "dishSerial": "DISH-...",
    "uploadKey": "3f8c…-c21a:47"
  }
]
```

`doPost` appends one row per element and returns `{"status":"ok","count":N}`, where `N` counts the
rows actually written — records whose `uploadKey` is already in the sheet are skipped, so `N` can be
smaller than the batch (0 when the whole batch was already stored). Any other reply (or a non-200)
makes the app keep the records `PENDING/FAILED` and retry later via WorkManager, so no data is lost
if the backend is misconfigured.

`uploadKey` is `<installId>:<local row id>` and is stable across retries of the same record. That is
what makes a retry safe: the app resends any batch it didn't get a success response for, including
one whose rows already landed before the response was lost.
