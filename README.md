# thinkai-shared

Reusable libraries for ThinkAI Spring / Postgres apps (JSI, Nestlogue backend, MarginPulse, Marketworks, …).

## Modules

| Module | Artifact | Purpose |
|--------|----------|---------|
| **schema-applier** | `com.thinkai:schema-applier` | Apply non-destructive schema diffs from `schema-definition.json` on startup |

## schema-applier contract

SchemaApplier has two complementary layers:

1. `tables` is a serialized target-state snapshot. It converges tables, columns,
   defaults, safe type widenings, nullability relaxations, and indexes.
2. `changes` is an ordered, checksummed ledger for changes that cannot be inferred
   from column metadata: data backfills, foreign/check/unique constraints, functions,
   triggers, extensions, comments, and grants.

Every invocation is transactional and guarded by a PostgreSQL advisory lock. Applied
change IDs and SHA-256 checksums are stored in `thinkai_schema_history`. Editing an
applied change fails startup. A change may include a read-only `verificationSql` query;
this both verifies a fresh application and safely adopts an already-existing Flyway
change only when PostgreSQL proves the expected object is present.

**Auto-apply:** create table, add column, create index, SET/DROP DEFAULT, safe type
widenings, `NOT NULL` → nullable, and safety-checked ordered change sets.

**Rejected or pending:** drop table/column/index/schema/constraint, truncate, delete,
arbitrary type changes, type narrowing, and inferred `NULL` → `NOT NULL`. A versioned
change set may set `NOT NULL` after an explicit backfill. Unknown SQL is rejected.

The default is fail-closed: a missing definition, checksum drift, failed verification,
or pending destructive schema difference aborts startup.

## Install locally

```bash
cd thinkai-shared
mvn clean install
```

CI publishes tagged builds and manually dispatched builds to GitHub Packages at
`https://maven.pkg.github.com/eugenena/thinkai-shared`. Consumers outside the local
machine must configure that repository and a GitHub Packages credential in Maven
settings, then pin the published version rather than relying on a mutable local JAR.

Consuming app (e.g. getjsi):

```xml
<dependency>
  <groupId>com.thinkai</groupId>
  <artifactId>schema-applier</artifactId>
  <version>0.2.0-SNAPSHOT</version>
</dependency>
```

Spring Boot auto-configuration runs SchemaApplier after the DataSource exists and
registers it as a database initializer so JPA validation waits for it. Configuration:

```properties
thinkai.schema.enabled=true
thinkai.schema.resource=/schema-definition.json
thinkai.schema.schema=public
thinkai.schema.history-table=thinkai_schema_history
thinkai.schema.advisory-lock-id=7249031147
thinkai.schema.dry-run=false
thinkai.schema.fail-on-pending=true
thinkai.schema.require-definition=true
spring.jpa.hibernate.ddl-auto=validate
```

Example definition (statements are intentionally one JDBC statement per entry):

```json
{
  "tables": {
    "campaigns": {
      "createSql": "CREATE TABLE IF NOT EXISTS campaigns (id UUID PRIMARY KEY, status VARCHAR(32))",
      "columns": [
        {"name": "id", "definition": "UUID NOT NULL"},
        {"name": "status", "definition": "VARCHAR(32)"}
      ],
      "indexes": []
    }
  },
  "changes": [
    {
      "id": "001-campaign-status",
      "description": "Backfill and constrain campaign status",
      "statements": [
        "UPDATE campaigns SET status = 'DRAFT' WHERE status IS NULL",
        "ALTER TABLE campaigns ALTER COLUMN status SET NOT NULL",
        "ALTER TABLE campaigns ADD CONSTRAINT ck_campaign_status CHECK (status IN ('DRAFT', 'ACTIVE'))"
      ],
      "verificationSql": "SELECT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_campaign_status' AND conrelid = to_regclass('campaigns'))"
    }
  ]
}
```

Generate or refresh the declarative snapshot after changing a local PostgreSQL schema:

```bash
mvn -pl schema-applier exec:java \
  -Dexec.mainClass=com.thinkai.schema.SchemaSerializer \
  -Dexec.args="jdbc:postgresql://localhost:5432/app user password public src/main/resources/schema-definition.json"
```

The serializer preserves the hand-authored `changes` array. It does not attempt to
invent backfills or reconstruct the intent of constraints, functions, and triggers.

### Flyway cutover

Do not blindly mark historical migrations complete. Convert their durable effects into
ordered change sets with `verificationSql`, then test both paths:

- a fresh empty PostgreSQL database applies every change;
- an existing Flyway database adopts each verified change without rerunning it.

Only after both converge to the same schema should the consuming application disable
Flyway. Keep the historical migrations in source control as audit evidence.

### Verification

The normal suite runs unit and Spring Boot auto-configuration tests. The PostgreSQL
acceptance suite is enabled when a test JDBC URL is supplied:

```bash
mvn verify \
  -Dschema.test.jdbc.url=jdbc:postgresql://localhost:5432/postgres \
  -Dschema.test.jdbc.user="$USER"
```

Repository CI always supplies PostgreSQL 16 and therefore never skips this gate.

## Portfolio

Ops infrastructure — not a product. Apps stay separate; this jar is the bridge.
