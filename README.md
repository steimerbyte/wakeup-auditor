# Wakeup Auditor

Rangliste, welche Apps das Gerät im Hintergrund beschäftigen — plus ein Schalter
je App, der ihr die Hintergrundausführung nimmt.
Die App startet fünf `dumpsys`-Kommandos mit Shell-Rechten (über Shizuku) und
wertet sie lokal aus. **Eine** Schreiboperation gibt es: der `HINTERGRUND`-Schalter
setzt `RUN_ANY_IN_BACKGROUND` per `cmd appops` (siehe unten). Ohne Tippen wird
nichts verändert.

- Package: `com.steimerbyte.wakeupauditor`
- Label: Wakeup Auditor
- Version 1.4.0, minSdk 31, target/compile 35
- Gerät: Nothing Phone, NothingOS/Android 17 (`C5.0-261023-2146`)

## Genutzte Kommandos

| Nummer | Kommando | Auswertung |
|---|---|---|
| 1 | `dumpsys jobscheduler` | Jobs je Quellpaket (Blockkopf `JOB …`, Paket aus `Source: uid=… pkg=…`), nebenbei der Standby-Bucket je Job |
| 2 | `dumpsys alarm` | gefeuerte Alarme je Paket (Abschnitt `Alarm Stats:`) |
| 3 | `dumpsys batterystats --charged` | `UID u0aNNN: … fg/bg/fgs/cached (dauer)` |
| 4 | `cmd deviceidle whitelist` | Doze-Ausnahmen (verschiedene Pakete) |
| 5 | `cmd package list packages -U` | UID → Paketname |
| 6 | `cmd appops get <pkg> RUN_ANY_IN_BACKGROUND` | Zustand des Hintergrund-Schalters (einzeln, siehe Warteschlange) |
| 7 | `cmd appops set <pkg> RUN_ANY_IN_BACKGROUND allow\|ignore` | **Schreiben**, nur auf Tippen |
| 8 | `cat /proc/stat /proc/meminfo` | Live-Anzeige CPU/RAM, alle 700 ms |

1–5 laufen über `IShizukuService.newProcess` mit UID 2000 (shell). 6 und 8 lesen
nur, 7 schreibt und liest anschließend zurück; die App hält keine Wake-Locks und
schreibt keine Dateien.

## Aufbau

`Audit.kt` enthält die reinen Parser (`analyze`, `parseDuration`, `parseBackgroundMode`,
`rankedBy`), `MainActivity.kt` die Shizuku-Anbindung, die Live-Anzeige und die
Compose-Oberfläche.

**Jobs.** `dumpsys jobscheduler` meldet zuerst `Registered <n> jobs:` und listet
sie in zwei Formen:

```
  JOB usagestats_prune:1000/0: e50bede @usagestats_prune@android/com.android.server.…
  JOB #1000/808: 6b9ef78 android/com.android.server.MountServiceIdler
```

Beide beginnen mit `  JOB `. Das Paket kommt aus der `Source: uid=… pkg=…`-Zeile,
die in jedem Block vierfach eingerückt folgt; fehlt sie, wird es aus dem
`@namespace@pkg/class`-Teil abgeleitet. Andere `Source:`-Zeilen im Dump (sechsfach
eingerückt, Abschnitt `Active Package stats`) werden bewusst nicht gelesen.

**Alarme.** `dumpsys alarm` listet im Abschnitt `Alarm Stats:` je UID/Paket einen
Kopf (`  u0a177:com.google.android.gms +911ms running, 25 wakeups:`) und darunter
je Alarmtyp eine Zeile `    +341ms 0 wakes 24 alarms, last …`. Gezählt wird die
Spalte **alarms** (gefeuerte Alarme), nicht `wakeups`. Kopfzeilen anderer
Abschnitte (`Removal history`, `Allow while idle history`) haben dieses Format
nicht und werden nicht erfasst.

**Dauern.** `dumpsys batterystats --charged` gibt pro UID eine Zeile aus, z. B.

