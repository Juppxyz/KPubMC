# KPubMC-API für das Casino

Mit der API kann ein anderes Plugin, hier das Casino, Einsätze in Bargeld annehmen und Gewinne in Bargeld auszahlen. Gebucht wird über eine eigene **Casinokasse**, die nichts mit der Staatskasse zu tun hat.

| Datei | Wofür |
|---|---|
| `kpubmc-2.0.0-api.jar` | Nur zum Kompilieren (5 Interfaces/Klassen) |
| `kpubmc-2.0.0-api-sources.jar` | Quelltext mit Javadoc für die IDE |

Beide entstehen beim Bauen von KPubMC unter `target/api/`.

## Was das Casino darf und was nicht

Das Casino **darf**:

- Bargeld lesen: Ist ein Item ein gültiger Schein, was ist er wert, wie viel Bargeld trägt ein Spieler.
- Einen Einsatz nehmen: Scheine aus dem Inventar des Spielers gehen in die Casinokasse.
- Einen Gewinn auszahlen: Scheine aus der Casinokasse gehen an den Spieler, aber nur, wenn die Kasse ihn decken kann und der Spieler sein Tageslimit nicht überschreitet.
- Gebühren an den Staat abführen: Geld aus der Casinokasse geht direkt in die Staatskasse (`payToTreasury`).

Das Casino **kann nicht**:

- Geld auf Bankkonten buchen oder von dort abbuchen. Die API kennt keine Konten.
- Scheine drucken, die die Casinokasse nicht bezahlt hat. KPubMC druckt Scheine nur für sich selbst; ein Aufruf aus einem anderen Plugin, auch per Reflection, wird abgelehnt.
- Geld aus der Staatskasse nehmen.
- Kreditgeld verspielen. Geld, das an einen Kredit gebunden ist, kann man bei Basil gar nicht als Bargeld abheben.

Damit kann das Casino pro Saison höchstens sein Startkapital (Standard 100.000) plus das, was Spieler dort verloren haben, wieder ausgeben, und an einen einzelnen Spieler höchstens das Tageslimit (Standard 20.000 netto in 24 Stunden). Mehr Geld kann über das Casino nicht in die Wirtschaft kommen.

## Einbinden

`plugin.yml` des Casinos:

```yaml
depend: [KPubMC]
```

Gradle (Kotlin DSL):

```kotlin
dependencies {
    compileOnly("io.papermc.paper:paper-api:26.3.build.43-alpha")
    compileOnly(files("libs/kpubmc-2.0.0-api.jar"))
}
```

Maven:

```bash
mvn install:install-file -Dfile=kpubmc-2.0.0-api.jar -Dsources=kpubmc-2.0.0-api-sources.jar -DgroupId=xyz.jupp.minecraft -DartifactId=kpubmc-api -Dversion=2.0.0 -Dpackaging=jar
```

```xml
<dependency>
    <groupId>xyz.jupp.minecraft</groupId>
    <artifactId>kpubmc-api</artifactId>
    <version>2.0.0</version>
    <scope>provided</scope>
</dependency>
```

`payToTreasury`, `dailyPayoutLimit` und `payoutLeft` gibt es ab dem KPubMC-Stand vom 16.11.2026; dafür die neue API-Jar verwenden. `apiVersion()` bleibt 1, ältere Aufrufe laufen unverändert weiter.

**Wichtig:** Die API-Jar **nicht** ins Casino-Plugin einbauen (nicht shaden, nicht `implementation`). Zur Laufzeit kommen die Klassen aus KPubMC. Eine zweite Kopie im Casino-Plugin führt zu `ClassCastException`, oder `KPubEconomy.get()` findet den Dienst nicht.

## Grundregeln

1. **Nur auf dem Main-Thread aufrufen.** `takeBet`, `payWin` und `cashOf` werfen sonst eine `IllegalStateException`.
2. **Ergebnisse kommen als `CompletableFuture<CasinoResult>`.** Das Future wird immer auf dem Main-Thread erfüllt und nie mit einer Exception. In `thenAccept` darf man also direkt die Bukkit-API benutzen.
3. **Beträge sind Vielfache von 10**, von 10 bis 1.000.000. Die kleinsten Scheine sind 10er.
4. **Nie selbst Scheine bauen, kopieren oder aufbewahren.** Also nicht in Kisten, GUI-Slots, Dateien oder Datenbanken. Einsätze nimmt `takeBet`, Gewinne zahlt `payWin`. Ein aufbewahrter Schein kann beim Saisonreset wertlos werden.
5. **Bargeld nie als Ware oder Material annehmen**, z. B. als Preis in einem eigenen Shop. Prüfen mit `cash().looksLikeCash(item)`.
6. **Nur das Paket `xyz.jupp.minecraft.api` benutzen.** Keine anderen KPubMC-Klassen aufrufen und keine `kpubmc:`-Markierungen an Items schreiben. Technisch ginge das, weil beide Plugins im selben Server laufen. Damit würde das Casino aber die Sicherheitsregeln umgehen, und es kann mit jedem KPubMC-Update kaputtgehen.
7. **Spielnamen** (`game`) bestehen nur aus Buchstaben, Ziffern, Leerzeichen und `_ . : -`, höchstens 32 Zeichen. Andere Zeichen werden entfernt.

