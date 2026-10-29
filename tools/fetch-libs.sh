#!/usr/bin/env bash
# Downloads the libraries the IPTV player needs (Google's Media3 1.4.1 and what it depends on) into libs/.
# build.sh runs it by itself when libs/ is empty. They come straight from Google's and Maven Central's repositories.
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p libs
GOOGLE=https://dl.google.com/android/maven2
CENTRAL=https://repo1.maven.org/maven2
# repository  group  artifact  version  type
LIBS="
G androidx.media3 media3-common 1.4.1 aar
G androidx.media3 media3-container 1.4.1 aar
G androidx.media3 media3-database 1.4.1 aar
G androidx.media3 media3-datasource 1.4.1 aar
G androidx.media3 media3-decoder 1.4.1 aar
G androidx.media3 media3-exoplayer 1.4.1 aar
G androidx.media3 media3-exoplayer-hls 1.4.1 aar
G androidx.media3 media3-extractor 1.4.1 aar
G androidx.annotation annotation 1.3.0 jar
G androidx.annotation annotation-experimental 1.3.1 aar
G androidx.collection collection 1.2.0 jar
G androidx.concurrent concurrent-futures 1.0.0 jar
G androidx.core core 1.8.0 aar
G androidx.arch.core core-common 2.1.0 jar
G androidx.arch.core core-runtime 2.1.0 aar
G androidx.exifinterface exifinterface 1.3.6 aar
G androidx.lifecycle lifecycle-common 2.3.1 jar
G androidx.lifecycle lifecycle-runtime 2.3.1 aar
G androidx.versionedparcelable versionedparcelable 1.1.1 aar
C com.google.guava guava 33.0.0-android jar
C com.google.guava failureaccess 1.0.2 jar
C com.google.guava listenablefuture 9999.0-empty-to-avoid-conflict-with-guava jar
C org.jetbrains annotations 13.0 jar
C org.jetbrains.kotlin kotlin-stdlib 1.7.10 jar
C org.jetbrains.kotlin kotlin-stdlib-common 1.7.10 jar
"
echo "$LIBS" | while read -r repo group artifact version type; do
  [ -n "$repo" ] || continue
  file="libs/$artifact-$version.$type"
  [ -s "$file" ] && continue
  base=$([ "$repo" = G ] && echo "$GOOGLE" || echo "$CENTRAL")
  url="$base/${group//.//}/$artifact/$version/$artifact-$version.$type"
  echo "Downloading $artifact $version"
  if ! curl -fsSL "$url" -o "$file.part"; then rm -f "$file.part"; echo "Couldn't download $url" >&2; exit 1; fi
  mv "$file.part" "$file"
done
echo "Libraries ready in libs/"