```
  UID u0a307: 20.7 fg: 5.35 (52m 30s 445ms) bg: 0.0763 (36m 16s 681ms) fgs: 0.000931 (40s 883ms) cached: 0.153 (2h 23m 9s 350ms)
  UID 1000: 36.8 fg: 0 (65ms) bg: 36.8 cached: 0 (43ms)
```

Die Schlüssel stehen in wechselnder Reihenfolge und können fehlen; jede Kennzahl
wird einzeln per Regex gesucht. Fehlt die Klammer (nur mAh-Anteil, z. B.
`bg: 36.8`), gibt es keine Dauer und der Wert ist 0. `parseDuration` liest
`1h 29m 52s 161ms`, `27m 9s 202ms` und `136ms`; die Alternation prüft `ms` vor
`m`, sonst würde `161ms` zu 161 Minuten.

**UID → Paket.** Gemeinsame UIDs (z. B. `1000`) tragen mehrere Pakete. Gewählt
wird das Paket mit den meisten Jobs + Alarmen, sonst das alphabetisch erste.
Bleibt eine UID ohne Paket, erscheint sie als `uid <n>`.

**Standby-Bucket.** Jeder Job-Block trägt eine Zeile `    Standby bucket: ACTIVE`.
Ein Paket kann über seine Jobs mehrere Buckets tragen (gemessen: WhatsApp mit
`ACTIVE`, daneben `EXEMPTED`); angezeigt wird der häufigste, bei Gleichstand der
alphabetisch erste. Das kostet keinen zusätzlichen Shell-Aufruf, ist aber der
Bucket **zum Zeitpunkt der Job-Planung** — er kann von
`am get-standby-bucket <pkg>` abweichen, wenn Android den Bucket seither gedreht
hat. Pakete ohne Job haben keinen Bucket und zeigen `—`.

**Label.** Der Anzeigename kommt aus dem `PackageManager` (ein lokaler Aufruf je
Zeile, kein Shell-Aufruf) und steht als Hauptzeile der Liste; der Paketname
darunter in `Mono`.

## Layoutsystem

