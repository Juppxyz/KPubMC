#!/usr/bin/env bash
# Einmalige Host-Haertung fuer Debian 13 (Hetzner installimage), als root ausfuehren:
#   ADMIN_USER=jupp SSH_PORT=22 bash setup.sh
#
# WICHTIG: Die aktuelle SSH-Sitzung offen lassen und den neuen Login
# (ssh -p $SSH_PORT $ADMIN_USER@server) in einem ZWEITEN Terminal testen.
set -euo pipefail

ADMIN_USER="${ADMIN_USER:?ADMIN_USER setzen, z. B. ADMIN_USER=jupp}"
SSH_PORT="${SSH_PORT:-22}"
APP_DIR=/srv/kpubmc
HERE="$(cd "$(dirname "$0")" && pwd)"

[[ $EUID -eq 0 ]] || { echo "Als root ausfuehren"; exit 1; }
[[ -s /root/.ssh/authorized_keys ]] || { echo "/root/.ssh/authorized_keys fehlt/leer - Abbruch, sonst sperrst du dich aus"; exit 1; }

echo "== Pakete"
export DEBIAN_FRONTEND=noninteractive
apt-get update
apt-get -y full-upgrade
apt-get -y install sudo ufw fail2ban python3-systemd unattended-upgrades apt-listchanges \
  ca-certificates curl gnupg rsync restic htop

echo "== Admin-Benutzer $ADMIN_USER"
if ! id "$ADMIN_USER" &>/dev/null; then
  adduser --disabled-password --gecos "" "$ADMIN_USER"
fi
usermod -aG sudo "$ADMIN_USER"
install -d -m 700 -o "$ADMIN_USER" -g "$ADMIN_USER" "/home/$ADMIN_USER/.ssh"
install -m 600 -o "$ADMIN_USER" -g "$ADMIN_USER" /root/.ssh/authorized_keys "/home/$ADMIN_USER/.ssh/authorized_keys"
if ! passwd -S "$ADMIN_USER" | grep -q ' P '; then
  echo "Passwort fuer sudo setzen (nur lokal fuer sudo, SSH bleibt key-only):"
  passwd "$ADMIN_USER"
fi

echo "== Docker (offizielles Repo)"
if ! command -v docker &>/dev/null; then
  install -m 0755 -d /etc/apt/keyrings
  curl -fsSL https://download.docker.com/linux/debian/gpg -o /etc/apt/keyrings/docker.asc
  chmod a+r /etc/apt/keyrings/docker.asc
  . /etc/os-release
  echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/debian $VERSION_CODENAME stable" \
    > /etc/apt/sources.list.d/docker.list
  apt-get update
  apt-get -y install docker-ce docker-ce-cli containerd.io docker-compose-plugin
fi
install -m 644 "$HERE/daemon.json" /etc/docker/daemon.json
systemctl restart docker
# Bewusst KEIN "usermod -aG docker": die docker-Gruppe ist root-aequivalent. Nutze sudo docker.

echo "== Firewall (Host). Hinweis: Docker-Ports umgehen ufw - die Robot-Firewall ist die Aussengrenze."
ufw --force reset
ufw default deny incoming
ufw default allow outgoing
ufw allow "$SSH_PORT/tcp" comment ssh
ufw allow 25565/tcp comment minecraft
ufw --force enable

echo "== fail2ban"
cat > /etc/fail2ban/jail.local <<EOF
[DEFAULT]
backend = systemd
bantime = 1h
bantime.increment = true
findtime = 10m
maxretry = 5

[sshd]
enabled = true
port = $SSH_PORT
EOF
systemctl enable --now fail2ban
systemctl restart fail2ban

echo "== Automatische Sicherheitsupdates (ohne Auto-Reboot, der wuerde den MC-Server killen)"
cat > /etc/apt/apt.conf.d/20auto-upgrades <<'EOF'
APT::Periodic::Update-Package-Lists "1";
APT::Periodic::Unattended-Upgrade "1";
APT::Periodic::AutocleanInterval "7";
EOF
systemctl enable --now unattended-upgrades

echo "== Kernel-Haertung"
cat > /etc/sysctl.d/90-hardening.conf <<'EOF'
kernel.dmesg_restrict = 1
kernel.kptr_restrict = 2
kernel.unprivileged_bpf_disabled = 1
net.ipv4.conf.all.rp_filter = 1
net.ipv4.conf.default.rp_filter = 1
net.ipv4.conf.all.accept_redirects = 0
net.ipv4.conf.all.send_redirects = 0
net.ipv6.conf.all.accept_redirects = 0
net.ipv4.tcp_syncookies = 1
EOF
sysctl --system >/dev/null

echo "== App-Verzeichnis $APP_DIR"
install -d -m 750 "$APP_DIR"
install -d "$APP_DIR/plugins" "$APP_DIR/backups"
install -d -o 1000 -g 1000 "$APP_DIR/data/mc" "$APP_DIR/backups/mc"
install -d "$APP_DIR/data/postgres"
install -d -o 999 -g 999 "$APP_DIR/backups/db"
cp -n "$HERE/../compose.yaml" "$APP_DIR/" || true
cp -n "$HERE/../.env.example" "$APP_DIR/.env" || true
chmod 600 "$APP_DIR/.env"

echo "== SSH haerten (Port $SSH_PORT, nur Key, kein root)"
sed -e "s/__SSH_PORT__/$SSH_PORT/" -e "s/__ADMIN_USER__/$ADMIN_USER/" \
  "$HERE/sshd-hardening.conf" > /etc/ssh/sshd_config.d/10-hardening.conf
sshd -t
systemctl reload ssh

echo
echo "Fertig. JETZT in einem zweiten Terminal testen:  ssh -p $SSH_PORT $ADMIN_USER@<server-ip>  und  sudo -v"
echo "Erst wenn das klappt, diese root-Sitzung schliessen."
