# Baan

Een eigen Android-launcher: het startscherm als instrumentpaneel — een klokring
met batterij en dagvoortgang, agenda- en berichtenblokken, een duim-boog met je
meest gebruikte apps, een vastzet-balk, een app-lade met zoeken/sorteren, en een
kompasring die de richting van je kinderen toont.

Pakket: `nl.sebastiaan.baan`.

## Mappen
- `app/` — de launcher (Java-bron, resources, manifest, buildscript).
- `locatie-endpoint/` — Google Apps Script dat OwnTracks-locaties ontvangt en als
  JSON teruggeeft, plus opzet-instructies. Voedt de kompasring.

## Bouwen zonder Gradle / Android SDK
Dit project wordt met losse gereedschappen gebouwd (aapt2, android.jar, dx,
apk_sign_ts), niet met Gradle. Reden: in de oorspronkelijke bouwomgeving zijn
`dl.google.com`, Maven Central en Gradle geblokkeerd.

```bash
# 1. gereedschap ophalen (eenmalig)
./setup-toolchain.sh ~/baan-sdk

# 2. je ondertekensleutel in app/keys/ zetten (key8.pem + cert.pem) — zie onder

# 3. bouwen + ondertekenen
cd app
BAAN_SDK=~/baan-sdk bash build.sh
# -> app/build/Baan.apk
```

Vereisten: Node/npm, JDK 17, Python 3, curl, unzip, zip.

### Belangrijke bouwregels (uit de praktijk)
- **Geen Java-lambda's.** Android kent geen `LambdaMetafactory`; schrijf
  anonieme klassen. `build.sh` controleert zelf op `invokedynamic` en stopt als
  het er toch in zit.
- Een foreground service moet altijd `startForeground` aanroepen, ook bij falen.
- Vraag toestemmingen nooit opnieuw vanuit `onRequestPermissionsResult` (lus).

## Ondertekenen en updates
- De APK wordt v1+v2+v3 ondertekend met `apk_sign_ts`.
- **Updates installeren alleen met dezelfde sleutel.** Bewaar je sleutel
  (`key8.pem`, `k.pem`, `cert.pem`) dus veilig en los van de repo. Ben je 'm
  kwijt, dan moet de oude app eerst verwijderd worden (en ben je je
  launcher-instellingen kwijt).
- Een nieuwe sleutel maken (alleen voor een eerste, schone installatie):
  ```bash
  openssl req -x509 -newkey rsa:2048 -nodes -keyout k.pem -out cert.pem \
    -days 10000 -subj "/CN=Baan"
  openssl pkcs8 -topk8 -nocrypt -in k.pem -out key8.pem
  ```

## Beveiliging
`.gitignore` houdt sleutels (`*.pem`) en build-uitvoer buiten de repo. Commit
nooit je private sleutel, ook niet in een privé-repo-geschiedenis.

## Versie
Bijgehouden in `app/AndroidManifest.xml` (`versionCode` / `versionName`).
Huidige functies o.a.: app-lade met zoeken + sorteren op naam/installdatum,
kleuren-iconen in de lade, vastzet-balk boven de baan, kompasring naar kinderen.

## Niet getest op toestel
De APK's worden in een cloud-omgeving gebouwd en zijn niet op een fysiek toestel
getest. Controleer gedrag na installatie zelf.
