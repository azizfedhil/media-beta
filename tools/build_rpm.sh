#!/usr/bin/env bash
# Builds the Linux RPM with rpmbuild from the app image Gradle made (createDistributable).
# We write the spec ourselves instead of using jpackage's, because jpackage's RPM installed without a menu entry and
# can't declare the libmpv requirement. This one installs the app to /opt/pear, a launcher link in /usr/bin, a
# .desktop file in /usr/share/applications and the icon in the hicolor theme.
# Usage: tools/build_rpm.sh <app-image-dir> <numeric-version> <icon.png> <out-dir>
set -euo pipefail
image="$(cd "$1" && pwd)"; version="$2"; icon="$(cd "$(dirname "$3")" && pwd)/$(basename "$3")"; out="$4"
mkdir -p "$out"; out="$(cd "$out" && pwd)"
test -x "$image/bin/Pear" || { echo "no launcher at $image/bin/Pear" >&2; exit 1; }

top="$(mktemp -d)"; root="$top/stage"
mkdir -p "$root/opt" "$root/usr/bin" "$root/usr/share/applications" "$root/usr/share/icons/hicolor/512x512/apps" "$top"/{BUILD,RPMS,SPECS}
cp -a "$image" "$root/opt/pear"
ln -s /opt/pear/bin/Pear "$root/usr/bin/pear"
cp "$icon" "$root/usr/share/icons/hicolor/512x512/apps/pear.png"
cat > "$root/usr/share/applications/pear.desktop" <<'D'
[Desktop Entry]
Type=Application
Name=Pear
Comment=Media hub
Exec=/opt/pear/bin/Pear
Icon=pear
Terminal=false
Categories=AudioVideo;Video;Player;
StartupWMClass=com-mediahub-pear-desktop-MainKt
D

cat > "$top/SPECS/pear.spec" <<SPEC
%define debug_package %{nil}
%global __os_install_post %{nil}
%global _build_id_links none
%define _binary_payload w9.gzdio
Name: pear
Version: $version
Release: 1
Summary: Pear media hub
License: Proprietary
BuildArch: x86_64
AutoReqProv: no
# libmpv is loaded with dlopen, so rpmbuild can't detect it. Fedora: mpv-libs (RPM Fusion). Debian/Ubuntu: libmpv2.
Requires: libmpv.so.2()(64bit)
Requires(post): /bin/sh
%description
Pear media hub.
%install
mkdir -p %{buildroot}
cp -a $root/. %{buildroot}/
%files
/opt/pear
/usr/bin/pear
/usr/share/applications/pear.desktop
/usr/share/icons/hicolor/512x512/apps/pear.png
%post
command -v update-desktop-database >/dev/null 2>&1 && update-desktop-database -q /usr/share/applications || :
command -v gtk-update-icon-cache >/dev/null 2>&1 && gtk-update-icon-cache -q -t -f /usr/share/icons/hicolor || :
%postun
command -v update-desktop-database >/dev/null 2>&1 && update-desktop-database -q /usr/share/applications || :
SPEC

rpmbuild -bb --define "_topdir $top" "$top/SPECS/pear.spec"
cp "$top"/RPMS/*/pear-*.rpm "$out"/
ls -l "$out"
