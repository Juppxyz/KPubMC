# KPubMC auf dem Hetzner EX-44

```
Spieler ──TCP 25565──► [Hetzner Robot-Firewall] ─► Host (ufw, fail2ban) ─► mc (Paper)
Browser ──HTTPS──► Cloudflare ══Tunnel (ausgehend)══► cloudflared ─► mc:8100 (BlueMap)
                                                         mc ─(internes Netz)─► db (Postgres)
```

Offen nach außen: **nur SSH und 25565/tcp**. Für BlueMap gibt es keinen offenen Port; der Tunnel baut die Verbindung von innen nach außen auf. Postgres hängt in einem Docker-Netz ohne Internet und ohne veröffentlichten Port.

> Cloudflare kann Minecraft Java im Free-Plan nicht proxien. `mc.<domain>` ist deshalb ein **DNS-only**-Eintrag (graue Wolke), die Server-IP ist also öffentlich. Hetzner filtert DDoS-Angriffe im Netz automatisch. Wenn die IP versteckt werden soll, geht das nur mit einem TCP-Proxy (Cloudflare Spectrum, kostenpflichtig, oder z. B. TCPShield).

---

## 1. Betriebssystem installieren

1. Robot → Server → **Rescue** aktivieren (Linux 64 bit, **deinen SSH-Key auswählen**), dann Reset → Hardware-Reset.
2. `ssh root@<ip>` und danach `installimage`
   - **Debian 13** (trixie)
   - `SWRAIDLEVEL 1` (die zwei NVMe spiegeln)
   - Hostname, z. B. `kpubmc`
   - Partitionen: `/boot` 1G, `/` den Rest (ext4). Swap 8G reicht.
3. `reboot`, dann `ssh root@<ip>`. Wegen der Rescue-Keys funktioniert der Login nur per Key.

## 2. Robot-Firewall (die äußere Grenze)

Robot → Server → **Firewall**. Diese Firewall ist **zustandslos** und filtert, bevor ein Paket den Server erreicht. Sie ist wichtig, weil **Docker veröffentlichte Ports an ufw vorbei öffnet**.

Eingehend, in dieser Reihenfolge (Rest = verwerfen), „IPv6 filtern“ einschalten:

| # | Name | Protokoll | Quell-IP | Quell-Port | Ziel-Port | TCP-Flags | Aktion |
|---|------|-----------|----------|------------|-----------|-----------|--------|
| 1 | icmp | icmp | | | | | accept |
| 2 | ssh | tcp | *deine IP/32, falls statisch* | | 22 | | accept |
| 3 | minecraft | tcp | | | 25565 | | accept |
| 4 | tcp established | tcp | | | 32768-65535 | ack | accept |
| 5 | dns | udp | | 53 | 32768-65535 | | accept |
| 6 | ntp | udp | | 123 | 32768-65535 | | accept |

Regel 4 lässt die Antworten auf ausgehende Verbindungen durch (apt, Docker-Pulls, Mojang-Auth, Cloudflare-Tunnel). Der Tunnel läuft absichtlich über `--protocol http2` (TCP), damit keine UDP-Regel für QUIC nötig ist.

Wenn du dich aussperrst: Robot-Firewall ausschalten oder Rescue-System booten. Beides geht immer.

## 3. Host härten

Vom PC aus:

```bash
scp -r deploy root@<ip>:/root/
```

Auf dem Server:

```bash
ADMIN_USER=jupp SSH_PORT=22 bash /root/deploy/host/setup.sh
```

Das Skript erledigt:
- einen Admin-Benutzer mit sudo und deinem Key
- SSH nur mit Key, kein root-Login, `AllowUsers`
- Docker aus dem offiziellen Repo mit Log-Rotation und `no-new-privileges`
- ufw, fail2ban (sshd) und automatische Sicherheitsupdates
- Sysctl-Härtung
- `/srv/kpubmc` mit den richtigen Besitzern

**Die root-Sitzung offen lassen**, dann in einem zweiten Terminal `ssh jupp@<ip>` und `sudo -v` testen. Erst danach die root-Sitzung schließen.

Hinweise:
- Ein anderer SSH-Port (z. B. `SSH_PORT=2222`) reduziert nur Log-Rauschen. Wenn du ihn änderst, auch Robot-Regel 2 anpassen, **bevor** das Skript läuft.
- Nach Kernel-Updates startet der Server nicht automatisch neu, weil das den MC-Server abwürgt. Ob ein Neustart nötig ist, zeigt `ls /var/run/reboot-required`. Den Neustart dann selbst ansetzen und vorher im Spiel ankündigen.

## 4. Cloudflare

1. Domain in Cloudflare, DNS:
   - `mc` → A → `<server-ipv4>` → **DNS only** (grau)
   - optional `AAAA` für IPv6, ebenfalls grau
   - optional SRV `_minecraft._tcp.play` → `mc.<domain>` Port 25565
2. Zero Trust → Networks → **Tunnels** → Create → *Cloudflared* → Name `kpubmc`. Den **Token** kopieren (nur den Token, nicht den ganzen `docker run`-Befehl).
3. Im Tunnel → **Public Hostname**: `map.<domain>` → Service `HTTP` → `mc:8100`. Den orangen CNAME legt Cloudflare selbst an.
4. SSL/TLS: *Always Use HTTPS* an, HSTS an, Minimum TLS 1.2.
5. Optional: Security → WAF → Rate-Limit-Regel für `map.<domain>`. Wenn die Karte nur für Spieler sichtbar sein soll: Zero Trust → Access → Application für `map.<domain>` mit E-Mail-OTP.

