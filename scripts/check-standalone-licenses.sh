#!/usr/bin/env bash
# Release gate for the -standalone CLI jar's license payload. Fails when:
#   - the jar-root LICENSE is not the project's Apache-2.0 license (e.g. a dependency's won),
#   - NOTICE or the generated THIRD-PARTY listing is missing,
#   - any bundled third-party artifact has no META-INF/licenses/<artifactId>/ text,
#   - META-INF/LICENSE exists (a dependency's license would read as the whole jar's).
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CLI_TARGET="$REPO_ROOT/schema-synchronizer-cli/target"
JAR="${1:-}"
if [[ -z "$JAR" ]]; then
  VERSION="$(sed -n 's:.*<version>\(.*\)</version>.*:\1:p' "$REPO_ROOT/pom.xml" | head -1)"
  JAR="$CLI_TARGET/schema-synchronizer-cli-$VERSION-standalone.jar"
fi
LISTING="$CLI_TARGET/generated-sources/license/THIRD-PARTY.txt"

fail() { echo "ERROR: $*" >&2; exit 1; }

[[ -n "$JAR" && -f "$JAR" ]] || fail "standalone jar not found; run mvn package first"
[[ -f "$LISTING" ]] || fail "$LISTING not found"

entries="$(unzip -Z1 "$JAR")"

has() { grep -qxF "$1" <<<"$entries"; }

has LICENSE || fail "jar-root LICENSE missing"
has NOTICE || fail "jar-root NOTICE missing"
has META-INF/NOTICE || fail "META-INF/NOTICE missing"
has META-INF/licenses/THIRD-PARTY.txt || fail "META-INF/licenses/THIRD-PARTY.txt missing"
if grep -qx 'META-INF/LICENSE' <<<"$entries"; then
  fail "META-INF/LICENSE present; a single dependency's license would read as the whole jar's"
fi

unzip -p "$JAR" LICENSE | head -3 | grep -q "Apache License" \
  || fail "jar-root LICENSE is not the project's Apache License 2.0"
if unzip -p "$JAR" LICENSE | grep -q "GNU General Public License"; then
  fail "jar-root LICENSE contains GPL text (a dependency license overwrote the project LICENSE)"
fi
cmp -s <(unzip -p "$JAR" LICENSE) "$REPO_ROOT/LICENSE" || fail "jar-root LICENSE differs from $REPO_ROOT/LICENSE"
cmp -s <(unzip -p "$JAR" NOTICE) "$REPO_ROOT/NOTICE" || fail "jar-root NOTICE differs from $REPO_ROOT/NOTICE"

count=0
while IFS=: read -r group artifact version; do
  count=$((count + 1))
  grep -qE "^META-INF/licenses/$artifact/[^/]+$" <<<"$entries" \
    || fail "$group:$artifact:$version is bundled but META-INF/licenses/$artifact/ has no license text (run scripts/refresh-third-party-licenses.sh)"
done < <(grep -oE '\([A-Za-z0-9_.-]+:[A-Za-z0-9_.-]+:[^ )]+ - ' "$LISTING" | sed -E 's/^\(//; s/ - $//' | sort -u)

[[ "$count" -gt 0 ]] || fail "no third-party artifacts parsed from $LISTING"
echo "Standalone jar license payload OK: $(basename "$JAR") ($count third-party artifacts)"
