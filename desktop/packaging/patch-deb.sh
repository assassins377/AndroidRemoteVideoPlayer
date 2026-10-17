#!/bin/bash
# Adds the libvlc runtime dependency to the jpackage .deb (jpackage cannot see it: vlcj loads libvlc via JNA).
set -euo pipefail
DEB=$1; W=$(mktemp -d)
dpkg-deb -R "$DEB" "$W"
grep -q 'libvlc5' "$W/DEBIAN/control" || sed -i -E 's/^(Depends: .*)$/\1, libvlc5 | vlc\nRecommends: vlc-plugin-base, libsecret-tools/' "$W/DEBIAN/control"
fakeroot dpkg-deb -b -Zxz "$W" "$DEB" >/dev/null
