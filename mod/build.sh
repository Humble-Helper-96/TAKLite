#!/usr/bin/env bash
# Builds TAK Lite 1.5.0 with custom map sources (TileServer GL etc.), offline
# map download and import of TAK data packages, KML, KMZ, GPX and CoT.
#
# Upstream no longer publishes TAK Lite's source, so this starts from the
# official release APK, verifies its checksum, and adds the feature on top:
#   1. decode the APK with apktool
#   2. apply patches/taklite-1.5.0.patch (small hooks in MainActivity,
#      SettingsActivity, TakManager and TakLocationService, Settings buttons,
#      the import activity, cleartext HTTP for LAN servers)
#   3. compile src/ (the features themselves, plain Java) into an extra classes3.dex
#   4. rebuild, zip-align and sign with your own key
#
# Needs: bash, curl, unzip, python3, patch, and a JDK 17+ (java, javac, keytool).
# No Android SDK required. Output: mod/out/TAKLite-V1.5.1-custommaps.apk
set -euo pipefail

MOD="$(cd "$(dirname "$0")" && pwd)"
WORK="$MOD/work"
TOOLS="$WORK/tools"
OUT="$MOD/out"
KEYSTORE="${KEYSTORE:-$MOD/keystore/taklite-mod.p12}"
KEYSTORE_PASS="${KEYSTORE_PASS:-taklite}"
KEY_ALIAS="${KEY_ALIAS:-taklite}"

APK_NAME=TAKLite-V1.5.0-release.apk
APK_URL=https://github.com/RyanR3/TAKLite/releases/download/v1.4.9/$APK_NAME
APK_SHA256=cbb00eff0abbd3826b075c3d81c805b8d949592489a211bc1398414d485290a1

APKTOOL_URL=https://github.com/iBotPeaches/Apktool/releases/download/v2.10.0/apktool_2.10.0.jar
APKTOOL_SHA256=c0350abbab5314248dfe2ee0c907def4edd14f6faef1f5d372d3d4abd28f0431
# jadx is only used for the d8 dexer and apksig signer it bundles.
JADX_URL=https://github.com/skylot/jadx/releases/download/v1.5.1/jadx-1.5.1.zip
JADX_SHA256=12fd966431903b8e15c36e5007f19343475be7d8f2a55f082e7a929eeabc937e
OSMDROID_URL=https://repo1.maven.org/maven2/org/osmdroid/osmdroid-android/6.1.15/osmdroid-android-6.1.15.aar
OSMDROID_SHA256=96464c7267ed966e69a076c83bda562c9147b7756fec013ac0e5c56df2b91476
# Compile-time only (never packaged). Uses your SDK's copy when ANDROID_HOME is set.
ANDROID_JAR_URL=https://raw.githubusercontent.com/Reginer/aosp-android-jar/main/android-35/android.jar

log() { printf '\n==> %s\n' "$*"; }
die() { printf 'error: %s\n' "$*" >&2; exit 1; }

for cmd in curl unzip python3 patch java javac keytool; do
    command -v "$cmd" >/dev/null || die "'$cmd' is not installed"
done

# fetch URL DEST [SHA256]
fetch() {
    local url=$1 dest=$2 sha=${3:-}
    if [ ! -s "$dest" ]; then
        echo "downloading $(basename "$dest")"
        curl -fsSL --retry 3 -o "$dest.part" "$url"
        mv "$dest.part" "$dest"
    fi
    if [ -n "$sha" ]; then
        echo "$sha  $dest" | sha256sum -c --quiet - || die "checksum mismatch for $dest (delete it to re-download)"
    fi
}

mkdir -p "$TOOLS" "$OUT"

