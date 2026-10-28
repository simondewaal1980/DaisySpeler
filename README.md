# DaisySpeler

**Versie 6.0**

Eenvoudige Android-speler voor DAISY 2.02-luisterboeken, met een Nederlandse
interface. De app is bedoeld voor uitgepakte DAISY-boeken of ZIP-archieven met
een `ncc.html`, SMIL-bestanden en audio (MP3/WAV). Boeken kunnen van het
apparaat worden geïmporteerd of via de ingebouwde boekbrowser worden
gedownload.

## Functies

### Boeken toevoegen

- Boek importeren via de Android-mapkiezer (Storage Access Framework): kies een
  uitgepakte map of een ZIP-bestand. Er zijn geen brede opslagrechten nodig.
- ZIP-bestanden worden veilig naar app-eigen opslag uitgepakt. Daarna gebruikt de
  app dezelfde DAISY-parser en player als bij een uitgepakte map.
- Een map kan opnieuw worden geïmporteerd; de app leest de actuele inhoud dan
  opnieuw in.
- Ingebouwde boekbrowser voor Passend Lezen en ABC Global Books. Log in met je
  eigen account en download een boek.
- De downloadmap is standaard `Download/audioboeken` en de naam is in de app aan
  te passen. Downloads blijven doorlopen als de app naar de achtergrond gaat.
- ZIP-bestanden die via de boekbrowser binnenkomen, worden automatisch aan de
  bibliotheek toegevoegd. Dat gebeurt bij de downloadmelding, bij terugkeer in de
  app of met de knop **Toevoegen**. Mislukte imports worden enkele keren opnieuw
  geprobeerd.
- Andere gedownloade bestanden blijven in de downloadmap staan en kunnen
  handmatig worden toegevoegd.

### DAISY-parser

- Leest `ncc.html`, optioneel `book.opf` en de SMIL-bestanden. Aaneengesloten
  audioclips worden samengevoegd tot een doorlopende tijdlijn.
- Ondersteunt tekstcoderingen met een UTF-8- of UTF-16-BOM en URL-encoded paden
  in OPF-bestanden.
- Ontbrekende `clip-end`-tijden worden waar mogelijk aangevuld met de volgende
  clip in hetzelfde audiobestand.
- Inhoudsopgave met koppen, pagina's, zijbalken en notities. Standaard worden
  koppen en pagina's getoond; er kan naar alle navigatiepunten worden gefilterd.

### Bibliotheek en omslagen

- Boekomslagen: als een DAISY-boek een omslagafbeelding heeft (via `ncc.html`
  of `book.opf`, of een bestand met een naam als `cover.jpg` of `omslag.png`),
  wordt die getoond in de bibliotheek en op het speler-scherm. De afbeelding
  wordt lokaal in de app opgeslagen, zodat een map-import blijft werken.
- Als een boek geen omslag heeft, wordt indien mogelijk een hoes uit de
  ID3-tags van de audio gebruikt.
- Zelf een omslag kiezen: via het afbeeldingsicoon bij een boek (of rechtsboven
  in de speler) opent een kleine browser met Google Afbeeldingen, al ingesteld
  op titel en auteur. Houd een afbeelding ingedrukt om hem als omslag op te
  slaan.
- De boekenplank sorteren op alfabetische titel of op laatst gelezen boek. De
  gekozen sortering blijft bewaard na een herstart.
- De iconen in de bibliotheek kunnen worden vergroot via de vergrootglasknop.
- Bij elk boek is de voortgang en de positie van **Verder luisteren** zichtbaar.

### Speler

- Compact speler-scherm: omslag, titel en huidige hoofdstuk naast elkaar, met
  daaronder de bediening en een compacte actierij voor hoofdstuknavigatie,
  inhoud, snelheid, bookmarks en de slaaptimer.
- Vorige/volgende kop, zoeken met de schuifbalk, −15 seconden en +30 seconden.
- Afspeelsnelheid van 0,5× (twee keer zo langzaam) tot 2,0× (twee keer zo snel),
  in stappen van 0,1×. De gekozen snelheid blijft bewaard na een herstart en de
  stemtoonhoogte verandert niet mee.
- Slaaptimer in stappen van 15 minuten tot 2 uur, met aftelling. Bij het
  verstrijken pauzeert de weergave en wordt een bookmark met reden
  `SLEEP_TIMER` opgeslagen. In debugbuilds is er een testoptie van 10 seconden.
- Afspelen op de achtergrond via een Media3 `MediaSessionService`, met
  meldings- en vergrendelschermbediening.
- Bluetooth- en headsetbediening via AVRCP/media buttons: play/pause,
  volgende/vorige hoofdstuk en stoppen. De app pauzeert ook als de audio-output
  wegvalt, bijvoorbeeld bij het loskoppelen van een Bluetooth-koptelefoon.
- Een play-toets op een headset of auto kan het laatst gespeelde boek ook
  hervatten wanneer de app niet meer actief is.

### Bookmarken en verder luisteren

- Automatische bookmarks: elke keer dat het afspelen start of stopt wordt een
  start/stop-paar opgeslagen. De stopreden wordt bewaard (pauze, slaaptimer,
  einde boek, boekwissel of fout). Per boek worden de vijf meest recente sessies
  bewaard; oudere worden automatisch opgeruimd.
- Bookmarks zijn per boek en per sessie te bekijken en aan te tikken om terug te
  springen. Automatische bookmarks kunnen afzonderlijk of in één keer worden
  verwijderd.