## Bargeld

- Scheine zu **10, 100 und 1.000** Schilling. Ein Schein ist ein Papier mit unsichtbarer Markierung (Wert und Saison). Der Name ist egal, umbenanntes Papier ist 0 wert.
- **Jeder gültige Schein trägt den Saison-Stempel, auch in Saison 0.** Ein Papier mit `kpubmc:cash`, aber ohne `kpubmc:cash_season` ist wertlos. Selbst gebaute Scheine (z. B. der alte Legacy-Modus) gelten deshalb auf einem Server mit KPubMC nie.
- `cash().valueOf(item)` gibt den Wert **eines** Scheins des Stapels zurück, oder 0, wenn es kein gültiger Schein dieser Saison ist.
- `cash().cashOf(player)` liefert das gesamte gültige Bargeld in den 36 Lagerplätzen (Hotbar und Inventar). Offhand, Rüstung und Cursor zählen nicht.
- `cash().isOldNote(item)` erkennt einen Schein aus einer alten Saison, z. B. für die Meldung „Deine Scheine sind aus der letzten Saison“.
- `takeBet` nimmt alle Scheine und gibt das Wechselgeld sofort zurück. Wer einen 1.000er hat und 100 setzt, bekommt 900 in Scheinen zurück.
- `payWin` legt die Scheine ins Inventar. Was nicht passt, fällt dem Spieler vor die Füße.

## Casinokasse

```java
CasinoBank casino = KPubEconomy.get().casino();
casino.balance();       // aktueller Stand
casino.startCapital();  // Startkapital der Saison
casino.isOpen();        // false, wenn ein Admin das Casino per Notschalter gestoppt hat
casino.dailyPayoutLimit();  // höchstens so viel netto pro Spieler in 24 Stunden (0 = kein Limit)
casino.payoutLeft(player);  // Future: wie viel der Spieler gerade noch auszahlen lassen kann
```

**Tageslimit:** Ein Spieler kann in 24 Stunden höchstens `dailyPayoutLimit()` netto aus der Casinokasse bekommen, also Gewinne minus Einsätze. Ein Gewinn darüber wird wie eine leere Kasse abgelehnt (`CASINO_EMPTY`, nichts gezahlt). Den Gewinn behalten (z. B. als Jetons) und später auszahlen; `payoutLeft(player)` sagt, wie viel gerade geht. Das Limit gilt auch für Admin-Auszahlungen.

| Status | Bei `takeBet` | Bei `payWin` |
|---|---|---|
| `OK` | Einsatz ist in der Kasse | Gewinn wurde übergeben |
| `INVALID_AMOUNT` | Betrag ungültig, nichts passiert | Betrag ungültig, nichts passiert |
| `NOT_ENOUGH_CASH` | Spieler hat zu wenig Bargeld, nichts genommen | – |
| `INVENTORY_FULL` | Wechselgeld passt nicht ins Inventar, nichts genommen | – |
| `CASINO_EMPTY` | – | Kasse reicht nicht oder Tageslimit des Spielers erreicht, nichts gezahlt: **später erneut auszahlen** |
| `CLOSED` | Admin hat gestoppt, nichts passiert | Admin hat gestoppt, nichts gezahlt: **später erneut auszahlen** |
| `PLAYER_GONE` | Spieler nicht online, nichts genommen | Spieler ist gerade gegangen, Gewinn ist zurück in der Kasse: **später erneut auszahlen** |
| `FAILED` | Datenbankfehler, Scheine wurden zurückgegeben | Datenbankfehler, nichts gezahlt |

Bargeld landet nie auf einem Bankkonto, auch nicht im Fehlerfall. Geht der Spieler genau in dem Moment, in dem eine Einsatz-Buchung fehlschlägt, sind die Scheine verloren und stehen im Server-Log. So hält es auch Basil beim Einzahlen.

Das Casino muss sich um diese Fälle selbst kümmern:

- **Kasse prüfen, bevor ein Einsatz angenommen wird.** Ist der höchstmögliche Gewinn größer als `balance()`, das Spiel ablehnen. Sonst kann die Kasse am Ende `CASINO_EMPTY` melden, obwohl der Einsatz schon genommen ist.
- **`CASINO_EMPTY` nach genommenem Einsatz:** Mindestens den Einsatz zurückzahlen (`payWin(player, einsatz, "refund")`), und bis die Kasse wieder voll ist, keine neuen Spiele annehmen.
- **`PLAYER_GONE` oder `CLOSED` bei `payWin`:** Den offenen Gewinn speichern und später erneut mit `payWin` auszahlen, beim nächsten Join oder sobald `isOpen()` wieder `true` ist.
- **Serverstopp:** Offene Spiele dürfen im `onDisable` des Casinos noch erstattet werden (`payWin`). Beim Stoppen läuft alles sofort, das Future ist schon fertig, wenn der Aufruf zurückkommt.

## Gebühren für den Staat

