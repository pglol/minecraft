#!/bin/sh
# Usage: ./create-server.sh <modpack mods folder> [world folder] [ram]
# Builds a ready-to-run Attack on Titan RPG server in ./AoT-Server
DIR="$(cd "$(dirname "$0")" && pwd)"
MODS="$1"; WORLD="${2:-$DIR/AttackOnTitan}"; RAM="${3:-6G}"
[ -z "$MODS" ] && { echo "Usage: $0 <mods folder> [world folder] [ram]"; exit 1; }
printf "Do you accept the Minecraft EULA (https://aka.ms/MinecraftEULA)? (y/n) "; read A
F=""; [ "$A" = "y" ] && F="--accept-eula"
java -jar "$DIR/aot-world.jar" server "$DIR/AoT-Server" --mods "$MODS" --world "$WORLD" --ram "$RAM" $F
