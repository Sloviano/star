/**
 * Google Apps Script Web App backend for the Starlink Kit Provisioning Scanner.
 *
 * Receives a JSON array of scan records POSTed by the Android app's UploadWorker and appends one
 * row per record to the "Scans" sheet. An empty array ("[]") is a valid connectivity test ping and
 * simply returns { status: "ok", count: 0 } without writing anything.
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

var HEADER = ['Timestamp', 'Dish ID', 'Kit Number', 'Dish Serial'];

function doPost(e) {
  try {
    if (!e || !e.postData || !e.postData.contents) {
      return json({ status: 'error', message: 'Empty request body' });
    }

    var parsed = JSON.parse(e.postData.contents);

    // Connectivity probe from the app's Settings ▸ Test connection. Resolves the spreadsheet and the
    // Scans tab (creating the tab if missing) to prove the real write path works — but persists no row.
    if (parsed && !Array.isArray(parsed) && parsed.dryRun === true) {
      getSheet();
      return json({ status: 'ok', count: 0, dryRun: true });
    }

    var items = Array.isArray(parsed) ? parsed : [parsed];
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