Die Oberfläche folgt dem verbindlichen Layoutsystem der NothingOS-App-Familie
(`~/.omp/agent/managed-skills/nothingos-app/SKILL.md`): Tokens (`Accent`, `Dim`,
`Panel`, `Line`, `Good`, `Warn`), Typografie (`Title` 28 sp, `Kicker` 9 sp,
`Micro` 9 sp, `Value` 22 sp, `Body` 12 sp, `Mono` 10 sp, alles Monospace) und die
Seitenhierarchie OSW → Live-Panel → Summary → Controls → List. Die Komponenten
`Osw`, `OutlineBox`, `SummaryPanel`, `LivePanel`, `MiniButton`, `Pill`, `Toggle`,
`SearchField`, `Histogram` und `Bar` stehen mit den Maßen der Vorlage in
`MainActivity.kt` (Abschnitt „Komponenten des Layoutsystems").

| Slot | Inhalt |
|---|---|
| OSW | `WAKEUP AUDITOR`, darunter der Kicker `SCHNAPPSCHUSS <zeit> · NICHT AUTOMATISCH`, rechts `MiniButton` **NEU LADEN** (`LÄDT…` während des Ladens); darunter eine `Micro`-Zeile `NUR LESEND · JOBS, ALARME, HINTERGRUNDZEIT` |
| Live-Panel | CPU- und RAM-Histogramm (46 dp, 48 Slots, 62 % Balkenbreite), rechts der Wert in `Value` |
| Summary | `JOBS`, `ALARME`, `DOZE-PAKETE` in einem `SummaryPanel` |
| Controls | `SearchField` mit Trefferzahl, vier Sortier-`Pill`, Kicker `PUNKTE = JOBS×3 + ALARME×2 + MINUTEN`, `Pill` **NUR MIT JOBS** |
| List | `LazyColumn`: Rang in `Mono`, Label in `Body`, Paket und `JOB`/`ALARM`/`BG` in `Mono`, Punkte rechts in `Value`, darunter der `Toggle` HINTERGRUND |
| Overlays | Karten für Shizuku-Hinweis, Laden und Fehler; die Detailansicht ist eine eigene Seite; die About-Sektion öffnet sich als Dialog über die Kicker-Zeile |

Die About-Sektion (Tipp auf `SCHNAPPSCHUSS … · NICHT AUTOMATISCH`) zeigt Name in
`Title`, `Version <BuildConfig.VERSION_NAME> · <Gerätemodell>`, den Leitspruch
`Function over form.` allein stehend in `Accent`, einen Satz Zweck und die
`Kernkommandos:` als `Mono`-Liste. Kein Impressum, keine Links.

Abweichungen von der Vorlage — jede erzwungen, keine Geschmacksfrage:

- **Detailansicht als eigene Seite statt Dialog.** Sie zeigt vier Rohwerte im
  `SummaryPanel` (JOBS, ALARME, MINUTEN, PUNKTE) und darunter die Zeitwerte in
  einer `OutlineBox`. Der Zurück-Knopf ist ein `MiniButton`, weil die Vorlage
  keinen eigenen Zurück-Slot kennt.
- **`SearchField` mit dem Mikrolabel `SUCHE` statt einer Lupe.** Die
  Monospace-Schrift des Geräts hat kein `⌕`; das Zeichen würde als leeres
  Kästchen erscheinen.
- **`Hint` nicht übernommen.** Der Zweizeiler erklärt den Schalter und bleibt
  fester Bestandteil der Liste; er ist nicht ausblendbar und braucht deshalb
  keinen `SharedPreferences`-Merker.
- **`Osw` hat einen vierten Parameter `onKickerClick`.** Die Vorlage verlangt
  einen antippbaren Kicker, die Signatur nennt aber nur drei Parameter; der
  Zusatz ist optional und ändert die drei Pflichtparameter nicht.
- **`AboutPanel` als Dialog, nicht als Seite.** Der Kicker ist in jedem Zustand
  sichtbar (auch im Lade- und Fehlerzustand), der Dialog funktioniert dort
  gleichermaßen; eine eigene Seite hätte einen weiteren Zweig in der
  Zustandsverzweigung gebraucht.

Kopfsummen nach dem Umbau, gemessen im selben `flock`-Block wie die Dumps davor
und danach (18:30, finale 1.4.0): JOBS **331**, ALARME **553**, DOZE-PAKETE
**34** — die App liest den Dump also genau so, wie die Shell ihn zählt:

| Kopfwert | Kommando | Wert vor / nach dem App-Start |
|---|---|---|
| JOBS | `adb shell dumpsys jobscheduler \| grep -c '^  JOB '` | 331 / 331 |
| ALARME | `adb shell dumpsys alarm \| grep -oE '[0-9]+ wakes [0-9]+ alarms' \| awk '{s+=$3} END{print s}'` | 553 / 554 |
| DOZE-PAKETE | `adb shell cmd deviceidle whitelist \| cut -d, -f2 \| sort -u \| wc -l` | 34 / 34 |

Die App zeigt 331/553/34, also den Stand des Dumps vor dem Start. Die 554 im
Dump danach sind ein Alarm, der zwischen App-Laden und Kontrollmessung feuerte —
derselbe Drift, den schon die alte Fassung hatte (1.3.0 um 17:51: 331 Jobs,
455 Alarme, 34 Doze-Pakete; 1.4.0 um 17:55: 330/467/34). JOBS und DOZE-PAKETE
waren in jeder Messung identisch, der Alarmzähler wächst zwischen zwei Starts
weiter.

## Sortierung, Suche, Filter

Vier Pillen unter dem Suchfeld: `PUNKTE` (Standard), `JOBS`, `ALARME`,
`MINUTEN`, jeweils absteigend und bei Gleichstand alphabetisch nach Paket.
`rankedBy` in `Audit.kt` ist die einzige Sortierstelle; umgeschaltet wird auf den
bereits gelesenen Dumps, ohne neu zu laden.

Das Suchfeld darüber wirkt live auf Paketname **und** Label
(`lowercase().contains`), das `×` leert es; rechts steht die Trefferzahl
(`23 TREFFER`), darunter die Zeile `SORTIERT NACH PUNKTE · 23 VON 152 APPS`.
Suche und `NUR MIT JOBS` wirken zusammen. Ohne Treffer erscheint
`KEIN TREFFER FÜR «…»` in `Warn`.

## HINTERGRUND-Schalter (AppOps)

`RUN_ANY_IN_BACKGROUND` steuert, ob eine App im Hintergrund laufen darf. Gelesen
wird `cmd appops get`, geschrieben `cmd appops set … ignore|allow`. Nach dem
Schreiben wird **neu gelesen** und nur die Antwort angezeigt; ein fehlgeschlagenes
Kommando kann so keinen falschen Zustand vortäuschen.

- Je Zeile ein `Toggle`: `HINTERGRUND AN` (Dim) / `HINTERGRUND AUS` (Warn) /
  `HINTERGRUND –` (noch nicht geprüft, als `Mono`) / `HINTERGRUND ?` (Antwort
  unlesbar, in `Warn`).
- Angeboten werden nur Drittanbieter-Apps: `uid % 100000 >= 10000`, kein
  `FLAG_SYSTEM`, nicht `android`, nicht `moe.shizuku.*`/`rikka.shizuku.*`, nicht
  diese App selbst.
- Die Abfrage läuft **einzeln nacheinander** im Hintergrund (ein `cmd appops get`
  je Paket) und veröffentlicht in Fünferschritten; bis dahin steht `–`. Der
  Fortschritt steht als `HINTERGRUND · PRÜFE 40 / 70` über der Liste.
- `AUS = APP DARF NICHT IM HINTERGRUND LAUFEN. / MESSENGER UND SYNC BRECHEN DANN AB.`
  steht dauerhaft als Zweizeiler über der Liste.

## Live-Anzeige (CPU/RAM)

Panel unter der Kopfzeile: zwei Zeilen mit je einem 46-dp-Histogramm (48 Slots,
Balken 62 % der Slotbreite, `Accent`/`Line`), rechts der Wert. Zusammen 114 dp
hoch (gemessen an den Rahmenlinien im Screenshot).

- Eine Probe alle 700 ms über `cat /proc/stat /proc/meminfo` (ein Prozess für
  beides). CPU-Last = `1 − Δidle/Δtotal` mit `idle = cpu[3] + cpu[4]`.
- RAM = `(MemTotal − MemAvailable) / MemTotal`, Anzeige `5,3/11,1 GiB` (deutsches
  Komma).
- Der Sampler lebt in einer `LaunchedEffect` mit `repeatOnLifecycle(RESUMED)`:
  pausiert im Hintergrund, endet mit dem Composable. Gelesen wird im
  IO-Dispatcher, geschrieben auf Main.
- Fehlt die Antwort, bleibt das Histogramm leer und rechts steht `KEINE DATEN`.

## Tests

`./gradlew testDebugUnitTest` — 17 Tests, alle grün:

| Klasse | Prüft |
|---|---|
| `DurationTest` (2) | `parseDuration`/`formatDuration` gegen `1h 29m 52s 161ms`, `27m 9s 202ms`, `136ms` |
| `DumpCheckTest` (1) | derselbe Parser über die Dumps in `raw/`, Jobs-/Alarm-/Doze-Zahl gegen die `grep`-Zählungen; ohne `raw/` übersprungen |
| `SortTest` (5) | alle vier Achsen absteigend, Gleichstand alphabetisch, 59 999 ms = 0 Minuten |
| `ShellResultTest` (3) | stdout wird genutzt, leere Ausgabe wirft die erste stderr-Zeile, ohne stderr eine Ersatzmeldung |
| `ProcAndOpsTest` (6) | AppOps-Modi (`RUN_ANY_IN_BACKGROUND:` und `Default mode:`), `/proc`-Probe inkl. GiB-Text und fehlendem Meminfo |

## Formel

```
Punkte = Jobs × 3 + Alarme × 2 + Minuten
Minuten = (bg + cached) / 60000
```

Ein Job wiegt 3, ein Alarm 2, eine Hintergrundminute 1. `fg` und `fgs` gehen
nicht in die Punkte ein, stehen aber in der Detailansicht.

## Gemessene Zahlen (Gerät, 2026-11-05, Android 17)

Aufgenommen nach dem Ende einer Fremd-Kompilierphase, also ohne CPU-Last durch
`dex2oat`. Snapshot der App um 16:54, die Dumps davor und danach im selben
`flock`-Block gezogen.

| Kopfwert | App | Beleg auf demselben Dump |
|---|---|---|
| JOBS | 330 | `dumpsys jobscheduler \| grep -c '^  JOB '` → 330 (vor **und** nach dem App-Start) |
| ALARME | 277 | `dumpsys alarm \| grep -E '^    \+[^ ]+ [0-9]+ wakes [0-9]+ alarms' \| awk '{s+=$4} END{print s}'` → 277 (vor und nach) |
| DOZE-PAKETE | 34 | `cmd deviceidle whitelist \| cut -d, -f2 \| sort -u \| wc -l` → 34 |
| Zeilen | 152 | Summe der `JOBS`-Spalte über alle Zeilen = 330, der `ALARME`-Spalte = 277 |

Der Alarmzähler läuft weiter (277 → 291 → 338 → 351 innerhalb einer Stunde,
je nach Gerätebetrieb); verglichen werden muss immer ein Dump aus demselben
Zeitfenster. JOBS lag über alle Messungen bei 328–331.

### Die vier Sortierachsen, Top 2 nachgerechnet

| Achse | Platz 1 | Platz 2 | Kommando |
|---|---|---|---|
| PUNKTE | `android` 443 | `gms` 259 | 27×3+181×2+0 bzw. 36×3+58×2+35 |
| JOBS | `com.google.android.gms` 36 | `…googlequicksearchbox` 29 | `grep '^    Source: uid=' jobs.txt \| sed -E 's/.*pkg=([^ ]+).*/\1/' \| sort \| uniq -c \| sort -rn` |
| ALARME | `android` 181 | `com.google.android.gms` 58 | `awk` über die `alarms`-Spalte je Paketkopf (siehe oben) |
| MINUTEN | `…connectivity.resources` 113 | `dev.imranr.obtainium` 113 | `UID u0a283:` bg 31m 1s 867ms + cached 1h 22m 32s 653ms = 113 min · `UID u0a315:` cached 1h 53m 30s 599ms + bg 7s 75ms = 113 min |

Gleichstand wird alphabetisch aufgelöst: bei MINUTEN stehen
`com.google.android.connectivity.resources`, `dev.imranr.obtainium`,
`ee.mtakso.client` in dieser Reihenfolge, bei JOBS
`com.google.android.contacts` vor `io.github.forkmaintainers.iceraven` (beide 15).

### Suche und Filter

| Eingabe | Treffer | Beleg |
|---|---|---|
| `google` | 36 | Trefferzahl der Zeilen; Paket- **und** Label-Treffer, deshalb nicht 1:1 mit einer `grep`-Zählung vergleichbar |
| `gms` | 1 | unter den 152 Zeilen trifft nur `com.google.android.gms`; die 18 weiteren Overlay-/Sidecar-Pakete mit `gms` im Namen haben keine Zeile (kein Job, kein Alarm, keine Batteriezeile) |
| `gms` + NUR MIT JOBS | 1 | gms hat 36 Jobs > 0, Schnittmenge bleibt 1 |
| `google` + NUR MIT JOBS | 23 | 22 Pakete mit `google` im Namen **und** Jobs (`grep -c` über die `Source:`-Pakete) plus `com.android.vending` über sein Label „Google Play Store" |
| `Play Store` | 1 | Label-Treffer: `com.android.vending` (12 Jobs, 3 min, 39 Punkte) — der Paketname enthält kein „play store" |
| `zzz` | 0 | Zustand `KEIN TREFFER FÜR «zzz»` |
| NUR MIT JOBS ohne Suche | 63 von 152 | `grep '^    Source: uid=' jobs.txt \| sed -E 's/.*pkg=([^ ]+).*/\1/' \| sort -u \| wc -l` → 63 |

### Detailansicht und Bucket

`ee.mtakso.client` (Bolt): `UID 10439`, 1 Job, 0 Alarme, `BG + CACHED` 2h 0m 14s 548ms,
`MINUTEN 120`, `PUNKTE 123` (1×3 + 0 + 120); Label „Bolt", `BUCKET WORKING_SET` —
`am get-standby-bucket ee.mtakso.client` → 20 (WORKING_SET).
`com.google.android.apps.messaging`: Label „Messages", `BUCKET ACTIVE`,
`am get-standby-bucket …` → 10 (ACTIVE). `android`: `BUCKET EXEMPTED`, Shell → 5.
WhatsApp hat 4 Jobs, alle `ACTIVE`; die Anzeige folgt der Mehrheit.

### HINTERGRUND-Schalter

Test an `io.github.forkmaintainers.iceraven` (Ausgangszustand `allow`):

| Schritt | Toggle | `cmd appops get io.github.forkmaintainers.iceraven RUN_ANY_IN_BACKGROUND` |
|---|---|---|
| vorher | `HINTERGRUND AN` | `RUN_ANY_IN_BACKGROUND: allow` |
| getippt | `HINTERGRUND AUS` | `RUN_ANY_IN_BACKGROUND: ignore` |
| erneut getippt | `HINTERGRUND AN` | `RUN_ANY_IN_BACKGROUND: allow` |

Erneut geprüft mit dem `Toggle` in 1.4.0 (18:12): Ausgang `allow`, erster Tipp
`ignore`, zweiter Tipp `allow` — dieselben Werte wie mit dem alten Chip, jeweils
per `cmd appops get` zurückgelesen.

Der Ausgangszustand ist wiederhergestellt. `android`, `com.google.android.gms`
und die System-Apps tragen keinen Schalter; die Warteschlange meldete `PRÜFE 0 / 70`,
also 70 schaltbare Pakete unter den 152 Zeilen.

### Live-Anzeige

| Anzeige | Wert | Gegenprobe |
|---|---|---|
| CPU | 30 % | `adb shell top -n1 -b \| head -5` → `800%cpu … 564%idle` ⇒ (800−564)/800 = 29,5 % |
| RAM | 5,3/11,1 GiB | `/proc/meminfo`: MemTotal 11 680 596 kB, MemAvailable 6 125 016 kB ⇒ 5 554 580 kB = 5,30 GiB von 11,14 GiB |

Zwei Screenshots im Abstand von 8 s zeigen unterschiedliche Histogramme
(fast leer → volle 48 Slots) und 45 % → 30 % Last.

## Bedienung

- **NEU LADEN** im Kopf liest die Dumps neu; der Kopf sagt sonst
  `SCHNAPPSCHUSS 17:11 · NICHT AUTOMATISCH`. Während des Ladens steht `LÄDT…`.
- **Pillen** `PUNKTE`/`JOBS`/`ALARME`/`MINUTEN` sortieren die bereits geladene
  Liste um.
- **Suchfeld** filtert live, `×` leert es, die Trefferzahl steht rechts im Feld.
- **NUR MIT JOBS** filtert auf Zeilen mit `jobs > 0` und wirkt mit der Suche zusammen.
- **HINTERGRUND** je Zeile schaltet `RUN_ANY_IN_BACKGROUND` um und zeigt danach
  den neu gelesenen Zustand.
- Tippen auf eine Zeile öffnet die Detailansicht mit Label, Bucket und allen
  Rohwerten (UID, Jobs, Alarme, fg, bg, fgs, cached, bg+cached, Minuten, Punkte)
  und der Rechnung im Klartext. Zurück über den Knopf oder die Systemgeste; die
  Listenposition bleibt dabei erhalten.
- Suchtext, Sortierung und Filter überleben die Rotation (`rememberSaveable`),
  die Listenposition ebenfalls (eigener `LazyListState` oberhalb der Verzweigung).
- Beim ersten Start fragt die App die Shizuku-Berechtigung selbst an.
  Ohne laufenden Shizuku-Dienst zeigt sie einen Hinweis statt einer leeren Liste.

## Was ungetestet blieb

- **Nur ein Gerät, ein Stand.** Android 17/NothingOS (`C5.0-261023-2146`). Andere
  Hersteller formatieren `dumpsys`-Ausgaben anders; die Parser sind auf die oben
  gezeigten Formen gebaut.
- **Die Zahlen driften zwischen zwei Starts.** Alarme wachsen weiter, Jobs werden
  angelegt und abgebrochen (330 → 331). Ein Nachmessen später ergibt andere
  Summen; verglichen werden muss immer ein Dump aus demselben Zeitfenster.
- **Fehlerzustand am Gerät nicht ausgelöst.** Die Fehlerkarte mit der ersten
  stderr-Zeile ist nur durch `ShellResultTest` belegt; am Gerät war kein Kommando
  zum Scheitern zu bringen, ohne Shizuku anzufassen.
- **`HINTERGRUND ?`** (unlesbare AppOps-Antwort) trat nicht auf; alle 70 Pakete
  lieferten ein bekanntes Modus-Wort. Der Fall ist im Unit-Test abgedeckt.
- **`HINTERGRUND –`** (noch nicht geprüft) ist nur kurz sichtbar: die Warteschlange
  schafft die 70 Pakete in wenigen Sekunden. Als Screenshot festgehalten ist der
  Fortschritt (`PRÜFE 0 / 70`, `15 / 70`, `60 / 70`), nicht der einzelne Strich.
  Zwischen dem Ende eines Ladevorgangs und dem Berechnen der schaltbaren Pakete
  (PackageManager, im Hintergrund) zeigt die Zeile für einen Moment gar keinen
  Schalter.
- **Der Bucket ist der Stand der Job-Planung**, nicht der Live-Wert von
  `am get-standby-bucket`; bei sechs Paketen im Dump lagen zwei verschiedene
  Buckets vor, dort entscheidet die Mehrheit.
- **Der Schalter ist eine Momentaufnahme wie der Rest**: Ändert das System den
  AppOps-Modus später selbst, zeigt die Zeile den zuletzt gelesenen Wert bis zum
  nächsten `NEU LADEN`.
- **Live-Anzeige pausiert im Hintergrund** (`repeatOnLifecycle`), aber nicht
  zusätzlich bei ausgeschaltetem Display — `RESUMED` deckt das üblicherweise mit ab,
  ein Test mit gesperrtem Gerät steht aus.
- **Foreground-Zeiten** (`fg`, `fgs`) werden angezeigt, gehen aber nicht in die
  Punkte ein.
- **Gemeinsame UIDs** (z. B. `1000`, das für `android` und viele Systemdienste
  steht) werden auf ein Paket abgebildet. Die Zeit dieser UID landet komplett bei
  diesem einen Paket, nicht anteilig.
- Kein Test auf Multi-User-Geräten (`user 10`); es wird nur der laufende Nutzer
  ausgewertet, wie `dumpsys` ihn liefert.
- Kein Test mit entzogener Shizuku-Berechtigung nach dem ersten Start (nur der
  Hinweiszustand vor der Erteilung ist gesehen).
