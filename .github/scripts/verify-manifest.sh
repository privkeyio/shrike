#!/usr/bin/env bash
#
# Fails unless the manifest covers every artifact a release is expected to carry.
#
# The checksums job runs with `if: !cancelled()` so that it is not skipped by the matrix job's overall
# result. That also means it runs when a build job has failed, and it happily writes a manifest for
# whatever artifacts did arrive. Twice that nearly shipped: a Windows build failed and the manifest lost
# the .msi and .zip, and a macOS build failed and it lost the arm64 dmg. Both were caught by comparing
# the manifest against the artifacts by hand, which is not a check that scales.
#
# The counts are deliberate rather than derived. A release carries a known set of files, and the point is
# to notice when one is absent, so changing the build matrix has to change this too.
set -euo pipefail

manifest="${1:?usage: verify-manifest.sh SHA256SUMS}"

# Without this, a path that cannot be read makes every grep fail rather than match nothing, the counts come
# out empty, `[ -ne ]` errors and is read as false, and the gate exits 0. A check that passes when it cannot
# see the thing it is checking is worse than no check.
if [ ! -r "$manifest" ]; then
    echo "no readable manifest at ${manifest}" >&2
    exit 1
fi

# extension:count, one entry per class of artifact the matrix produces
required=(
    '\.msi$:1'          # windows installer
    '\.zip$:1'          # windows portable
    '\.dmg$:2'          # macos, x86_64 and arm64
    '\.deb$:4'          # linux, {x86_64,aarch64} x {gui,headless}
    '\.tar\.gz$:4'      # linux, {x86_64,aarch64} x {gui,headless}
)

short=0
for spec in "${required[@]}"; do
    pattern="${spec%:*}"
    expected="${spec##*:}"
    # grep exits 1 for no matches and 2 for an error, and only the first is an expected answer here
    actual=$(grep -cE "$pattern" "$manifest") || [ $? -eq 1 ] || exit 1
    if [ "$actual" -ne "$expected" ]; then
        echo "manifest carries $actual file(s) matching ${pattern}, expected ${expected}" >&2
        short=1
    fi
done

if [ "$short" -ne 0 ]; then
    echo "" >&2
    echo "The manifest does not cover every platform, which means a build job failed and this ran anyway." >&2
    echo "This manifest is signed by hand at release time, so publishing it would hand users a signature" >&2
    echo "covering everything except the download they cannot then check." >&2
    exit 1
fi

echo "manifest covers all $(wc -l < "$manifest") expected artifacts"
