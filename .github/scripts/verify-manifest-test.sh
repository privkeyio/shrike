#!/usr/bin/env bash
#
# Pins the exit codes of verify-manifest.sh, which is a release gate whose only other exercise is a live
# workflow run. The fixtures are real manifests: complete is the one published for 2.5.5-blake2b.14, and
# missing-dmg is the shape the failed macOS run actually produced.
set -uo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
verify="$here/verify-manifest.sh"
failures=0

expect() {
    local want="$1" name="$2" manifest="$3"
    "$verify" "$manifest" >/dev/null 2>&1
    local got=$?
    if [ "$got" -eq "$want" ]; then
        echo "  pass  $name (exit $got)"
    else
        echo "  FAIL  $name: expected exit $want, got $got"
        failures=$((failures + 1))
    fi
}

expect 0 "a complete manifest is accepted"           "$here/fixtures/manifest-complete"
expect 1 "a manifest missing one dmg is refused"     "$here/fixtures/manifest-missing-dmg"
expect 1 "a manifest missing windows is refused"     "$here/fixtures/manifest-missing-windows"
expect 1 "an empty manifest is refused"              "$here/fixtures/manifest-empty"
expect 1 "a manifest that does not exist is refused" "$here/fixtures/manifest-nonexistent"
expect 1 "a directory is refused"                    "$here/fixtures"
expect 1 "an unexpected artifact type is refused"    "$here/fixtures/manifest-unexpected-type"

if [ "$failures" -ne 0 ]; then
    echo "$failures case(s) failed" >&2
    exit 1
fi
echo "all cases pass"
