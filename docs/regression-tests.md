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

Sinds de stabilisatieronde zijn er ook unit tests voor:

- de open/load-state: een `READY` zonder media wordt één keer gericht hersteld en
  daarna een duidelijke fout; timeouts, retry en verouderde request-id's worden
  gecontroleerd;
- repository-concurrentie: een trage import of omslagcontrole van boek A houdt
  het openen van boek B niet tegen, en annuleren geeft de per-boek-lock vrij;
- `findNextStart`: duizenden clips zonder `clip-end` blijven correct en binnen
  een redelijke looptijd verwerkt.

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
en annuleren van een timer tijdens de fade-out, tijdelijk én blijvend
audiofocusverlies met expliciet hervatten, en een laadtimeout. Ook ZIP-herimport,
positieherstel bij herhaalde audio, behoud van een bestaande omslag bij een
schrijffout, boek herladen, buffering en achtergrondbediening worden getest.

Voor de stabilisatieronde zijn daar tests bij gekomen voor een inconsistente
`READY` zonder media, retry na een laadfout, play tijdens laden, play vanuit
`IDLE` en `ENDED`, activiteit naar `CREATED` en terug naar `RESUMED`, opnieuw
verbinden na een service-herstart, en hervatten vanaf de media-notificatie na een
service-herstart met de juiste positie en één luistersessie. Ook het openen van
een ander boek terwijl een grote ZIP-import loopt, moet binnen de deadline
`READY` of een duidelijke fout met retry opleveren. De timeouts in deze tests zijn
kort en testbaar gehouden; er wordt niet alleen op een langere spinner gewacht.

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