Was das Casino für den Staat einnimmt (z. B. Gebühren am Geldwechsler), geht **direkt in die Staatskasse**, nie als Bargeld an einen Spieler oder Admin:

```java
casino.payToTreasury(betrag, "wechselgebuehr").thenAccept(result -> {
    switch (result.status()) {
        case OK -> topf -= betrag;                    // ist in der Staatskasse
        case CASINO_EMPTY, CLOSED -> { }              // im Topf lassen, später nochmal
        default -> getLogger().warning("Gebühren nicht abgeführt: " + result.status());
    }
});
```

- Abgeführt werden kann nur, was die Casinokasse **über ihrem Startkapital** hat, also Geld, das Spieler im Casino gelassen haben. Das Startkapital selbst darf nicht zu Staatseinnahmen werden.
- Am besten automatisch, z. B. einmal pro Stunde den ganzen Topf. Ein Knopf zum Abholen als Bargeld wird nicht gebraucht.
- In der Staatskasse erscheint das unter der Quelle **Casino**.

## Beispiel: Spielautomat

```java
public final class CasinoPlugin extends JavaPlugin implements Listener {

    private KPubEconomy economy;

    @Override
    public void onEnable() {
        economy = KPubEconomy.get();
        if (economy.apiVersion() != KPubEconomy.API_VERSION) {
            getLogger().severe("KPubMC-API passt nicht (Server " + economy.apiVersion() + ", Plugin " + KPubEconomy.API_VERSION + ")");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        // neue Saison: eigene Daten (Jackpot, Statistik, offene Gewinne) zurücksetzen
        if (getConfig().getInt("season", -1) != economy.season()) {
            resetOwnData();
            getConfig().set("season", economy.season());
            saveConfig();
        }
        getServer().getPluginManager().registerEvents(this, this);
    }

    void spin(Player player, long bet) {
        CasinoBank casino = economy.casino();
        long maxWin = bet * 10;
        if (!casino.isOpen()) {
            player.sendMessage("§cDas Casino hat gerade geschlossen.");
            return;
        }
        if (casino.balance() < maxWin) {
            player.sendMessage("§cDie Casinokasse kann diesen Einsatz gerade nicht decken.");
            return;
        }
        casino.takeBet(player, bet, "slots").thenAccept(taken -> {
            switch (taken.status()) {
                case OK -> {
                    long win = roll(bet); // 0 .. maxWin
                    if (win > 0) pay(player, win);
                }
                case NOT_ENOUGH_CASH -> player.sendMessage("§cDu hast nicht genug Bargeld dabei.");
                case INVENTORY_FULL -> player.sendMessage("§cMach erst Platz im Inventar für dein Wechselgeld.");
                case CLOSED -> player.sendMessage("§cDas Casino hat gerade geschlossen.");
                default -> player.sendMessage("§cDas hat nicht geklappt, versuch es gleich nochmal.");
            }
        });
    }

    void pay(Player player, long win) {
        economy.casino().payWin(player, win, "slots").thenAccept(paid -> {
            switch (paid.status()) {
                case OK -> player.sendMessage("§aGewonnen: " + win + " " + economy.currencyName());
                case PLAYER_GONE, CLOSED -> rememberPendingWin(player.getUniqueId(), win); // später erneut zahlen
                default -> getLogger().warning("Gewinn " + win + " für " + player.getName() + " nicht gezahlt: " + paid.status());
            }
        });
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        long pending = takePendingWin(event.getPlayer().getUniqueId());
        if (pending > 0) pay(event.getPlayer(), pending);
    }
}
```

## Saisonreset

Ein Admin kann mit `/saisonreset` und einem Neustart eine neue Saison starten. Dabei passiert Folgendes:

- Alle Konten, die Staatskasse und die **Casinokasse** werden geleert. Die Casinokasse startet danach wieder mit ihrem Startkapital.
- **Scheine der alten Saison sind wertlos.** `valueOf` gibt 0 zurück, `takeBet` nimmt sie nicht an.
- `economy.season()` zählt um eins hoch. Der Reset läuft beim Start von KPubMC, also bevor das Casino startet. Im `onEnable` des Casinos ist die neue Saison schon gesetzt.

Fürs Casino heißt das: die Saison zusammen mit den eigenen Daten speichern (siehe Beispiel) und beim Wechsel Jackpots, Statistiken und offene Gewinne zurücksetzen. Offene Gewinne aus der alten Saison nicht mehr auszahlen.

## Für Admins

- Startkapital in der `config.json` von KPubMC: `"casinoStartCapital": 100000`. Es gilt ab der nächsten Saison, eine laufende Kasse wird nicht verändert.
- Tageslimit pro Spieler in der `config.json`: `"casinoDailyPayoutLimit": 20000` (0 = kein Limit).
- `/wirtschaft` zeigt den Stand der Casinokasse, wie viel das Casino in 24 Stunden netto an Spieler ausgezahlt hat und wer am meisten bekommen hat, und hat einen Notschalter **Casino**. Solange er an ist, sind Einsätze, Gewinne und Zahlungen an den Staat geschlossen.
- Jede Buchung steht in der Tabelle `casino_ledger` mit Spieler, Spiel, Betrag und Zeit.
