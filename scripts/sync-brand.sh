#!/bin/sh
# Redraw the app's icons from chairtime's brand code. See scripts/sync-brand.mts.
#
#   scripts/sync-brand.sh
#   CHAIRTIME=../chairtime-m5 scripts/sync-brand.sh     # another checkout or worktree
#
# It runs inside chairtime, whose packages (tsx, sharp, lucide-react) do the work.
set -eu
here=$(cd "$(dirname "$0")/.." && pwd)
chairtime=$(cd "${CHAIRTIME:-$here/../chairtime}" 2>/dev/null && pwd) \
  || { echo "no chairtime at ${CHAIRTIME:-$here/../chairtime} — is it cloned beside this project?" >&2; exit 1; }
[ -d "$chairtime/node_modules/lucide-react" ] \
  || { echo "chairtime's packages are not installed: run npm install in $chairtime" >&2; exit 1; }
cd "$chairtime"
CHAIRTIME_ABS="$chairtime" APP_ABS="$here" npx tsx "$here/scripts/sync-brand.mts"
