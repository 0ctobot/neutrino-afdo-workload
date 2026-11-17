#!/bin/bash
# Build the workload app without Gradle: framework APIs only, compiled against
# Android's own Java API surface (a jlink system image made from the
# platform's core-for-system-modules.jar, as the Android Gradle plugin does),
# so a host-only JDK API fails at compile time.
#
#   ./build.sh                 out/workload.apk (release: not debuggable)
#   DEBUGGABLE=1 ./build.sh    debuggable (run-as works: for test phones without root)
#
# Pinned tools (override only on purpose; build-info.txt records what was used):
#   ANDROID_SDK (~/Android/Sdk), BUILD_TOOLS 36.0.0, PLATFORM android-35, NDK 28.2.13676358,
#   JAVA_HOME (a JDK 21), KEYSTORE (~/.android/debug.keystore, password android).
set -euo pipefail
H=$(cd "$(dirname "$0")" && pwd)
ANDROID_SDK=${ANDROID_SDK:-$HOME/Android/Sdk}
BUILD_TOOLS=${BUILD_TOOLS:-36.0.0}
PLATFORM=${PLATFORM:-android-35}
NDK=${NDK:-$ANDROID_SDK/ndk/28.2.13676358}
JAVA_HOME=${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk}
KEYSTORE=${KEYSTORE:-$HOME/.android/debug.keystore}
KS_PASS=${KS_PASS:-android}
BT=$ANDROID_SDK/build-tools/$BUILD_TOOLS
PLAT=$ANDROID_SDK/platforms/$PLATFORM
AJ=$PLAT/android.jar
OUT=$H/out
B=$OUT/build
export PATH=$JAVA_HOME/bin:$PATH

CC=$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android35-clang
for f in "$CC" "$BT/aapt2" "$BT/d8" "$BT/zipalign" "$BT/apksigner" "$AJ" "$PLAT/core-for-system-modules.jar" "$KEYSTORE"; do
	[ -e "$f" ] || { echo "missing: $f" >&2; exit 1; }
done
java -version 2>&1 | grep -q '"21\.' || { echo "JAVA_HOME must be a JDK 21" >&2; exit 1; }
[ -d "$H/app/assets" ] || { echo "no app/assets: run ./assets.sh first" >&2; exit 1; }

rm -rf "$B" && mkdir -p "$B/classes" "$B/res" "$OUT"

# Android's java.* surface as a system image (cached per platform jar).
SYS=$OUT/sysimage-$(sha256sum "$PLAT/core-for-system-modules.jar" | cut -c1-12)
if [ ! -f "$SYS/lib/jrt-fs.jar" ]; then
	T=$OUT/sysimage.tmp; rm -rf "$T" "$SYS"; mkdir -p "$T/mod" "$T/jmods"
	(cd "$T/mod" && unzip -q "$PLAT/core-for-system-modules.jar")
	{ echo 'module java.base {'
	  (cd "$T/mod" && find . -name '*.class' | sed 's|^\./||; s|/[^/]*$||' | sort -u | tr / . | sed 's/.*/  exports &;/')
	  echo '}'; } > "$T/module-info.java"
	javac --system=none --patch-module java.base="$T/mod" -d "$T/mod" "$T/module-info.java"
	jmod create --module-version "$(java -version 2>&1 | sed -n 's/.*version "\([0-9]*\).*/\1/p' | head -1)" \
		--target-platform linux-amd64 --class-path "$T/mod" "$T/jmods/java.base.jmod"
	jlink --module-path "$T/jmods" --add-modules java.base --output "$SYS" --disable-plugin system-modules
	cp "$JAVA_HOME/lib/jrt-fs.jar" "$SYS/lib/"
	rm -rf "$T"
fi

# Resources and manifest.
"$BT/aapt2" compile --dir "$H/app/res" -o "$B/res.zip"
debug=(); [ "${DEBUGGABLE:-0}" = 1 ] && debug=(--debug-mode)
VERSION_CODE=${VERSION_CODE:-$(git -C "$H" rev-list --count HEAD 2>/dev/null || echo 1)}
VERSION_NAME=${VERSION_NAME:-$(git -C "$H" describe --tags --always --dirty 2>/dev/null || echo dev)}
"$BT/aapt2" link -o "$B/base.apk" -I "$AJ" --manifest "$H/app/AndroidManifest.xml" "${debug[@]}" \
	--version-code "$VERSION_CODE" --version-name "$VERSION_NAME" --min-sdk-version 35 --target-sdk-version 35 \
	-A "$H/app/assets" --no-compress --java "$B/gen" "$B/res.zip"

# Java -> dex.
find "$H/app/src" "$B/gen" -name '*.java' > "$B/sources.txt"
javac --system "$SYS" -source 17 -target 17 -XDstringConcat=inline -encoding UTF-8 -Xlint:all,-options,-serial,-classfile -Werror \
	-classpath "$AJ" -d "$B/classes" @"$B/sources.txt"
"$BT/d8" --release --min-api 35 --lib "$AJ" --output "$B" $(find "$B/classes" -name '*.class')

# Native code (anonymous memory for mem-fixture): 16 KiB pages, stored
# uncompressed and page-aligned so it is mapped straight from the APK.
mkdir -p "$B/lib/arm64-v8a"
"$CC" -O2 -Wall -Wextra -Wno-unused-parameter -Werror -shared -fPIC -Wl,-z,max-page-size=16384 -Wl,--build-id=sha1 \
	-o "$B/lib/arm64-v8a/libafdomem.so" "$H/native/afdomem.c"

# Package, align, sign.
(cd "$B" && zip -q -0 base.apk classes.dex lib/arm64-v8a/libafdomem.so)
"$BT/zipalign" -f -P 16 4 "$B/base.apk" "$B/aligned.apk"
"$BT/apksigner" sign --ks "$KEYSTORE" --ks-pass pass:"$KS_PASS" --out "$OUT/workload.apk" "$B/aligned.apk"
"$BT/apksigner" verify "$OUT/workload.apk"

{
	echo "version_code=$VERSION_CODE"; echo "version_name=$VERSION_NAME"; echo "debuggable=${DEBUGGABLE:-0}"
	echo "build_tools=$BUILD_TOOLS"; echo "platform=$PLATFORM"; echo "ndk=$(basename "$NDK")"
	echo "platform_jar_sha256=$(sha256sum "$AJ" | cut -d' ' -f1)"
	echo "java=$(java -version 2>&1 | head -1)"
	echo "signer=$("$BT/apksigner" verify --print-certs "$OUT/workload.apk" | sed -n 's/.*SHA-256 digest: //p' | head -1)"
	echo "assets_manifest_sha256=$(sha256sum "$H/app/assets/MANIFEST" | cut -d' ' -f1)"
	echo "apk_sha256=$(sha256sum "$OUT/workload.apk" | cut -d' ' -f1)"
} > "$OUT/build-info.txt"
cat "$OUT/build-info.txt"
