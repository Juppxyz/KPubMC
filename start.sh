#!/bin/bash

set -e  # Script bei Fehler beenden
set -o pipefail

DIR="/mnt/HC_Volume_101895195/KlotzscherPub"
if [ ! -d "$DIR" ]; then
  echo "[ERROR] Arbeitsverzeichnis $DIR existiert nicht."
  exit 1
fi
cd "$DIR"


DEVICE="/mnt/HC_Volume_101895195"
MOUNT_SRC="/dev/sdb"
SERVER_JAR="paper.jar"

log() {
  echo "[INFO] $1"
}

error() {
  echo "[ERROR] $1" >&2
}

#mount_volume() {
#  if [ ! -d "$DEVICE" ]; then
#    log "Mount-Point $DEVICE existiert nicht, erstelle ihn.."
#    mkdir -p "$DEVICE"
#  fi
#
#  if mountpoint -q "$DEVICE"; then
#    log "Volume ist bereits gemountet."
#  else
#    log "Volume ist nicht gemountet. Versuche zu mounten.."
#    mount "$MOUNT_SRC" "$DEVICE" || error "Mount fehlgeschlagen!"
#  fi
#}
#unmount_volume() {
#  log "Unmount des Volumes in 5 Sekunden.."
#  sleep 10
#  if mountpoint -q "$DEVICE"; then
#    umount "$DEVICE" && log "Volume erfolgreich ausgehängt." || error "Unmount fehlgeschlagen!"
#  else
#    log "Volume ist bereits ausgehängt."
#  fi
#}

start_minecraft() {
  log "Starte Minecraft-Server.."
  java -Xmx11G \
    -XX:+UseG1GC -XX:+ParallelRefProcEnabled -XX:MaxGCPauseMillis=200 \
    -XX:+UnlockExperimentalVMOptions -XX:+DisableExplicitGC -XX:+AlwaysPreTouch \
    -XX:G1NewSizePercent=30 -XX:G1MaxNewSizePercent=40 -XX:G1HeapRegionSize=8M \
    -XX:G1ReservePercent=20 -XX:G1HeapWastePercent=5 -XX:G1MixedGCCountTarget=4 \
    -XX:InitiatingHeapOccupancyPercent=15 -XX:G1MixedGCLiveThresholdPercent=90 \
    -XX:G1RSetUpdatingPauseTimePercent=5 -XX:SurvivorRatio=32 \
    -XX:+PerfDisableSharedMem -XX:MaxTenuringThreshold=1 \
    -Dusing.aikars.flags=https://mcflags.emc.gs/ -Daikars.new.flags=true \
    -jar "$SERVER_JAR" --nogui
}

update_system() {
  log "Führe Systemupdate durch.."
  apt update -y && apt upgrade -y
  apt autoremove -y && apt autoclean -y
}

# Trap für Ctrl+C oder Script-Abbruch
trap cleanup INT TERM

cleanup() {
  log "Minecraft-Server wurde beendet. Aufräumen.."
  unmount_volume
  exit 0
}

### MAIN
#update_system
#mount_volume
start_minecraft
#unmount_volume
