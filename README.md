# DaisySpeler

Eenvoudige Android-speler voor DAISY 2.02-luisterboeken, met een Nederlandse
interface. De app is bedoeld voor uitgepakte DAISY-boeken of ZIP-archieven met
een `ncc.html`, SMIL-bestanden en audio (MP3/WAV).

## Functies

- Boek importeren via de Android-mapkiezer (Storage Access Framework): kies een
  uitgepakte map of een ZIP-bestand. Er zijn geen brede opslagrechten nodig.
- ZIP-bestanden worden veilig naar app-eigen opslag uitgepakt. Daarna gebruikt de
  app dezelfde DAISY-parser en player als bij een uitgepakte map.
- Leest `ncc.html`, optioneel `book.opf` en de SMIL-bestanden. Aaneengesloten
  audioclips worden samengevoegd tot een doorlopende tijdlijn.
- Inhoudsopgave met koppen, pagina's, zijbalken en notities. Standaard worden
  koppen en pagina's getoond; er kan naar alle navigatiepunten worden gefilterd.
- Boekomslagen: als een DAISY-boek een omslagafbeelding heeft (via `ncc.html`
  of `book.opf`, of een bestand met een naam als `cover.jpg` of `omslag.png`),
  wordt die getoond in de bibliotheek en op het speler-scherm. De afbeelding
  wordt lokaal in de app opgeslagen, zodat een map-import blijft werken.
- Vorige/volgende kop, zoeken met de schuifbalk, −15 seconden en +30 seconden.
- Automatische bookmarks: elke keer dat het afspelen start of stopt wordt een
  start/stop-paar opgeslagen. De stopreden wordt bewaard (pauze, slaaptimer,
  einde boek of fout). Bookmarks zijn per boek en per sessie te bekijken en aan
  te tikken om terug te springen.
- "Verder luisteren": de laatste positie wordt tijdens het afspelen en bij
  zoeken/pauzeren opgeslagen en na een herstart hersteld.
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
- Over-scherm met de auteur, de app-versie en de GPL-licentie.

## Gebruiken

1. Zet een uitgepakt DAISY 2.02-boek of een ZIP-bestand op het apparaat
   (bijvoorbeeld in `Download`).
2. Open DaisySpeler en tik op **Boek toevoegen**.
3. Kies **Map kiezen** voor een uitgepakte map, of **ZIP-bestand kiezen** voor
   een ZIP-archief. Geef bij een map leestoegang.
4. Tik op het boek in de bibliotheek om het te openen.

De app onthoudt een gekozen map via een persistente URI-toestemming. Als de map
wordt verplaatst of verwijderd, kan het boek niet meer worden geopend. Een ZIP
wordt bij import uitgepakt naar app-eigen opslag; het originele ZIP-bestand
blijft staan. Als je een ZIP-boek uit de bibliotheek verwijdert, worden de
uitgepakte bestanden verwijderd. Boekgegevens, bookmarks en de slaaptimer worden
lokaal in de app opgeslagen; er is geen netwerkverbinding nodig.

## Bouwen en testen

Vereisten: JDK 17 en Android SDK 36.

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

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
- Geen handmatige bookmarks; bookmarks worden automatisch bij start en stop
  gemaakt.
- Alleen omslagafbeeldingen als los bestand in het boek. Hoezen die alleen in
  ID3-tags van de MP3-bestanden zitten, worden niet gelezen.

## Techniek

- Kotlin, Jetpack Compose en Material 3
- AndroidX Media3 (ExoPlayer + MediaSessionService)
- jsoup voor het lezen van NCC/OPF/SMIL-HTML
- kotlinx.serialization voor lokale JSON-opslag
- Min SDK 24, target/compile SDK 36

Het app-icoon is een madeliefje met een play-knop in het hart. De adaptieve
lagen staan in `app/src/main/res/drawable/ic_launcher_background.xml`,
`ic_launcher_foreground.xml` en `ic_launcher_monochrome.xml` (voor themed icons).
De bronbestanden en 512px-previews staan in `docs/icon/`.

De belangrijkste code staat in:

- `app/src/main/java/nl/simon/daisyspeler/data/parser/` — DAISY-parser
- `app/src/main/java/nl/simon/daisyspeler/ui/components/` — gedeelde UI-onderdelen
  zoals de omslagafbeelding
- `app/src/main/java/nl/simon/daisyspeler/data/importer/` — veilige ZIP-uitpak
- `app/src/main/java/nl/simon/daisyspeler/playback/` — afspelen, bookmarks en
  slaaptimer
- `app/src/main/java/nl/simon/daisyspeler/ui/` — bibliotheek, speler en
  over-scherm
- `app/src/test/java/nl/simon/daisyspeler/` — unit tests

## Licentie

DaisySpeler is open source software onder de GPLv3-licentie (GNU General Public
License, versie 3). Zie [LICENSE](LICENSE) voor de volledige licentietekst.

Copyright (C) 2026 Simon de Waal van Breukelen
