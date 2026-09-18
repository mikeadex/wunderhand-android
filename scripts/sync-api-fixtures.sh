#!/bin/sh
# Copy chairtime's API contract fixtures into :core's tests.
#
# chairtime writes them from real responses:
#   cd ../chairtime && UPDATE_API_FIXTURES=1 npx vitest run tests/api-v1-*.test.ts
# Then run this, then `./gradlew :core:test`. A field renamed on either side
# fails a test instead of a pro's screen. The iOS app reads the same files
# (../wunderhand/scripts/sync-api-fixtures.sh), so the two apps cannot come to
# disagree about what the API says.
set -eu
here=$(cd "$(dirname "$0")/.." && pwd)
# Another checkout or worktree of chairtime: CHAIRTIME=../chairtime-m5 scripts/sync-api-fixtures.sh
src="${CHAIRTIME:-$here/../chairtime}/tests/fixtures/api-v1"
dest="$here/core/src/test/resources/fixtures"
[ -d "$src" ] || { echo "no fixtures at $src — is chairtime cloned beside this project?" >&2; exit 1; }
mkdir -p "$dest"
cp "$src"/*.json "$dest"/
echo "copied $(ls "$src"/*.json | wc -l | tr -d ' ') fixture(s) into $dest"
