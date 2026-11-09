# Baan — kinderen peilen op de ring

Twee delen: een **locatie-endpoint** (Google Apps Script, dit mapje) en de
**kompas-ring in Baan** (zit in de app vanaf v1.3). De kinderen draaien
**OwnTracks**; dat stuurt hun positie naar jouw endpoint; Baan leest het uit en
tekent per kind een peiling op de buitenste ring.

```
 [OwnTracks op kindtoestel]  --HTTP-->  [Apps Script endpoint + Sheet]  <--HTTP--  [Baan op jouw toestel]
```

Niets loopt langs een derde partij: de data staat in jouw eigen Google-sheet.

## 1. Endpoint opzetten (eenmalig)
1. Ga naar script.google.com → **Nieuw project** → plak `locatie-endpoint.gs`.
2. Zet bovenin `TOKEN` op een eigen, lang geheim woord.
3. **Implementeren → Nieuwe implementatie → Web-app**
   - *Uitvoeren als:* ikzelf
   - *Wie heeft toegang:* iedereen
   - Kopieer de **web-app-URL** (eindigt op `/exec`).

De sheet "Baan locaties" wordt vanzelf aangemaakt bij de eerste melding.

## 2. OwnTracks op elk kindtoestel
1. Installeer **OwnTracks** (Play Store).
2. Preferences → **Connection → Mode → HTTP**.
3. **Connection → Host → URL:**
   ```
   <web-app-URL>?token=<TOKEN>
   ```
4. **Connection → Identification:**
   - *Username:* jouw naam (bv. `sebas`)
   - *Device:* een herkenbare naam per kind, eindigend op de voornaam, bv.
     `telefoon-emma`. Baan leidt de weergavenaam hieruit af ("Emma").
   - *Tracker ID (TID):* 2 letters, bv. `EM`.
5. Zet locatietoestemming op **altijd toestaan** en stel in OwnTracks een
   redelijk meld-interval in (bv. "move"/significant, of elke paar minuten).

> Tip: gebruik voor elk kind een **apart** Device, anders overschrijven ze
> elkaars positie.

## 3. Baan koppelen
In Baan: zeshoekje rechtsboven (Instellingen) → **Kinderen volgen** →
plak dezelfde URL en token. Zet het vervolgens **aan**. Baan vraagt eenmalig
om locatietoestemming (voor je eigen positie) en gebruikt de kompassensor.

## Goed om te weten
- **Richting ± enkele graden, positie seconden–minuten oud.** Goed voor "die
  kant op, ~2 km", niet voor "om de hoek".
- Het kompas gebruikt de magnetische sensor; bij rare uitslag het toestel even
  in een 8-beweging kalibreren. Magnetische vs. ware noord wordt niet
  gecorrigeerd (klein verschil in NL).
- Werkt alleen terwijl Baan op de voorgrond is — het is geen achtergrond-tracker.
- **Wees er open over met je kinderen.** Een gedeelde app die zij zelf draaien
  werkt alleen eerlijk als zij weten dat het aanstaat.

## Later makkelijk te wisselen
Wil je geen Apps Script maar Firestore of Home Assistant? Baan vraagt alleen een
URL die een JSON-array teruggeeft met objecten `{name, lat, lon, tst}`. Elke bron
die dat levert, werkt.
