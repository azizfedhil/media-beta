#!/usr/bin/env bash
# Signs every .exe and .dll under the given files or folders, with the certificate in WINDOWS_SIGN_PFX_BASE64.
#
#   tools/sign_windows.sh desktopApp/build/compose/binaries/main/app/Pear desktopApp/build/installer/PearSetup.exe
#
# Needs, from the environment (CI passes them from secrets):
#   WINDOWS_SIGN_PFX_BASE64    the code-signing certificate as a base64 .pfx
#   WINDOWS_SIGN_PFX_PASSWORD  its password
#   WINDOWS_SIGN_TIMESTAMP_URL optional; a timestamp server, so signatures outlive the certificate
#
# Runs in Git Bash on a Windows runner (signtool is part of the Windows SDK). The caller sets MSYS2_ARG_CONV_EXCL=*,
# so that Git Bash does not rewrite signtool's /switches as paths.
# It signs with a certificate in a file (an OV or EV .pfx). A cloud signer (Artifact Signing, SignPath, or a CA's cloud
# HSM) needs its own tool in place of signtool's /f option; see docs/desktop/code-signing.md.
set -euo pipefail

: "${WINDOWS_SIGN_PFX_BASE64:?WINDOWS_SIGN_PFX_BASE64 is not set}"
: "${WINDOWS_SIGN_PFX_PASSWORD:?WINDOWS_SIGN_PFX_PASSWORD is not set}"
timestamp="${WINDOWS_SIGN_TIMESTAMP_URL:-http://timestamp.digicert.com}"

if [ "$#" -eq 0 ]; then
  echo "usage: $0 <file-or-folder>..." >&2
  exit 2
fi

signtool="$(find "/c/Program Files (x86)/Windows Kits/10/bin" -path "*/x64/signtool.exe" 2>/dev/null | sort | tail -n 1)"
if [ -z "$signtool" ]; then
  echo "signtool.exe not found: install the Windows SDK, or use a runner image that has it." >&2
  exit 1
fi

workdir="$(mktemp -d)"
trap 'rm -rf "$workdir"' EXIT
pfx="$workdir/sign.pfx"
printf '%s' "$WINDOWS_SIGN_PFX_BASE64" | base64 --decode > "$pfx"

files=()
for target in "$@"; do
  if [ -d "$target" ]; then
    mapfile -d '' found < <(find "$target" -type f \( -iname '*.exe' -o -iname '*.dll' \) -print0)
    files+=("${found[@]}")
  elif [ -f "$target" ]; then
    files+=("$target")
  else
    echo "not found: $target" >&2
    exit 1
  fi
done

if [ "${#files[@]}" -eq 0 ]; then
  echo "nothing to sign under: $*" >&2
  exit 1
fi

for file in "${files[@]}"; do
  echo "signing $file"
  "$signtool" sign /fd SHA256 /f "$pfx" /p "$WINDOWS_SIGN_PFX_PASSWORD" /tr "$timestamp" /td SHA256 "$file"
done
echo "signed ${#files[@]} file(s)"
