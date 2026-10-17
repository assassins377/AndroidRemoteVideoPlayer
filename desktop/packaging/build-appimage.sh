#!/bin/bash
# Builds a single-file AppImage from the Compose app image (desktop/build/compose/binaries/main/app).
# usage: build-appimage.sh <appimagetool> <version> <out-dir>
set -euo pipefail
TOOL=$1; VER=$2; OUT=$3
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
SRC=$ROOT/desktop/build/compose/binaries/main/app/remote-video-player
DIR=$(mktemp -d)/RemoteVideoPlayer.AppDir
mkdir -p "$DIR/usr"; cp -a "$SRC"/. "$DIR/usr/"
cp "$ROOT/desktop/src/main/resources/icon.png" "$DIR/remote-video-player.png"
cat > "$DIR/remote-video-player.desktop" <<D
[Desktop Entry]
Type=Application
Name=Remote Video Player
Exec=remote-video-player
Icon=remote-video-player
Categories=AudioVideo;Video;Player;
D
cat > "$DIR/AppRun" <<'R'
#!/bin/sh
HERE="$(dirname "$(readlink -f "$0")")"
exec "$HERE/usr/bin/remote-video-player" "$@"
R
chmod +x "$DIR/AppRun"
mkdir -p "$OUT"
ARCH=x86_64 "$TOOL" --appimage-extract-and-run --no-appstream "$DIR" "$OUT/RemoteVideoPlayer-$VER-x86_64.AppImage"
