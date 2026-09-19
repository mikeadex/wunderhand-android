#!/bin/sh
# Turn the Firebase console's google-services.json into firebase.properties, which the build reads.
#
#   scripts/firebase-config.sh ~/Downloads/google-services.json
#
# The Firebase project needs two Android apps: com.wunderhand.app (the store build) and
# com.wunderhand.app.debug (the one run from here). One google-services.json covers both.
# Neither file goes in git. Without firebase.properties the app builds and runs with push off.
set -eu
here=$(cd "$(dirname "$0")/.." && pwd)
src="${1:?the path to google-services.json}"
[ -f "$src" ] || { echo "no file at $src" >&2; exit 1; }
python3 - "$src" "$here/firebase.properties" <<'PY'
import json, sys
src, dest = sys.argv[1], sys.argv[2]
data = json.load(open(src))
info = data["project_info"]
apps = {c["client_info"]["android_client_info"]["package_name"]: c for c in data["client"]}
lines = [f"projectId={info['project_id']}", f"senderId={info['project_number']}"]
missing = [p for p in ("com.wunderhand.app", "com.wunderhand.app.debug") if p not in apps]
first = next(iter(apps.values()))
lines.append(f"apiKey={first['api_key'][0]['current_key']}")
for package, key in (("com.wunderhand.app", "release.appId"), ("com.wunderhand.app.debug", "debug.appId")):
    if package in apps: lines.append(f"{key}={apps[package]['client_info']['mobilesdk_app_id']}")
open(dest, "w").write("\n".join(lines) + "\n")
print(f"wrote {dest} for project {info['project_id']}")
for p in missing: print(f"  note: no Android app for {p} in this Firebase project yet — add it in the console and run this again")
PY
