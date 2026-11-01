# Skip-Bo Masters (Android)

Een Android-project in Kotlin en Jetpack Compose voor een mobiele kaartspel-ervaring geïnspireerd op Skip-Bo.

## Status
- Android Studio / Kotlin / Jetpack Compose
- Lokale spelmodus tegen computer
- Twee spelers op één apparaat
- Online tweespelerkamers via Firebase Cloud Functions (Firebase-project vereist)
- Vriendencode, vriendenlijst, vriendschapsverzoeken en privéspeluitnodigingen
- Servergestuurde Elo-ranglijst met paginering
- Nederlandstalige interface
- Donkergroene premium tafelstijl, goudaccenten en grote touchvlakken

## Openen
1. Pak het ZIP-bestand uit.
2. Open de map `SkipBoMasters` in Android Studio.
3. Laat Gradle synchroniseren.
4. Kies **Build > Build Bundle(s) / APK(s) > Build APK(s)**.
5. Installeer de APK op een Android-testtoestel.

## GitHub publiceren
Maak op GitHub een lege repository aan met de naam `skipbo-masters-android`. Voer daarna in de projectmap uit:

```bash
git init
git add .
git commit -m "Initial Skip-Bo Masters Android project"
git branch -M main
git remote add origin https://github.com/JOUW-GEBRUIKERSNAAM/skipbo-masters-android.git
git push -u origin main
```

## Belangrijke ontwikkelnotities
Dit is een vroege speelbare prototypebasis, geen officieel product van Mattel. Skip-Bo is een merk van de betreffende rechthebbende. De interface gebruikt eigen vormgeving en generieke kaartillustraties. Controleer de regels, beurtlogica en kaartverdeling grondig voordat je publiceert. Online multiplayer vereist nog een backend, accounts, matchmaking en synchronisatie.


## Roadmap
- [x] Basiskaartspel met 162 kaarten (144 nummerkaarten + 18 Skip-Bo-jokers)
- [x] Voorraadstapel van 30 kaarten per speler in 2-spelermodus
- [x] Vier gezamenlijke bouwstapels en vier persoonlijke aflegstapels
- [x] Afleggen naar een gekozen persoonlijke aflegstapel
- [x] Premium donkere speeltafel en goudaccenten
- [ ] Uitgebreide QA van alle regelrandgevallen en kaartverdeling
- [ ] Instelbare moeilijkheid voor de computer
- [ ] Geluid, haptiek en kaartanimaties
- [x] Online speelkamers, privékaartopslag en server-side kaartzetvalidatie (v10 basis)
- [ ] App Check afdwingen, duurzame Google-accountkoppeling en volledige QA
- [ ] Testen op verschillende Android-schermformaten

## Let op
De huidige code is een prototype. Het is niet officieel gelicenseerd of verbonden aan Mattel. De spelregels en volledige beurtflow moeten vóór een publieke release worden nagekeken en getest.


## v3 gameplay update
- When a player plays the last card from their hand, the game now draws a fresh hand (up to five cards) immediately and the same turn continues, as in standard Skip-Bo turn flow.
- The computer prioritizes playable low-value cards and Skip-Bo wild cards after checking its stock pile first.
- The app remains an early prototype. This is not a verified official rules implementation and still needs playtesting, including multi-player stock-pile setup, draw-pile recycling, and edge cases.


## Updates in v4
- Als je de laatste kaart uit je hand speelt, trek je meteen vijf nieuwe kaarten en mag je doorspelen.
- Een voltooide bouwstapel wordt onder de trekstapel teruggelegd in plaats van weggegooid.
- Je kiest nu zelf op welke van de vier persoonlijke aflegstapels je aan het einde van je beurt aflegt.
- Dit blijft een speelbaar prototype: computerstrategie, multiplayer en volledige officiële-regelvalidatie zijn nog niet af.


## Updates in v5
- De lokale modus voor twee spelers is nu aan beide kanten bedienbaar: speler 2 kan ook voorraad-, hand- en aflegkaarten spelen en een beurt beëindigen.
- De interface toont de naam van de actieve speler bij diens hand en voorraad.
- In de lokale modus staat nu expliciet dat de telefoon na de beurt wordt doorgegeven.

