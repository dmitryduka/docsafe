#!/usr/bin/env bash
#
# Verify that an official DocSafe APK was built from the current source checkout.
#
# It builds the release APK from the current working tree (if not already built),
# then compares its CONTENTS against the official APK, ignoring the cryptographic
# signature (which legitimately differs between signing keys). A content match
# proves the official APK's code/resources are exactly what this source compiles to.
#
# Usage:
#   tools/verify-apk.sh <official.apk> [rebuilt.apk]
#
# If <rebuilt.apk> is omitted, the script builds it with ./gradlew :app:assembleRelease.
#
set -euo pipefail

if [[ $# -lt 1 || $# -gt 2 ]]; then
  echo "Usage: $0 <official.apk> [rebuilt.apk]" >&2
  exit 2
fi

OFFICIAL="$1"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
REBUILT="${2:-}"

[[ -f "$OFFICIAL" ]] || { echo "error: official APK not found: $OFFICIAL" >&2; exit 2; }

if [[ -z "$REBUILT" ]]; then
  echo "==> Building release APK from source ($ROOT)"
  ( cd "$ROOT" && ./gradlew :app:assembleRelease )
  REBUILT="$ROOT/app/build/outputs/apk/release/app-release.apk"
fi
[[ -f "$REBUILT" ]] || { echo "error: rebuilt APK not found: $REBUILT" >&2; exit 2; }

echo "==> Official : $OFFICIAL"
echo "==> Rebuilt  : $REBUILT"
echo "==> Whole-file SHA-256 (will differ if the signing keys differ — that's expected):"
( shasum -a 256 "$OFFICIAL" "$REBUILT" 2>/dev/null || sha256sum "$OFFICIAL" "$REBUILT" ) | sed 's/^/    /'
echo
echo "==> Comparing entry contents, ignoring the signature…"

python3 - "$OFFICIAL" "$REBUILT" <<'PY'
import sys, zipfile, hashlib

def is_sig(name):
    n = name.upper()
    return name.startswith("META-INF/") and n.endswith((".RSA", ".DSA", ".EC", ".SF", ".MF"))

def digest_entries(path):
    out = {}
    with zipfile.ZipFile(path) as z:
        for info in z.infolist():
            name = info.filename
            if name.endswith("/") or is_sig(name):
                continue
            out[name] = hashlib.sha256(z.read(name)).hexdigest()
    return out

a = digest_entries(sys.argv[1])
b = digest_entries(sys.argv[2])

only_a = sorted(set(a) - set(b))
only_b = sorted(set(b) - set(a))
changed = sorted(n for n in (set(a) & set(b)) if a[n] != b[n])

for n in only_a:  print(f"    only in official : {n}")
for n in only_b:  print(f"    only in rebuilt  : {n}")
for n in changed: print(f"    differs          : {n}")

if not (only_a or only_b or changed):
    print(f"\nMATCH ✅  — {len(a)} entries identical. The official APK is built from this source.")
    sys.exit(0)
else:
    print(f"\nDIFFERENCES FOUND ❌  ({len(only_a)} only-official, {len(only_b)} only-rebuilt, {len(changed)} changed).")
    sys.exit(1)
PY
