#!/usr/bin/env bash
# Run the MySQL integration contract against supported compatible engines.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
RUN_ID="$$"
PERCONA_CONTAINER="schemasync-percona-$RUN_ID"
TIDB_CONTAINER="schemasync-tidb-$RUN_ID"

cleanup() {
  docker rm -f "$PERCONA_CONTAINER" "$TIDB_CONTAINER" >/dev/null 2>&1 || true
}
trap cleanup EXIT

docker run -d --name "$PERCONA_CONTAINER" \
  -e MYSQL_DATABASE=schema_synchronizer_test \
  -e MYSQL_USER=schema_sync \
  -e MYSQL_PASSWORD=schema_sync \
  -e MYSQL_ROOT_PASSWORD=root \
  -p 127.0.0.1::3306 \
  percona/percona-server:8.4 >/dev/null

docker run -d --name "$TIDB_CONTAINER" \
  -p 127.0.0.1::4000 \
  pingcap/tidb:v8.5.4 >/dev/null

PERCONA_PORT="$(docker port "$PERCONA_CONTAINER" 3306/tcp | sed 's/.*://')"
TIDB_PORT="$(docker port "$TIDB_CONTAINER" 4000/tcp | sed 's/.*://')"

for attempt in {1..90}; do
  if docker exec "$PERCONA_CONTAINER" mysqladmin ping -h 127.0.0.1 \
      -uschema_sync -pschema_sync --silent \
      >/dev/null 2>&1; then
    break
  fi
  if [[ "$attempt" == 90 ]]; then
    echo "ERROR: Percona Server did not become ready" >&2
    exit 1
  fi
  sleep 1
done

for attempt in {1..90}; do
  if (echo >/dev/tcp/127.0.0.1/"$TIDB_PORT") >/dev/null 2>&1; then
    break
  fi
  if [[ "$attempt" == 90 ]]; then
    echo "ERROR: TiDB did not become ready" >&2
    exit 1
  fi
  sleep 1
done

run_contract() {
  local label="$1"
  local url="$2"
  local user="$3"
  local password="$4"
  echo "Verifying MySQL dialect against $label"
  mvn --batch-mode --no-transfer-progress -f "$REPO_ROOT/pom.xml" \
    -pl schema-synchronizer \
    -Dtest=SchemaSynchronizerMySqlIntegrationTest \
    -Dschema.test.mysql.jdbc.url="$url" \
    -Dschema.test.mysql.jdbc.user="$user" \
    -Dschema.test.mysql.jdbc.password="$password" \
    test
}

run_contract "Percona Server 8.4" \
  "jdbc:mysql://127.0.0.1:$PERCONA_PORT/schema_synchronizer_test" schema_sync schema_sync
run_contract "TiDB 8.5.4" \
  "jdbc:mysql://127.0.0.1:$TIDB_PORT/test" root ""

echo "Percona Server and TiDB compatibility verification passed."
