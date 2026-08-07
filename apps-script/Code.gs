/**
 * Google Apps Script Web App backend for the Starlink Kit Provisioning Scanner.
 *
 * Receives scan records POSTed by the Android app's UploadWorker as { token, records: [...] } and
 * appends one row per record to the "Scans" sheet. An empty batch is a valid connectivity test ping
 * and simply returns { status: "ok", count: 0 } without writing anything. Requests must carry the
 * shared secret; see SHARED_SECRET.
 *
 * Deploy: Extensions ▸ Apps Script ▸ Deploy ▸ New deployment ▸ Web app,
 *   Execute as: Me, Who has access: Anyone. Copy the /exec URL into the app's Settings.
 *   After ANY edit you must publish a NEW VERSION (Manage deployments ▸ Edit ▸ Version: New version),
 *   otherwise /exec keeps running the old code.
 */

// Spreadsheet the rows go into. Leave blank ('') ONLY if this script is container-bound to the
// sheet (created via the sheet's Extensions ▸ Apps Script). For a standalone script,
// getActiveSpreadsheet() is null, so paste the spreadsheet id from its URL here:
//   https://docs.google.com/spreadsheets/d/<THIS_PART_IS_THE_ID>/edit
var SHEET_ID = '';

// The tab that receives rows. Auto-created with a header row if missing.
var SHEET_NAME = 'Scans';

// Shared secret the app must present in the request body, matching SHEETS_TOKEN in the Android
// build's local.properties. The deployment is "Anyone"-access and unauthenticated, so without this
// the /exec URL by itself is write access to your sheet — and that URL travels widely.
//
// Leave '' to accept unauthenticated posts (the original behaviour). When turning it on, roll out
// the app build that sends the token FIRST, then set it here: the reverse order rejects uploads
// from installed builds until they are updated. (Their records stay PENDING and retry, so nothing
// is lost — but technicians stop syncing in the meantime.)
//
// This raises the bar; it does not make the endpoint secret. Anyone holding the APK can extract the
// token, so treat it as "the URL alone is not enough", not as real authentication.
var SHARED_SECRET = '';

var HEADER = ['Timestamp', 'Dish ID', 'Kit Number', 'Dish Serial'];

function doPost(e) {
  try {
    if (!e || !e.postData || !e.postData.contents) {
      return json({ status: 'error', message: 'Empty request body' });
    }

    var parsed = JSON.parse(e.postData.contents);

    // Current builds post an envelope: { token, records } or { token, dryRun }. A bare array is the
    // pre-token payload from an older build, which carries no token.
    var isEnvelope = parsed && !Array.isArray(parsed) && typeof parsed === 'object';

    if (SHARED_SECRET && (!isEnvelope || parsed.token !== SHARED_SECRET)) {
      // Apps Script cannot set a status code, so this is a 200 the app reads as a failure: it keeps
      // the records and retries. The reason surfaces in Settings ▸ Diagnostics ▸ Last upload error.
      return json({ status: 'error', message: 'Unauthorized' });
    }

    // Connectivity probe from the app's Settings ▸ Test connection. Resolves the spreadsheet and the
    // Scans tab (creating the tab if missing) to prove the real write path works — but persists no row.
    if (isEnvelope && parsed.dryRun === true) {
      getSheet();
      return json({ status: 'ok', count: 0, dryRun: true });
    }

    var items;
    if (isEnvelope) {
      items = Array.isArray(parsed.records) ? parsed.records : [];
    } else if (Array.isArray(parsed)) {
      items = parsed;
    } else {
      items = [parsed];
    }
    if (items.length === 0) {
      return json({ status: 'ok', count: 0 });
    }

    var written = appendRows(items);
    return json({ status: 'ok', count: written });
  } catch (err) {
    // Surfaced to the Android app (SheetsUploader → Settings ▸ Diagnostics ▸ Last upload error).
    return json({ status: 'error', message: String(err && err.message ? err.message : err) });
  }
}

/** Map records to rows and append them in one batched write. Returns the number of rows written. */
function appendRows(items) {
  var sheet = getSheet();
  var rows = items.map(function (d) {
    return [
      d.timestamp ? new Date(d.timestamp) : '',
      d.dishId || '',
      d.kitNumber || '',
      d.dishSerial || ''
    ];
  });
  sheet.getRange(sheet.getLastRow() + 1, 1, rows.length, rows[0].length).setValues(rows);
  SpreadsheetApp.flush(); // force the write before the request returns
  return rows.length;
}

/** Resolve the target spreadsheet (by id, or the bound one) and the Scans tab, creating it if new. */
function getSheet() {
  var ss = SHEET_ID ? SpreadsheetApp.openById(SHEET_ID) : SpreadsheetApp.getActiveSpreadsheet();
  if (!ss) {
    throw new Error('No spreadsheet. Set SHEET_ID (standalone script) or bind this script to the sheet.');
  }
  var sheet = ss.getSheetByName(SHEET_NAME);
  if (!sheet) {
    sheet = ss.insertSheet(SHEET_NAME);
    sheet.appendRow(HEADER);
  }
  return sheet;
}

function json(obj) {
  return ContentService
    .createTextOutput(JSON.stringify(obj))
    .setMimeType(ContentService.MimeType.JSON);
}
