#!/usr/bin/env bash
# Regenerate schema-synchronizer-cli/src/main/resources/META-INF/licenses/<artifactId>/ from the
# dependencies bundled in the -standalone jar. Run after changing any CLI runtime dependency:
#
#   mvn -B -Dmaven.test.skip=true package
#   scripts/refresh-third-party-licenses.sh
#   scripts/check-standalone-licenses.sh
#
# License texts are copied from each dependency jar when the jar ships them; jars that ship none
# are fetched from the upstream repository at the tag matching the bundled version.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LISTING="$REPO_ROOT/schema-synchronizer-cli/target/generated-sources/license/THIRD-PARTY.txt"
DEST="$REPO_ROOT/schema-synchronizer-cli/src/main/resources/META-INF/licenses"
M2="${MAVEN_REPO_LOCAL:-$HOME/.m2/repository}"

if [[ ! -f "$LISTING" ]]; then
  echo "ERROR: $LISTING not found; run 'mvn -B -Dmaven.test.skip=true package' first" >&2
  exit 1
fi

upstream_url() {
  local artifact="$1" version="$2"
  case "$artifact" in
    mariadb-java-client)
      echo "https://raw.githubusercontent.com/mariadb-corporation/mariadb-connector-j/${version}/LICENSE" ;;
    mssql-jdbc)
      echo "https://raw.githubusercontent.com/microsoft/mssql-jdbc/v${version%%.jre*}/LICENSE" ;;
    protobuf-java)
      echo "https://raw.githubusercontent.com/protocolbuffers/protobuf/v${version#*.}/LICENSE" ;;
    *) echo "" ;;
  esac
}

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

find "$DEST" -mindepth 1 -maxdepth 1 -type d -exec rm -rf {} + 2>/dev/null || true
mkdir -p "$DEST"

grep -oE '\([A-Za-z0-9_.-]+:[A-Za-z0-9_.-]+:[^ )]+ - ' "$LISTING" \
  | sed -E 's/^\(//; s/ - $//' | sort -u | while IFS=: read -r group artifact version; do
  jar="$M2/${group//.//}/$artifact/$version/$artifact-$version.jar"
  out="$DEST/$artifact"
  mkdir -p "$out"
  if [[ ! -f "$jar" ]]; then
    echo "ERROR: $jar not in local repository" >&2
    exit 1
  fi
  rm -rf "$TMP/x" && mkdir -p "$TMP/x"
  unzip -qq -o "$jar" -x '*.class' 'META-INF/licenses/*' -d "$TMP/x" 2>/dev/null || true
  copied=0
  for f in LICENSE LICENSE.txt LICENSE.md NOTICE NOTICE.txt NOTICE.md README \
           META-INF/LICENSE META-INF/LICENSE.txt META-INF/LICENSE.md META-INF/license.txt \
           META-INF/NOTICE META-INF/NOTICE.txt META-INF/NOTICE.md; do
    if [[ -f "$TMP/x/$f" ]]; then
      cp "$TMP/x/$f" "$out/$(basename "$f")"
      copied=1
    fi
  done
  for f in "$TMP"/x/META-INF/*-LICENSE; do
    [[ -f "$f" ]] && cp "$f" "$out/" && copied=1
  done
  if [[ "$copied" == 0 ]]; then
    url="$(upstream_url "$artifact" "$version")"
    if [[ -z "$url" ]]; then
      echo "ERROR: $group:$artifact:$version ships no license text; add an upstream URL" >&2
      exit 1
    fi
    curl -fsSL "$url" -o "$out/LICENSE"
    echo "$artifact $version: fetched $url"
  else
    echo "$artifact $version: copied from jar"
  fi
done