## Online multiplayer: voorbereiding
De huidige lokale modus is pass-and-play op één toestel; er is nog geen netwerkverbinding.
Voor echte online potjes is een backend nodig (bijvoorbeeld Firebase Authentication + Realtime Database of Firestore), plus een projectconfiguratie van de eigenaar. Zonder die configuratie kan de app niet veilig online kamers aanmaken of synchroniseren. Er zijn daarom nog geen nepknoppen of niet-werkende online matchmaking toegevoegd.


## Updates in v6
- Correctie in de bediening van pass-and-play: speler 2 kan nu ook handkaarten en persoonlijke aflegstapels gebruiken.
- De actieve speler en diens voorraad blijven duidelijk zichtbaar tijdens het wisselen van beurt.
- De online modus wordt nog niet als beschikbaar gepresenteerd. Een echte online lobby vereist een geconfigureerde backend en gedeelde spelstatus; deze versie bevat nog geen werkende netwerkverbinding.


## Updates in v7
- Hersteld: in de modus 'Twee spelers op één telefoon' kunnen beide spelers hun handkaarten spelen en de bovenste kaart van hun eigen aflegstapels gebruiken.
- De computer blijft alleen bedienbaar via de automatische computerbeurt.
- De startpagina maakt duidelijk dat de tweespelermodus lokaal is en nog geen online multiplayer biedt.

## Technische status
- Er is nog geen Firebase-configuratie of server-side spelkamer toegevoegd. Dit vereist een Firebase-project van de app-eigenaar, configuratiebestanden en beveiligingsregels. De app doet daarom geen alsof-online matchmaking.
- Bouw/test deze versie in Android Studio voordat je deze verspreidt.


## v8 — Firebase online lobby foundation

### Added
- Firebase Authentication anonymous sign-in (no email/password registration needed for lobby identity).
- Cloud Firestore room creation with a six-character invite code.
- Join-room flow with a transaction so only one opponent can claim the second seat.
- Live room metadata listener for host/guest names and WAITING/READY state.
- Share-invitation action using the Android share sheet.
- Versioned Firestore game-state transport and per-player private hand documents.
- Firestore security rules: only authenticated users can inspect lobby metadata; only room participants can access shared game state; each private hand document is owner-only.

