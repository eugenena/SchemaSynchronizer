# SchemaSynchronizer

Safe schema convergence for relational databases. PostgreSQL 16+ is the first and
currently implemented dialect; the public API and project identity are intentionally
database-neutral so additional dialects can be added without another rebrand.

## Modules

| Module | Artifact | Purpose |
|--------|----------|---------|
| **schema-synchronizer** | `io.github.eugenena:schema-synchronizer` | Apply non-destructive schema diffs from `schema-definition.json` on startup |

## schema-synchronizer contract

SchemaSynchronizer has two complementary layers:

1. `tables` is a serialized target-state snapshot. It converges tables, columns,
   defaults, safe type widenings, nullability relaxations, and indexes.
2. `changes` is an ordered, checksummed ledger for changes that cannot be inferred
   from column metadata: data backfills, foreign/check/unique constraints, functions,
   triggers, extensions, comments, and grants. Changes default to `BEFORE_SCHEMA`;
   use `AFTER_SCHEMA` for constraints, functions, and triggers that depend on tables
   from the declarative snapshot.

Every invocation is transactional and guarded by a PostgreSQL advisory lock. Applied
change IDs and SHA-256 checksums are stored in `schema_synchronizer_history`. Editing an
applied change fails startup. A change may include a read-only `verificationSql` query;
this both verifies a fresh application and safely adopts an already-existing Flyway
change only when PostgreSQL proves the expected object is present.

Definitions and change sets are trusted application artifacts, not untrusted user
input. The SQL policy rejects destructive and unsupported statement shapes, but it is
not a SQL sandbox. In particular, authors must ensure functions invoked by
`verificationSql` are side-effect-free.

**Auto-apply:** create table, add column, create index, SET/DROP DEFAULT, safe type
widenings, `NOT NULL` → nullable, and safety-checked ordered change sets.

**Rejected or pending:** drop table/column/index/schema/constraint, truncate, delete,
arbitrary type changes, type narrowing, and inferred `NULL` → `NOT NULL`. A versioned
change set may set `NOT NULL` after an explicit backfill. Unknown SQL is rejected.

SchemaSynchronizer never executes destructive or unsafe reconciliation. It returns the
ordered statements in `SchemaSynchronizationResult.pendingSql()` and prints a copyable manual
transaction (`BEGIN`, schema-scoped `search_path`, statements, `COMMIT`) for an operator
to review and run separately. Set `schema-synchronizer.fail-on-pending=false` only when the
application may start while that manual work remains outstanding.

The default is fail-closed: a missing definition, checksum drift, failed verification,
or pending destructive schema difference aborts startup.

Current portability boundary: PostgreSQL 16+ with unquoted, lower-case identifiers.
MariaDB snapshot serialization is available as the first step toward MariaDB
synchronization; MariaDB target execution remains fail-closed until its DDL, locking,
and non-transactional recovery semantics are implemented. Quoted/mixed-case identifiers
are rejected rather than handled approximately.

Schema definitions declare both `formatVersion` and `dialect`. A definition can only be
applied to the same database family that produced it; SchemaSynchronizer is not a
cross-database migration or SQL-translation tool. Legacy definitions without those
fields are interpreted as PostgreSQL format version 1.

## Install locally

```bash
cd SchemaSynchronizer
mvn clean install
```

CI publishes tagged builds and manually dispatched builds to GitHub Packages at
`https://maven.pkg.github.com/eugenena/SchemaSynchronizer`. Consumers outside the local
machine must configure that repository and a GitHub Packages credential in Maven
settings, then pin the published version rather than relying on a mutable local JAR.

Consuming application:

```xml
<dependency>
  <groupId>io.github.eugenena</groupId>
  <artifactId>schema-synchronizer</artifactId>
  <version>0.2.0-SNAPSHOT</version>
</dependency>
```

Spring Boot auto-configuration runs SchemaSynchronizer after the DataSource exists and
registers it as a database initializer so JPA validation waits for it. Configuration:

```properties
schema-synchronizer.enabled=true
schema-synchronizer.resource=/schema-definition.json
schema-synchronizer.schema=public
schema-synchronizer.history-table=schema_synchronizer_history
schema-synchronizer.advisory-lock-id=7249031147
schema-synchronizer.dry-run=false
schema-synchronizer.fail-on-pending=true
schema-synchronizer.require-definition=true
spring.jpa.hibernate.ddl-auto=validate
```

Example definition (statements are intentionally one JDBC statement per entry):

```json
{
  "tables": {
    "work_items": {
      "createSql": "CREATE TABLE IF NOT EXISTS work_items (id UUID PRIMARY KEY, status VARCHAR(32))",
      "columns": [
        {"name": "id", "definition": "UUID NOT NULL"},
        {"name": "status", "definition": "VARCHAR(32)"}
      ],
      "indexes": []
    }
  },
  "changes": [
    {
      "id": "001-work-item-status",
      "description": "Backfill and constrain work item status",
      "statements": [
        "UPDATE work_items SET status = 'PENDING' WHERE status IS NULL",
        "ALTER TABLE work_items ALTER COLUMN status SET NOT NULL",
        "ALTER TABLE work_items ADD CONSTRAINT ck_work_item_status CHECK (status IN ('PENDING', 'COMPLETE'))"
      ],
      "verificationSql": "SELECT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_work_item_status' AND conrelid = to_regclass('work_items'))",
      "phase": "AFTER_SCHEMA"
    }
  ]
}
```

### Standalone utilities

Serialize a source database to a schema definition:

```bash
SCHEMA_DB_PASSWORD='local-password' mvn -pl schema-synchronizer exec:java \
  -Dexec.mainClass=io.github.eugenena.schemasynchronizer.SchemaSerializer \
  -Dexec.args="jdbc:postgresql://localhost:5432/app user - public src/main/resources/schema-definition.json"
```

The snapshot writer preserves the hand-authored `changes` array. It does not attempt to
invent backfills or reconstruct the intent of constraints, functions, and triggers.

Synchronize a target database from the serialized definition:

```bash
SCHEMA_DB_PASSWORD='target-password' mvn -pl schema-synchronizer exec:java \
  -Dexec.mainClass=io.github.eugenena.schemasynchronizer.SchemaSynchronizer \
  -Dexec.args="jdbc:postgresql://localhost:5432/target target_user - schema-definition.json public schema_synchronizer_history"
```

Both utilities accept the password directly in the third argument, but `-` plus
`SCHEMA_DB_PASSWORD` is recommended so credentials do not appear in the process list or
shell history. The synchronizer fails closed if the definition is missing, verification
fails, or destructive/unsafe differences require manual execution.

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
