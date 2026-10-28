# Regressietests

## Lokale tests en build

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

De tests omvatten onder meer opslagfouten met opnieuw proberen, behoud van bestaande
JSON-gegevens, teruglopende/herhaalde clips, de 60ms-tolerantie bij clipgrenzen,
downloadstatussen en de volgorde van opslagopdrachten bij afsluiten.
Bij herhaalde audio helpt de opgeslagen globale positie de juiste herhaling te
vinden; bij een gewijzigde boekindeling blijft herstel via het bronbestand werken.

## Android-tests zonder bestaande appgegevens te overschrijven

Gebruik een aparte emulator of testtelefoon (Android 8 of nieuwer), zonder echte
boeken of accounts. De instrumentatietests maken en verwijderen eigen testgegevens.
Het onderstaande commando gebruikt een afzonderlijk applicationId, zodat een
normale DaisySpeler-installatie niet wordt vervangen:

```bash
./gradlew :app:connectedDebugAndroidTest \
  -I gradle/isolated-tests.init.gradle --no-configuration-cache
```

Het init-script werkt alleen als het expliciet met `-I` wordt opgegeven. Voer na
de tests een normale `:app:assembleDebug` uit om weer een APK met het reguliere
applicationId te bouwen. De emulator hoeft na de tests niet te worden afgesloten.

De Android-tests gebruiken een lokaal gegenereerd WAV-boek. Ze controleren onder
meer afspelen, zoeken, snelheid, bookmarks, normale timer-expiratie, het vervangen
en annuleren van een timer tijdens de fade-out, audiofocusverlies met hervatten en
een laadtimeout. Ook ZIP-herimport, positieherstel bij herhaalde audio, behoud van
een bestaande omslag bij een schrijffout, boek herladen, buffering en
achtergrondbediening worden getest. De timeouttest wacht bewust op de echte
termijn van 20 seconden.

Voor een volledige apparaatcontrole blijven fysieke headset-/Bluetoothbediening,
SAF-maptoestemmingen en downloads bij de echte boekwebsites aanvullende handmatige
tests. Er zijn geen accounts of internetdownloads nodig voor de automatische tests.

## Bewust ongewijzigd gedrag

Een open automatische bookmarksessie wordt na procesherstart binnen twaalf uur
nog steeds hergebruikt. Na twaalf uur wordt deze als `STALE` afgesloten voordat een
nieuwe sessie begint. Dit bestaande beleid is vastgelegd met een regressietest en
niet gewijzigd; een andere definitie van een luistersessie vraagt een apart besluit.

Bij service-afsluiting geldt nog steeds een maximale wachttijd van twee seconden
voor de opslagwachtrij. De definitieve positie wordt nu achter oudere schrijfopdrachten
geplaatst. Bij uitzonderlijk trage of defecte opslag kan die wachttijd verlopen;
dit wordt gelogd en is geen garantie tegen abrupt procesverlies of opslaguitval.
