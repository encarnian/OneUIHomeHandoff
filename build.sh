#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
variant="${1:---debug}"
case "$variant" in --debug|--release|--unsigned) ;; *) echo 'Usage: ./build.sh [--debug|--release|--unsigned]' >&2; exit 2 ;; esac
sdk_path="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
if [[ -z "$sdk_path" ]]; then echo 'Set ANDROID_SDK_ROOT to your Android SDK directory.' >&2; exit 2; fi
build_tools="$sdk_path/build-tools/36.0.0"
android_jar="$sdk_path/platforms/android-36/android.jar"
for required in "$build_tools/aapt" "$build_tools/d8" "$build_tools/zipalign" "$build_tools/apksigner" "$android_jar"; do
    if [[ ! -f "$required" ]]; then echo "Missing SDK component: $required" >&2; exit 2; fi
done
for required in java javac jar python3; do command -v "$required" >/dev/null || { echo "Missing tool: $required" >&2; exit 2; }; done
mkdir -p lib build
if [[ ! -f lib/xposed-api-82.jar ]]; then
    curl --fail --location --retry 3 --output lib/xposed-api-82.jar.tmp https://api.xposed.info/de/robv/android/xposed/api/82/api-82.jar
    mv lib/xposed-api-82.jar.tmp lib/xposed-api-82.jar
fi
python3 - <<'PY'
import hashlib
from pathlib import Path
p=Path('lib/xposed-api-82.jar')
expected='f48c635f1c7469fdec0e00ad2ea0b7a6b2f5b55065784a35b7ca3a84615e8e25'
if hashlib.sha256(p.read_bytes()).hexdigest()!=expected:
    raise SystemExit('Xposed API checksum mismatch. Remove lib/xposed-api-82.jar and try again.')
PY
# Clean generated classes so deleted experimental hooks cannot leak into an update.
rm -rf build/classes build/dex build/generated
mkdir -p build/classes build/dex build/generated/io/github/encarnian/oneuihomehandoff
version_name="$(python3 - <<'PY'
import re,xml.etree.ElementTree as ET
from pathlib import Path
root=ET.parse('AndroidManifest.xml').getroot()
name=root.attrib['{http://schemas.android.com/apk/res/android}versionName']
if not re.fullmatch(r'[0-9]+\.[0-9]+\.[0-9]+',name):raise SystemExit('Use a numeric MAJOR.MINOR.PATCH versionName.')
code=int(root.attrib['{http://schemas.android.com/apk/res/android}versionCode'])
if code<1:raise SystemExit('versionCode must be positive.')
Path('build/generated/io/github/encarnian/oneuihomehandoff/BuildInfo.java').write_text('package io.github.encarnian.oneuihomehandoff; final class BuildInfo { static final String VERSION_NAME="'+name+'"; }\n')
print(name)
PY
)"
"$build_tools/aapt" package -f -M AndroidManifest.xml -S res -A assets -I "$android_jar" -F build/resources.apk
javac --release 8 -cp "$android_jar:lib/xposed-api-82.jar" -d build/classes src/io/github/encarnian/oneuihomehandoff/*.java build/generated/io/github/encarnian/oneuihomehandoff/BuildInfo.java
jar cf build/classes.jar -C build/classes .
"$build_tools/d8" --release --min-api 35 --lib "$android_jar" --classpath lib/xposed-api-82.jar --output build/dex build/classes.jar
cp build/resources.apk build/unsigned.apk
python3 - <<'PY'
import zipfile
from pathlib import Path
# Normalize ZIP metadata so build-machine timestamps and attributes are not published.
with zipfile.ZipFile('build/resources.apk') as resources, zipfile.ZipFile('build/unsigned.apk','w') as apk:
    entries={item.filename:(resources.read(item.filename),item.compress_type) for item in resources.infolist()}
    entries['classes.dex']=(Path('build/dex/classes.dex').read_bytes(),zipfile.ZIP_DEFLATED)
    for name,(data,compression) in sorted(entries.items()):
        entry=zipfile.ZipInfo(name,date_time=(1980,1,1,0,0,0))
        entry.compress_type=compression
        entry.create_system=3
        entry.external_attr=0o100644<<16
        apk.writestr(entry,data)
PY
"$build_tools/zipalign" -f -p 4 build/unsigned.apk build/aligned.apk
if [[ "$variant" == --unsigned ]]; then
    artifact="build/OneUIHomeHandoff-$version_name.unsigned.apk"
    cp build/aligned.apk "$artifact"
else
    if [[ "$variant" == --release ]]; then
        : "${SIGNING_KEYSTORE_PATH:?Set SIGNING_KEYSTORE_PATH to the release keystore outside this repository.}"
        : "${SIGNING_KEY_ALIAS:?Set SIGNING_KEY_ALIAS.}"
        : "${SIGNING_STORE_PASSWORD:?Set SIGNING_STORE_PASSWORD.}"
        export SIGNING_KEY_PASSWORD="${SIGNING_KEY_PASSWORD:-$SIGNING_STORE_PASSWORD}"
        python3 - <<'PY'
import os
from pathlib import Path
key=Path(os.environ['SIGNING_KEYSTORE_PATH']).resolve()
if key.is_relative_to(Path.cwd().resolve()):raise SystemExit('Keep the release keystore outside the source checkout.')
if not key.is_file():raise SystemExit('Release keystore does not exist.')
PY
        artifact="build/OneUIHomeHandoff-$version_name.apk"
    else
        mkdir -p build/private
        export SIGNING_KEYSTORE_PATH="$PWD/build/private/debug.jks"
        export SIGNING_KEY_ALIAS=androiddebugkey
        export SIGNING_STORE_PASSWORD=android
        export SIGNING_KEY_PASSWORD=android
        if [[ ! -f "$SIGNING_KEYSTORE_PATH" ]]; then
            keytool -genkeypair -keystore "$SIGNING_KEYSTORE_PATH" -storepass:env SIGNING_STORE_PASSWORD -keypass:env SIGNING_KEY_PASSWORD -alias "$SIGNING_KEY_ALIAS" -keyalg RSA -keysize 2048 -validity 3650 -dname 'CN=Android Debug,O=Android,C=US' -noprompt
        fi
        artifact="build/OneUIHomeHandoff-$version_name.debug.apk"
    fi
    "$build_tools/apksigner" sign --ks "$SIGNING_KEYSTORE_PATH" --ks-key-alias "$SIGNING_KEY_ALIAS" --ks-pass env:SIGNING_STORE_PASSWORD --key-pass env:SIGNING_KEY_PASSWORD --out "$artifact" build/aligned.apk
    "$build_tools/apksigner" verify --verbose "$artifact"
fi
"$build_tools/zipalign" -c -p 4 "$artifact"
python3 - "$artifact" <<'PY'
import hashlib,sys
from pathlib import Path
p=Path(sys.argv[1]);digest=hashlib.sha256(p.read_bytes()).hexdigest()
p.with_suffix(p.suffix+'.sha256').write_text(digest+'  '+p.name+'\n')
print('Built '+str(p))
PY
