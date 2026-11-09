/**
 * Baan — locatie-endpoint
 * ------------------------------------------------------------------
 * Ontvangt locaties van de OwnTracks-app (HTTP-modus) van je kinderen
 * en bewaart per toestel de laatste positie in een Google Sheet.
 * Baan haalt die posities op via doGet.
 *
 * EENMALIG INSTELLEN
 *  1. script.google.com -> Nieuw project -> plak deze code.
 *  2. Zet hieronder TOKEN op een eigen, lang, geheim woord.
 *  3. Implementeren -> Nieuwe implementatie -> type "Web-app".
 *       Uitvoeren als: ikzelf
 *       Wie heeft toegang: iedereen
 *     Kopieer de web-app-URL (eindigt op /exec).
 *  4. In OwnTracks op elk kindertoestel (zie README) vul je als URL in:
 *       <web-app-URL>?token=<TOKEN>
 *  5. In Baan plak je bij "Kinderen volgen" diezelfde URL + token.
 *
 * De sheet wordt automatisch aangemaakt bij de eerste melding.
 */

var TOKEN = 'ZET-HIER-EEN-GEHEIM-WOORD';   // <-- wijzig dit
var SHEET = 'posities';
var MAX_AGE_DAYS = 7;                       // oude rijen opruimen
var DEFAULT_INTERVAL = 5;                   // minuten, tot Baan iets anders zet

// Gewenste meld-interval (minuten). Baan zet het; Kidsloc leest het terug.
function getInterval() {
  var v = PropertiesService.getScriptProperties().getProperty('interval');
  var n = v ? parseInt(v, 10) : DEFAULT_INTERVAL;
  return (n >= 1 && n <= 120) ? n : DEFAULT_INTERVAL;
}
function setInterval_(min) {
  var n = parseInt(min, 10);
  if (!(n >= 1 && n <= 120)) return getInterval();
  PropertiesService.getScriptProperties().setProperty('interval', String(n));
  return n;
}

function doPost(e) {
  // OwnTracks post JSON; wij verwachten ?token=... in de URL.
  if (!check(e)) return json([]);
  var body;
  try { body = JSON.parse(e.postData.contents); } catch (err) { return json([]); }
  if (!body || body._type !== 'location') return json([]);

  // Identiteit: OwnTracks stuurt gebruiker/toestel mee in headers; in de
  // payload hebben we 'tid' (2 letters). We bouwen een stabiele sleutel.
  var tid = body.tid || '';
  var topic = body.topic || '';            // bv. owntracks/sebas/telefoon-emma
  var key = topic || ('tid:' + tid);
  var name = nameFrom(topic, tid);

  var lock = LockService.getScriptLock();
  try {
    lock.waitLock(5000);
    var sh = sheet();
    var data = sh.getDataRange().getValues();
    var row = -1;
    for (var i = 1; i < data.length; i++) if (data[i][0] === key) { row = i + 1; break; }
    var values = [key, name, Number(body.lat), Number(body.lon),
                  Number(body.tst) * 1000, Number(body.acc || 0),
                  Number(body.batt || -1), new Date()];
    if (row === -1) sh.appendRow(values);
    else sh.getRange(row, 1, 1, values.length).setValues([values]);
    prune(sh);
  } catch (err) {
    // niets: locatie gaat dan verloren, geen drama
  } finally {
    lock.releaseLock();
  }
  // Antwoord met het gewenste interval; Kidsloc past zich daarop aan.
  // (OwnTracks negeert dit onbekende type.)
  return json([{ _type: 'interval', min: getInterval() }]);
}

function doGet(e) {
  if (!check(e)) return json({ error: 'unauthorized' });
  // Baan stelt het interval in of vraagt het op.
  if (e.parameter.set === 'interval') return json({ ok: true, interval: setInterval_(e.parameter.min) });
  if (e.parameter.get === 'interval') return json({ interval: getInterval() });
  var sh = sheet();
  var data = sh.getDataRange().getValues();
  var out = [];
  for (var i = 1; i < data.length; i++) {
    var r = data[i];
    if (!r[0]) continue;
    out.push({
      id: r[0], name: r[1], lat: r[2], lon: r[3],
      tst: r[4], acc: r[5], batt: r[6]
    });
  }
  return json(out);
}

function check(e) {
  return e && e.parameter && e.parameter.token === TOKEN && TOKEN !== 'ZET-HIER-EEN-GEHEIM-WOORD';
}

function nameFrom(topic, tid) {
  if (topic) {
    var parts = topic.split('/');
    var last = parts[parts.length - 1] || tid;
    // "telefoon-emma" -> "Emma"
    var bits = last.split(/[-_ ]/);
    var w = bits[bits.length - 1] || last;
    return w.charAt(0).toUpperCase() + w.slice(1);
  }
  return tid || '??';
}

function sheet() {
  // Los script heeft geen 'actief' spreadsheet: onthoud het ID, anders maakt
  // elke melding een nieuw sheet aan.
  var props = PropertiesService.getScriptProperties();
  var id = props.getProperty('ssid');
  var ss = null;
  if (id) { try { ss = SpreadsheetApp.openById(id); } catch (e) { ss = null; } }
  if (!ss) { ss = SpreadsheetApp.create('Baan locaties'); props.setProperty('ssid', ss.getId()); }
  var sh = ss.getSheetByName(SHEET);
  if (!sh) {
    sh = ss.insertSheet(SHEET);
    sh.appendRow(['key', 'naam', 'lat', 'lon', 'tst', 'acc', 'batt', 'bijgewerkt']);
  }
  return sh;
}

function prune(sh) {
  var data = sh.getDataRange().getValues();
  var cutoff = Date.now() - MAX_AGE_DAYS * 86400000;
  for (var i = data.length - 1; i >= 1; i--) {
    var t = Number(data[i][4]);
    if (t && t < cutoff) sh.deleteRow(i + 1);
  }
}

function json(obj) {
  return ContentService.createTextOutput(JSON.stringify(obj))
      .setMimeType(ContentService.MimeType.JSON);
}
