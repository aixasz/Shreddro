/**
 * Shreddro — Google Apps Script Web App API Gateway
 * =================================================
 * Accepts POSTed transaction-slip JSON from the Android app, routes each row
 * into a per-bank sheet tab of the master spreadsheet (creating tab + headers
 * on first use), and returns a JSON confirmation.
 *
 * DEPLOYMENT (done once by each user, on their own Google account):
 *  1. sheets.new → note the spreadsheet ID from the URL, paste into SPREADSHEET_ID.
 *  2. Extensions → Apps Script → paste this file.
 *  3. Project Settings → Script properties → add SHARED_SECRET (any long random
 *     string; mirror it in the app's `shreddro.appsScriptSecret`).
 *  4. Deploy → New deployment → type "Web app":
 *       - Execute as: **Me** (so the script writes to your sheet)
 *       - Who has access: **Anyone** (auth is enforced via the shared secret;
 *         Apps Script web apps cannot read custom headers from anonymous
 *         callers in all cases, so the secret is also accepted in the body)
 *  5. Copy the /exec URL into the app's `shreddro.appsScriptUrl`.
 *
 * CONTRACT (request body):
 *   { "bank_name": "...", "date_time": "...", "amount": 123.45,
 *     "sender": "...", "receiver": "...", "reference_id": "...",
 *     "secret": "<shared secret>" }          // header X-Shreddro-Secret also OK
 *
 * RESPONSE: { "status": "ok", "sheet": "KBank", "row": 42 }
 *        or { "status": "error", "error": "message" }
 */

var SPREADSHEET_ID = 'PASTE_YOUR_SPREADSHEET_ID_HERE';

var HEADERS = ['logged_at', 'bank_name', 'date_time', 'amount', 'sender', 'receiver', 'reference_id'];

function doPost(e) {
  var lock = LockService.getScriptLock();
  try {
    // Serialize concurrent posts so appendRow never races header creation.
    lock.waitLock(20 * 1000);

    var payload = parsePayload_(e);
    assertAuthorized_(e, payload);
    var slip = validateSlip_(payload);

    var ss = SpreadsheetApp.openById(SPREADSHEET_ID);
    var sheetName = sanitizeSheetName_(slip.bank_name);
    var sheet = ss.getSheetByName(sheetName);

    // Dynamically create the per-bank tab with headers on first sight.
    if (!sheet) {
      sheet = ss.insertSheet(sheetName);
      sheet.appendRow(HEADERS);
      sheet.getRange(1, 1, 1, HEADERS.length).setFontWeight('bold');
      sheet.setFrozenRows(1);
    }

    sheet.appendRow([
      new Date(),
      slip.bank_name,
      slip.date_time,
      slip.amount,
      slip.sender,
      slip.receiver,
      slip.reference_id,
    ]);

    return jsonResponse_({
      status: 'ok',
      sheet: sheetName,
      row: sheet.getLastRow(),
    });
  } catch (err) {
    return jsonResponse_({ status: 'error', error: String(err && err.message || err) });
  } finally {
    try { lock.releaseLock(); } catch (ignored) {}
  }
}

/** Health check so users can verify the deployment in a browser. */
function doGet() {
  return jsonResponse_({ status: 'ok', service: 'shreddro-gateway', version: 1 });
}

// ── helpers ──────────────────────────────────────────────────────────────────

function parsePayload_(e) {
  if (!e || !e.postData || !e.postData.contents) {
    throw new Error('Empty request body');
  }
  try {
    return JSON.parse(e.postData.contents);
  } catch (err) {
    throw new Error('Body is not valid JSON');
  }
}

function assertAuthorized_(e, payload) {
  var expected = PropertiesService.getScriptProperties().getProperty('SHARED_SECRET');
  if (!expected) throw new Error('SHARED_SECRET script property is not configured');
  var provided = (payload && payload.secret) ||
    (e && e.parameter && e.parameter.secret) || '';
  if (provided !== expected) throw new Error('Unauthorized');
}

function validateSlip_(p) {
  var required = ['bank_name', 'date_time', 'sender', 'receiver', 'reference_id'];
  for (var i = 0; i < required.length; i++) {
    if (typeof p[required[i]] !== 'string') {
      throw new Error('Missing or non-string field: ' + required[i]);
    }
  }
  var amount = Number(p.amount);
  if (isNaN(amount) || amount < 0) throw new Error('Invalid amount');
  return {
    bank_name: p.bank_name.trim() || 'Unknown',
    date_time: p.date_time,
    amount: amount,
    sender: p.sender,
    receiver: p.receiver,
    reference_id: p.reference_id,
  };
}

/** Sheet tab titles: max 100 chars, no []/\*?:  */
function sanitizeSheetName_(name) {
  var cleaned = String(name).replace(/[\[\]\*\/\\\?:]/g, '').trim();
  return (cleaned || 'Unknown').substring(0, 100);
}

function jsonResponse_(obj) {
  return ContentService.createTextOutput(JSON.stringify(obj))
    .setMimeType(ContentService.MimeType.JSON);
}