## 5. Stack starten

```bash
sudo -i
cd /srv/kpubmc
openssl rand -hex 32   # -> POSTGRES_PASSWORD
openssl rand -hex 32   # -> RCON_PASSWORD
nano .env              # Passwörter und TUNNEL_TOKEN eintragen
```

Das Plugin vom PC hochladen:

```bash
scp target/kpubmc-*.jar jupp@<ip>:/tmp/
```

Auf dem Server:

```bash
sudo mv /tmp/kpubmc-*.jar /srv/kpubmc/plugins/
cd /srv/kpubmc && sudo docker compose up -d && sudo docker compose logs -f mc
```

Weitere Plugins kommen ebenfalls nach `plugins/`. Beim Start kopiert der Container sie nach `data/mc/plugins`.

Eine bestehende Welt/Config übernehmen: vor dem ersten Start per `rsync` nach `/srv/kpubmc/data/mc/` kopieren und danach `chown -R 1000:1000 data/mc` ausführen. `plugins/kpub/config.json` mit `aiReview` und API-Key dorthin legen. `databaseUrl` darin wird von `KPUB_DATABASE_URL` überschrieben.

Die alte DB übernehmen:

```bash
pg_dump -Fc kpubmc > kpubmc.dump    # lokal
```

Dann `scp` auf den Server und dort:

```bash
sudo docker compose exec -T db pg_restore -U kpubmc -d kpubmc < kpubmc.dump
```

## 6. BlueMap

Nach dem ersten Start in `data/mc/plugins/BlueMap/core.conf` `accept-download: true` setzen, dann `/bluemap reload`.

**Für den PvP-/Kriegsmodus wichtig:** BlueMap zeigt standardmäßig die Live-Positionen aller Spieler an. Damit wäre die Karte ein Spionagewerkzeug. In `plugins/BlueMap/plugin.conf`:

```
live-player-markers: false
```

Alternativ nur bestimmte Spieler verstecken (`hide-invisible`, `hide-vanished`, `hidden-game-modes`). Das Rendern der ersten Karte dauert einige Zeit. Die Kacheln sind absichtlich nicht im Backup, weil sie sich neu rendern lassen.

## 7. Backups

- `mc-backup`: alle 6 h ein tar der Welt nach `backups/mc`, 7 Tage lang (per RCON `save-off`/`save-on`, also konsistent)
- `db-backup`: alle 6 h ein pg_dump nach `backups/db`, 7 Tage, 4 Wochen und 3 Monate aufbewahrt

**Das liegt auf derselben Maschine und ist deshalb noch kein echtes Backup.** Eine Hetzner **Storage Box** (BX11 reicht) als Offsite-Ziel mit restic:

```bash
sudo ssh-keygen -t ed25519 -f /root/.ssh/storagebox -N ""
# Public Key in der Storage Box hinterlegen (Robot -> Storage Box -> SSH-Support an)
sudo restic -r sftp:uXXXXX@uXXXXX.your-storagebox.de:23/kpubmc init   # Passwort sicher aufheben!
```

Cronjob (`sudo crontab -e`):

```
30 4 * * * RESTIC_PASSWORD_FILE=/root/.restic-pw restic -r sftp:uXXXXX@uXXXXX.your-storagebox.de:23/kpubmc -o sftp.args="-i /root/.ssh/storagebox -p 23" backup /srv/kpubmc/backups /srv/kpubmc/.env && RESTIC_PASSWORD_FILE=/root/.restic-pw restic -r sftp:uXXXXX@uXXXXX.your-storagebox.de:23/kpubmc -o sftp.args="-i /root/.ssh/storagebox -p 23" forget --keep-daily 14 --keep-weekly 8 --prune
```

Einmal im Monat eine Wiederherstellung testen.

## 8. Prüfen

Auf dem Server:

```bash
sudo ss -tlnp                 # nur sshd und docker-proxy/25565
sudo docker compose ps        # alles healthy/running
sudo fail2ban-client status sshd
```

Von außen (PC):

```bash
nmap -Pn -p- <server-ip>
```

Erwartet ist nur 22 und 25565 offen, also kein 5432, 8100 oder 25575 (RCON). Außerdem `https://map.<domain>` im Browser öffnen.

## 9. Betrieb

| Was | Befehl |
|-----|--------|
| Konsole | `sudo docker compose exec mc rcon-cli` |
| Befehl senden | `sudo docker compose exec mc mc-send-to-console say Hallo` |
| Logs | `sudo docker compose logs -f --tail 200 mc` |
| Plugin-Update | Jar in `plugins/` ersetzen, dann `sudo docker compose restart mc` |
| Images aktualisieren | `sudo docker compose pull && sudo docker compose up -d` |
| Stoppen | `sudo docker compose down` (speichert die Welt sauber, 60 s Countdown) |

Weitere Sicherheitsregeln:
- In `server.properties` bleibt `online-mode=true`.
- OPs sparsam vergeben und `ops.json` regelmäßig prüfen.
- Die `.env` nie committen (steht in `.gitignore`).
- RCON ist nur im Docker-Netz erreichbar und wird **nicht** veröffentlicht.
- Den OpenAI-Key in der `config.json` mit einem Ausgabenlimit im OpenAI-Dashboard absichern.