### Firebase configuration required
1. Open the [Firebase Console](https://console.firebase.google.com/) and create a Firebase project.
2. Add an Android app with package name **`com.skipbomasters`**.
3. Download `google-services.json` and place it in **`app/google-services.json`** (not in the project root). This file is intentionally excluded by `.gitignore`.
4. In Firebase Console, open **Authentication → Sign-in method** and enable **Anonymous**.
5. Create a **Cloud Firestore** database.
6. From the project folder, install/use Firebase CLI and deploy the included rules:
   ```bash
   firebase login
   firebase use --add
   firebase deploy --only firestore:rules
   ```
7. Open the project in Android Studio and run **Sync Project with Gradle Files**. The Google Services plugin is conditionally applied when `app/google-services.json` exists.
8. Install the app on two devices. From **Online spelen**, enter a player name, create a room on device A, and join its six-character code on device B.

Firebase's current Android setup documentation lists Google Services Gradle plugin **4.5.0** and Firebase BoM **35.1.0**; this project uses those versions. Current Firebase guidance also recommends the main Auth/Firestore artifacts instead of the retired KTX artifacts. See https://firebase.google.com/docs/android/setup and https://firebase.google.com/docs/android/kotlin-migration.

### What is and is not online yet
- **Implemented in v8/v9:** anonymous account session, room creation/join, invite code sharing, live room metadata, a shared match snapshot, turn synchronization, and owner-only private-hand documents.
- **Still missing:** server-authoritative move validation, hiding the full draw/stock order from the client payload, reconnect/resume polish, player profiles, matchmaking, and push notifications.
- The included Firestore rules are a starter security boundary, not a substitute for server-side game validation or a production security review.
- This update has not been built or tested in Android Studio in this environment. Firebase project creation, enabling Anonymous Auth, creating Firestore, and deploying rules must be completed by the project owner.

### Android compatibility
- `minSdk` is now 24 (Android 7.0) for the Firebase Android setup baseline.
- No `google-services.json` is included; each developer must provide their own project configuration.


## v9 — eerste realtime online speelmodus

- De host kan vanuit een gevulde lobby een nieuw online spel starten.
- Beide toestellen luisteren naar dezelfde versiebeheerste spelstatus in Firestore.
- De host kan de match starten; beide spelers komen op hetzelfde online speelbord uit.
- Beurten, bouwstapels, voorraden en aflegstapels worden via updates gedeeld.
- De eigen hand wordt als afzonderlijk Firestore-document opgeslagen; Firestore-regels beperken lezen en schrijven tot de eigenaar van die hand.
- Een online speler vult de eigen hand op het eigen toestel aan wanneer de beurt naar hem/haar wisselt.
- Gelijktijdige updates gebruiken een revisienummer in een Firestore-transactie om overschrijven met een verouderde status te voorkomen.

### Belangrijke beperking van deze online prototypefase
De gedeelde JSON-status bevat vooralsnog ook de volledige trekstapelvolgorde en de volledige voorraadstapels. Die gegevens worden niet in het spelscherm getoond, maar zijn voor een technisch onderlegde deelnemer wel uit de databasepayload te halen. Ook controleert Firestore nog niet server-side of iedere kaartzet volgens de regels is toegestaan. Dit is daarom geschikt voor een eerste test met bekenden, niet voor geldprijzen, ranglijsten of competitie. Voor een eerlijke productieversie moeten trekstapel/voorraadlogica en zetvalidatie naar een vertrouwde backend (bijvoorbeeld Cloud Functions) worden verplaatst.


## v10 — server-authoritative online game engine

> Deze versie vervangt de v9-opzet waarbij de client de volledige spelstatus verstuurde. De v9-beschrijving hieronder is historische documentatie.

### Wat is veranderd
- De Android-app verstuurt kaartacties via Firebase Callable Cloud Functions; de client kan de gedeelde spelstatus niet meer rechtstreeks aanpassen.
- `startOnlineMatch` schudt de kaartenset op de server, deelt voorraadstapels van 30 kaarten uit en geeft de host de eerste hand van vijf kaarten.
- `submitOnlineMove` controleert de identiteit, kamer, actieve beurt, revisie, kaartbron, kaartindex en de geldigheid van een zet voordat de server de toestand wijzigt.
- Trekstapel en volledige voorraadstapels staan in server-only documenten; zelfs de eigenaar krijgt de volledige volgorde van de eigen voorraadstapel niet toegestuurd. De publieke status toont alleen het aantal voorraadkaarten en de zichtbare bovenste kaart.
- Iedere speler kan uitsluitend de eigen `privatePlayers/{uid}`-handdocumenten lezen; Firestore-clients mogen geen spelstatus, privékaarten of serverdocumenten schrijven.
- Beurten, aanvullen tot vijf kaarten, het terugleggen van voltooide bouwstapels en het bepalen van de winnaar worden server-side uitgevoerd.
- Een speler kan na een onderbroken verbinding opnieuw deelnemen aan dezelfde kamer.

### Online backend uitrollen
Dit project bevat nu een Cloud Functions-codebase in `functions/` (Node.js 22 + TypeScript). Na het koppelen van `app/google-services.json` en het activeren van Anoniem aanmelden en Cloud Firestore:

1. Installeer Firebase CLI en koppel het Firebase-project aan de map:
   ```bash
   npm install -g firebase-tools
   firebase login
   firebase use --add
   ```
2. Installeer en compileer de backend:
   ```bash
   cd functions
   npm install
   npm run build
   cd ..
   ```
3. Publiceer beveiligingsregels en Cloud Functions:
   ```bash
   firebase deploy --only firestore:rules,functions
   ```
4. Synchroniseer het Android Studio-project, installeer de app op twee Android-toestellen en test het aanmaken van een kamer, deelnemen via code, starten, spelen en opnieuw verbinden.

**Facturering:** Cloud Functions voor Firebase vereist op dit moment het Blaze-pay-as-you-go-abonnement met een gekoppeld Cloud Billing-account. Firebase heeft wel maandelijkse kosteloze gebruiksquota; stel vóór gebruik budgetmeldingen en waar mogelijk spend limits in. Zie https://firebase.google.com/docs/functions/quotas en https://firebase.google.com/docs/projects/billing/firebase-pricing-plans.

### Beveiligingsstatus en resterend werk
- De belangrijkste spelacties en verborgen kaartstapels worden nu server-side beheerd. Dit is een veel sterkere basis dan de client-geschreven state uit v9.
- Firebase App Check is nog niet afgedwongen. Activeer Play Integrity/App Check en zet `enforceAppCheck` aan in de callable-functions voordat de app publiek wordt uitgebracht.
- Anonieme accounts zijn voldoende voor eerste tests, maar accountkoppeling, herstel over toestellen en spelersprofielen ontbreken nog.
- Er is nog geen volledige geautomatiseerde spelregeltest-suite of twee-toestellen-test uitgevoerd. Test ook randgevallen zoals een lege trekstapel, gelijktijdige tikken, netwerkverlies en opnieuw verbinden.
- Dit project is nog niet gebouwd met Gradle in deze omgeving en bevat geen APK. Android Studio moet de uiteindelijke build controleren.

### Versie
- Android `versionCode`: 10
- Android `versionName`: 0.10.0


## v11 — officiële afhandeling van voltooide bouwstapels

De spelregels zijn opnieuw naast de Mattel-instructies gelegd. Een bouwstapel van twaalf kaarten wordt uit het speelgebied verwijderd en apart gelegd. Die kaarten worden pas geschud tot een nieuwe trekstapel wanneer de gewone trekstapel leeg is. De eerdere versies legden voltooide stapels te vroeg terug; dit is in zowel de lokale modus als de server-engine gecorrigeerd.

- Skip-Bo-jokers blijven als fysieke jokerkaart (`0`) bewaard wanneer een bouwstapel wordt afgerond en later opnieuw geschud.
- De server bewaart de aparte stapel met voltooide bouwstapels in een alleen voor de server leesbaar document.
- Bij het trekken controleert de server eerst de gewone trekstapel; alleen als die leeg is, worden voltooide bouwstapels geschud en hergebruikt.
- De lokale spelmodus gebruikt nu dezelfde timing.
- Node.js-tests toegevoegd voor: kaarten blijven apart zolang er nog trekkaarten zijn, hergebruik bij lege trekstapel, behoud van jokerkaarten en lege-stapel randgevallen.

### Testen
Voer vanuit `functions/` uit:
```bash
npm install
npm test
```
Dit compileert de TypeScript-backend en voert daarna de tests op de spelregelhelpers uit. Deze tests draaien los van Firebase en vereisen geen gekoppeld project.

### Versie
- Android `versionCode`: 11
- Android `versionName`: 0.11.0

### Regelbron
Mattel-instructies beschrijven dat een voltooide bouwstapel wordt verwijderd en dat voltooide bouwstapels worden geschud om een lege trekstapel aan te vullen:
- https://service.mattel.com/instruction_sheets/R7311-0920.pdf
- https://service.mattel.com/instruction_sheets/Skip-bo%203L%20Instructions.m3.pdf


## v12 — hervatten na app-herstart of netwerkonderbreking

- De app bewaart lokaal de laatste kamercode en spelersnaam (geen kaartdata of geheimen).
- In het online startscherm verschijnt de optie **Opnieuw verbinden** voor de laatst gebruikte kamer.
- De bestaande Firebase Authentication-identiteit wordt hergebruikt; de backend controleert opnieuw of die UID nog host of gast van de kamer is.
- Nieuwe callable Cloud Function `resumeOnlineMatch` controleert authenticatie, kamerlidmaatschap, kamermodus en overeenstemming van de publieke en privé-revisie.
- De server stuurt alleen de publieke spelsnapshot en de eigen privéhand terug; voorraadvolgorde, trekstapel en serverdocumenten worden niet blootgesteld.
- Vanuit een open kamer kan de speler ook handmatig de spelstatus opnieuw ophalen.
- De optie **Vergeten op dit toestel** wist uitsluitend de lokaal opgeslagen hervatcode; de online kamer en spelstatus worden niet verwijderd.

### Herstelgrenzen
- Een anoniem Firebase-account blijft doorgaans gekoppeld aan dezelfde app-installatie. Bij verwijderen van de app, wissen van app-data of overstappen naar een ander toestel kan de anonieme UID verloren gaan. In dat geval kan de oude kamer niet worden geclaimd door alleen de kamercode te kennen. Voor duurzaam herstel op meerdere toestellen is accountkoppeling (Google Play Games/Google Sign-In of e-mail) de volgende stap.
- Firestore-luisteraars blijven realtime updates ontvangen; de expliciete hervatfunctie haalt na opnieuw verbinden een consistente server-snapshot op.
- De backend is nog niet uitgerold naar een Firebase-project en de Android-app is niet in deze omgeving met Gradle op toestellen gevalideerd.

### Versie
- Android `versionCode`: 12
- Android `versionName`: 0.12.0


### v12.1 kleine lobby-correctie
- De knop om de opgeslagen sessie te vergeten verdwijnt nu direct uit het scherm nadat de lokale sessie is gewist.
- Een succesvolle nieuwe kamer of hervatting werkt de opgeslagen sessie direct in de gebruikersinterface bij.


## v13 — community, vrienden en ranglijst

### Android-functies
- Nieuw hoofdmenu-item **Vrienden & ranglijst**.
- Communityscherm met spelersprofiel, bewerkbare weergavenaam en persoonlijke vriendencode.
- Vriendschapsverzoeken versturen via een code en accepteren/weigeren.
- Vriendenlijst met knop **Daag uit**.
- Privéspeluitnodigingen naar een bestaande vriend.
- Binnengekomen speluitnodigingen accepteren of weigeren.
- Na accepteren wordt de bijbehorende kamer direct in de online lobby geopend.
- Wereldwijde Elo-ranglijst met paginering (25 spelers per pagina) en aanduiding voor voorlopig gerangschikte accounts.

### Cloud Functions
Nieuwe callable functies in `functions/src/index.ts`:
- `ensureFriendCode`
- `requestFriendByCode`
- `respondToFriendRequest`
- `createFriendGameInvite`
- `respondToGameInvite`
- `getCommunityOverview`
- `getLeaderboardPage`

Wanneer een online wedstrijd door de server wordt beëindigd, worden de uitslag, Elo-score, winst/verliesstatistieken, openbare profielgegevens en leaderboarddocumenten binnen dezelfde Firestore-transactie bijgewerkt. Een uitslagdocument met de kamercode als ID voorkomt dubbele scoreverwerking.

### Firestore
- `profiles/{uid}` bevat privéprofielgegevens en kan alleen door de eigenaar worden gelezen.
- `profiles/{uid}/friends/{friendUid}` bevat de eigen vriendenlijst.
- `publicProfiles/{uid}` bevat beperkte openbare spelersinformatie.
- `friendCodes`, `friendRequests`, `gameInvites`, `leaderboard` en `matchResults` kunnen niet rechtstreeks door Android-clients worden gewijzigd.
- Drie samengestelde indexen zijn toegevoegd voor inkomende vriendschapsverzoeken, inkomende speluitnodigingen en de ranglijst.

### Firebase-installatie en uitrol
1. Voeg jouw Firebase-configuratie toe als `app/google-services.json` (dit bestand blijft uitgesloten van de ZIP en Git).
2. Controleer dat Firebase Authentication met anonieme aanmelding is ingeschakeld.
3. Installeer Node.js 22 en Firebase CLI.
4. Voer vanuit de map `functions/` uit:
   ```bash
   npm install
   npm test
   ```
5. Selecteer het eigen Firebase-project en voer vanuit de projectroot uit:
   ```bash
   firebase login
   firebase use --add
   firebase deploy --only firestore:rules,firestore:indexes,functions
   ```
6. Open het project in Android Studio, synchroniseer Gradle en test met twee afzonderlijke Firebase-accounts op twee Android-toestellen.

Cloud Functions-deploy kan een Firebase Blaze-project en gekoppelde facturering vereisen. Stel budgetmeldingen in. Voor publiek gebruik moet Firebase App Check (Play Integrity) worden geconfigureerd en afgedwongen; deze testversie dwingt App Check nog niet af, zodat de functies na basisconfiguratie te testen zijn.

### Kwaliteits- en veiligheidsnotities
- De client kan vriendrelaties, speluitnodigingen, wedstrijdresultaten en ranglijstpunten niet rechtstreeks schrijven.
- Alleen de ontvanger kan een vriendschaps- of spelverzoek accepteren/weigeren.
- Elo-punten worden bijgewerkt bij de servermatige winnende zet; de verliezer verliest hetzelfde aantal punten, met een ondergrens van nul.
- Nieuwe spelers beginnen op 1.000 punten en krijgen de status voorlopig tot tien wedstrijden.
- De ranglijst toont ook voorlopige spelers, maar markeert ze zichtbaar.
- Er zijn unit tests voor Elo-berekening, vriendencodevalidatie en de bestaande kaartstapelregels.
- Deze omgeving kon de pure TypeScript-regelmodules compileren en acht Node-tests uitvoeren. De volledige Firebase Functions-build met de echte Firebase npm-pakketten en de Android Gradle-build zijn hier niet uitgevoerd. Emulator-, Firebase-project- en tweetoestellentests blijven noodzakelijk.

### Versie
- Android `versionCode`: 13
- Android `versionName`: 0.13.0

### Wat nog niet in v13 zit
- Google Sign-In / blijvende accountkoppeling over toestellen is nog niet geïntegreerd; v13 gebruikt de bestaande Firebase Authentication-identiteit (anoniem aanmelden voor nieuwe spelers).
- Vrienden- en speluitnodigingen zijn callable backendflows, maar vereisen uitgerolde Cloud Functions en indexen.
- App Check is nog niet afgedwongen. Schakel Play Integrity/App Check in en zet `enforceAppCheck: true` op de publieke functies vóór een openbare release.
- Volledige Android-Gradle-build, Firebase Emulator Suite-tests en end-to-endtests met twee apparaten zijn niet uitgevoerd in deze omgeving.


## v14 — Google-account koppelen en voortgang herstellen

### Wat is toegevoegd
- Android Credential Manager met Google ID-token via de officiële Google Identity-library.
- **Google-account koppelen** gebruikt Firebase `linkWithCredential` op de huidige speler. Daardoor blijft de bestaande Firebase UID hetzelfde; Firestore-profielen, vriendrelaties, statistieken en wedstrijdreferenties blijven aan dezelfde speler gekoppeld.
- **Bestaand Google-account herstellen** meldt aan met `signInWithCredential`. Op een nieuwe installatie opent dit de eerder gekoppelde Firebase UID en de daar opgeslagen gegevens.
- De app toont expliciet wanneer Google gekoppeld is en houdt het e-mailadres lokaal in het accountscherm; het e-mailadres wordt niet naar openbare spelersprofielen gekopieerd.
- Een accountconflict wordt niet automatisch samengevoegd. De gebruiker krijgt de instructie om het bestaande Google-account te herstellen; cloudgegevens van twee verschillende Firebase UID's worden niet stilzwijgend vermengd.
- De profielinitialisatie bewaart voortaan een reeds opgeslagen spelersnaam bij aanmelden op een ander toestel. Een aparte serverfunctie `updatePlayerDisplayName` verwerkt bewuste naamwijzigingen.
- Er is een extra backendtest toegevoegd die controleert dat accountherstel de bestaande servernaam niet overschrijft.

### Firebase Console instellen voor Google
1. Open Firebase Console → Project settings → Your apps → Android-app `com.skipbomasters`.
2. Voeg de SHA-1 fingerprint van je debug- en/of releasekeystore toe. Omdat dit project geen Gradle Wrapper meelevert, kun je de fingerprint via de Gradle-toolwindow in Android Studio bij `Tasks > android > signingReport` bekijken (of met `gradle signingReport` als Gradle lokaal is geïnstalleerd).
3. Open Authentication → Sign-in method en schakel **Google** in. Laat ook **Anonymous** ingeschakeld zolang nieuwe spelers als gast mogen starten.
4. Download daarna opnieuw `google-services.json`; de bijgewerkte configuratie moet de Web OAuth-client-ID bevatten.
5. Plaats het bestand in `app/google-services.json`. Dit bestand wordt bewust niet in deze ZIP opgenomen en staat in `.gitignore`.
6. Synchroniseer Gradle en bouw de app. In het communityscherm staan nu de acties **Google-account koppelen** en **Bestaand Google-account herstellen**.

De Credential Manager-implementatie gebruikt de Firebase Android BoM 35.1.0 met de door Firebase gedocumenteerde Credential Manager-dependencies (`credentials` 1.3.0, `credentials-play-services-auth` 1.3.0 en `googleid` 1.1.1). Zie de officiële installatiehandleiding: https://firebase.google.com/docs/auth/android/google-signin en de account-linkinghandleiding: https://firebase.google.com/docs/auth/android/account-linking.

### Belangrijke accountkeuze
- Kies **Google-account koppelen** op het toestel waarop de bestaande gastvoortgang staat. Dit is de juiste route om diezelfde UID en de cloudgegevens te behouden.
- Kies **Bestaand Google-account herstellen** op een andere installatie wanneer dat Google-account eerder al gekoppeld is.
- Als een Google-account al bij een andere Firebase UID hoort, worden de accounts niet automatisch samengevoegd. De eigenaar moet bewust het bestaande account herstellen. Historische gegevens van afzonderlijke accounts worden niet samengevoegd.
- Als iemand bij herstel een Google-account kiest dat nog nooit met Skip-Bo Masters is gebruikt, maakt Firebase een nieuw spelersaccount aan; de app meldt dit expliciet.

### Testen
Vanuit `functions/`:
```bash
npm install
npm test
```

Op Android moet daarna in Android Studio een Gradle-sync en build worden uitgevoerd. Test minimaal:
1. Gastaccount maken en een vriendencode/wedstrijdgegevens aanmaken.
2. Google-account aan dat gastaccount koppelen.
3. App afsluiten en opnieuw openen; controleer dat dezelfde spelersnaam, vriendencode en statistieken zichtbaar blijven.
4. Op een tweede Android-installatie met hetzelfde Google-account aanmelden; controleer dat dezelfde Firebase UID en profielgegevens worden geladen.
5. Een Google-account gebruiken dat al aan een andere Firebase UID is gekoppeld; controleer dat de app een begrijpelijke conflictmelding geeft en geen automatische merge uitvoert.

### Versie
- Android `versionCode`: 14
- Android `versionName`: 0.14.0

### Validatiestatus
De projectconfiguraties en bronbestanden zijn statisch gecontroleerd. De Firebase-configuratie, Google OAuth-client, Gradle-build en aanmelding op twee fysieke toestellen zijn project-eigenaarstaken en zijn niet vanuit deze bronbundel uitgevoerd. Er is geen APK bijgevoegd.

## v15 — persoonlijk profiel en wedstrijdgeschiedenis

### Android
- Het community-profiel toont nu ook het winstpercentage op basis van de serverstatistieken.
- Nieuw tabblad **Geschiedenis** met de twintig meest recente online uitslagen.
- Elke kaart toont de tegenstander, datum, winst/verlies en de persoonlijke Elo-wijziging.
- De speler kan de geschiedenis handmatig vernieuwen.

### Backend en privacy
- Nieuwe callable functie `getMyMatchHistory` vereist een ingelogde Firebase-speler.
- De functie haalt alleen resultaatdocumenten op waarin de UID van de aangemelde speler als winnaar of verliezer voorkomt.
- De server retourneert alleen de tegenstander, uitslag, datum en de voor de speler gesigneerde Elo-wijziging; geen UID's of gegevens van andere wedstrijden.
- Firestore blijft directe clienttoegang tot `matchResults` blokkeren.
- Twee samengestelde indexen toegevoegd voor het op datum ophalen van gewonnen en verloren wedstrijden.
- Unit tests controleren dat de historie tot de eigen UID beperkt blijft, Elo-verlies negatief wordt getoond en ongeldige records worden genegeerd.

### Firebase bijwerken
Voer na het ophalen van deze versie vanuit de projectroot uit:
```bash
firebase deploy --only firestore:indexes,functions
```
De callable functie `getMyMatchHistory` en de twee Firestore-indexen moeten actief zijn voordat het tabblad Geschiedenis online gegevens kan laden.

### Versie
- Android `versionCode`: 15
- Android `versionName`: 0.15.0

### Nog niet in v15
- Profielfoto uploaden is nog niet geïntegreerd.
- De geschiedenis toont maximaal twintig recente online wedstrijden; lokale potjes tegen de computer worden niet naar Firebase opgeslagen.
- Firebase deploy en fysieke toesteltests blijven nodig.