log "Fetching the official APK and build tools"
fetch "$APK_URL" "$WORK/$APK_NAME" "$APK_SHA256"
fetch "$APKTOOL_URL" "$TOOLS/apktool.jar" "$APKTOOL_SHA256"
fetch "$JADX_URL" "$TOOLS/jadx.zip" "$JADX_SHA256"
fetch "$OSMDROID_URL" "$TOOLS/osmdroid.aar" "$OSMDROID_SHA256"
JADX_JAR="$TOOLS/jadx-1.5.1-all.jar"
[ -s "$JADX_JAR" ] || unzip -p "$TOOLS/jadx.zip" lib/jadx-1.5.1-all.jar > "$JADX_JAR"
[ -s "$TOOLS/osmdroid.jar" ] || unzip -p "$TOOLS/osmdroid.aar" classes.jar > "$TOOLS/osmdroid.jar"
if [ -n "${ANDROID_HOME:-}" ] && [ -s "$ANDROID_HOME/platforms/android-35/android.jar" ]; then
    ANDROID_JAR="$ANDROID_HOME/platforms/android-35/android.jar"
else
    ANDROID_JAR="$TOOLS/android-35.jar"
    fetch "$ANDROID_JAR_URL" "$ANDROID_JAR"
fi

log "Decoding the APK"
rm -rf "$WORK/decoded"
java -jar "$TOOLS/apktool.jar" d -f -o "$WORK/decoded" "$WORK/$APK_NAME"

log "Applying patches"
patch -p1 --forward -d "$WORK/decoded" < "$MOD/patches/taklite-1.5.0.patch"

log "Compiling the mod"
rm -rf "$WORK/classes" "$WORK/stubs" "$WORK/dex"
mkdir -p "$WORK/classes" "$WORK/stubs" "$WORK/dex"
CP="$ANDROID_JAR:$TOOLS/osmdroid.jar"
JAVAC=(javac --release 8 -proc:none -nowarn -encoding UTF-8)
"${JAVAC[@]}" -cp "$CP" -d "$WORK/stubs" $(find "$MOD/stubs" -name '*.java') 2>&1 | grep -v '^warning\|UnsupportedAppUsage\|^Note:\|warnings$' || true
"${JAVAC[@]}" -cp "$CP:$WORK/stubs" -d "$WORK/classes" $(find "$MOD/src" -name '*.java') 2>&1 | grep -v '^warning\|UnsupportedAppUsage\|^Note:\|warnings$' || true
[ -s "$WORK/classes/com/taklite/client/mod/CustomMapSources.class" ] || die "javac failed"
java -cp "$JADX_JAR" com.android.tools.r8.D8 --release --min-api 26 \
    --lib "$ANDROID_JAR" --classpath "$TOOLS/osmdroid.jar" --classpath "$WORK/stubs" \
    --output "$WORK/dex" $(find "$WORK/classes" -name '*.class')

log "Rebuilding the APK"
java -jar "$TOOLS/apktool.jar" b -o "$WORK/unsigned.apk" "$WORK/decoded"
python3 - "$WORK/unsigned.apk" "$WORK/dex/classes.dex" <<'EOF'
import sys, zipfile
apk, dex = sys.argv[1:]
with zipfile.ZipFile(apk, 'a', zipfile.ZIP_DEFLATED) as z:
    if 'classes3.dex' in z.namelist():
        sys.exit('classes3.dex already exists; update build.sh to use the next free index')
    z.write(dex, 'classes3.dex')
EOF

if [ ! -f "$KEYSTORE" ]; then
    log "Creating signing key $KEYSTORE (keep it: updates must be signed with the same key)"
    mkdir -p "$(dirname "$KEYSTORE")"
    keytool -genkeypair -noprompt -storetype PKCS12 -keystore "$KEYSTORE" \
        -storepass "$KEYSTORE_PASS" -keypass "$KEYSTORE_PASS" -alias "$KEY_ALIAS" \
        -keyalg RSA -keysize 3072 -validity 10000 -dname "CN=TAK Lite Custom Maps"
fi

log "Signing"
java -cp "$JADX_JAR" "$MOD/tools/SignApk.java" "$WORK/unsigned.apk" \
    "$OUT/TAKLite-V1.5.1-custommaps.apk" "$KEYSTORE" "$KEYSTORE_PASS" "$KEY_ALIAS"

log "Done: $OUT/TAKLite-V1.5.1-custommaps.apk"