- Eigen bookmarks: met **Markeren** bewaar je de huidige plek, met een
  optionele notitie. Eigen bookmarks staan in het bookmarkscherm boven de
  automatische bookmarks en kunnen afzonderlijk worden verwijderd.
- "Verder luisteren": de laatste positie wordt tijdens het afspelen en bij
  zoeken/pauzeren opgeslagen en na een herstart hersteld.

### Over de app

- Over-scherm met de auteur, de app-versie, een korte helpfunctie en de
  GPL-licentie.
- Met de afsluitknop sluit je de app veilig af; de luisterpositie wordt bewaard.

## Gebruiken

1. Zet een uitgepakt DAISY 2.02-boek of een ZIP-bestand op het apparaat
   (bijvoorbeeld in `Download`), of gebruik de ingebouwde boekbrowser.
2. Open DaisySpeler en tik op **Boek toevoegen**.
3. Kies **Map kiezen** voor een uitgepakte map, **ZIP-bestand kiezen** voor een
   lokaal ZIP-archief, of kies een website onderaan het venster.
4. Tik op het boek in de bibliotheek om het te openen.

Bij een website kies je eerst de site, log je in met je eigen account en start je
de download. Een ZIP-bestand wordt daarna automatisch aan de bibliotheek
toegevoegd. Voor de downloadbrowser is op Android 9 en ouder de
opslagtoestemming nodig; op nieuwere Android-versies regelt de app dit zonder
brede opslagrechten.

De app onthoudt een gekozen map via een persistente URI-toestemming. Als de map
wordt verplaatst of verwijderd, kan het boek niet meer worden geopend. Een ZIP
wordt bij import uitgepakt naar app-eigen opslag; het originele ZIP-bestand
blijft staan. Als je een ZIP-boek uit de bibliotheek verwijdert, worden de
uitgepakte bestanden verwijderd. Boekgegevens, bookmarks en de slaaptimer worden
lokaal in de app opgeslagen. De coverbrowser en de boekbrowser gebruiken
internet: de eerste opent Google Afbeeldingen en downloadt de gekozen afbeelding,
de tweede opent de boekwebsites en downloadt ZIP-bestanden. De rest van de app
werkt zonder netwerkverbinding.

## Bouwen en testen

Vereisten: JDK 17 en Android SDK 36.

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

Zie [Regressietests](docs/regression-tests.md) voor de aanvullende controles en
Android-tests met een aparte testinstallatie.

De debug-APK staat daarna in:

```text
app/build/outputs/apk/debug/app-debug.apk
```

Installeren op een aangesloten toestel of emulator:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Beperkingen in deze versie

- Alleen DAISY 2.02. DAISY 3 en andere formaten worden niet ondersteund.
- ZIP-bestanden worden eerst uitgepakt naar app-opslag. Er is dus voldoende
  vrije ruimte nodig voor de uitgepakte audio.
- Geen gesynchroniseerde tekstweergave; de app is gericht op audio en navigatie.
- Eén boek per gekozen map of ZIP-bestand. Kies bij meerdere boeken steeds het
  bestand of de map van het afzonderlijke boek.
- De boekbrowser opent alleen de websites die in de app zijn opgenomen en
  vereist een eigen account en internetverbinding.
- Alleen ZIP-bestanden worden automatisch uit de downloadmap geïmporteerd.
  Andere bestanden moeten handmatig worden toegevoegd.
- Boeken zonder omslagafbeelding en zonder ID3-hoes tonen het standaard
  boek-icoon. Via de coverbrowser kan er alsnog handmatig een omslag worden
  gekozen; daarvoor is wel internet nodig.

## Techniek

- Kotlin, Jetpack Compose en Material 3
- AndroidX Media3 (ExoPlayer + MediaSessionService)
- AndroidX Navigation 3
- jsoup voor het lezen van NCC/OPF/SMIL-HTML
- kotlinx.serialization voor lokale JSON-opslag
- Min SDK 24, target/compile SDK 36
- Versie 6.0 (`versionCode` 8)

Het app-icoon is een madeliefje met een play-knop in het hart. De adaptieve
lagen staan in `app/src/main/res/drawable/ic_launcher_background.xml`,
`ic_launcher_foreground.xml` en `ic_launcher_monochrome.xml` (voor themed icons).
De bronbestanden en 512px-previews staan in `docs/icon/`.

De belangrijkste code staat in:

- `app/src/main/java/nl/simon/daisyspeler/data/parser/` — DAISY-parser
- `app/src/main/java/nl/simon/daisyspeler/data/importer/` — veilige ZIP-uitpak
- `app/src/main/java/nl/simon/daisyspeler/data/download/` — downloadbrowser en
  automatische import van ZIP-bestanden
- `app/src/main/java/nl/simon/daisyspeler/data/source/` — map- en ZIP-bronnen
- `app/src/main/java/nl/simon/daisyspeler/data/storage/` — bibliotheek, bookmarks,
  slaaptimer en voorkeuren
- `app/src/main/java/nl/simon/daisyspeler/playback/` — afspelen, bookmarks en
  slaaptimer
- `app/src/main/java/nl/simon/daisyspeler/ui/` — bibliotheek, speler,
  boekbrowser, omslagbrowser en over-scherm
- `app/src/test/java/nl/simon/daisyspeler/` — unit tests

## Licentie

DaisySpeler is open source software onder de GPLv3-licentie (GNU General Public
License, versie 3). Zie [LICENSE](LICENSE) voor de volledige licentietekst.

Copyright (C) 2026 Simon de Waal van Breukelen
