# SchemaSynchronizer

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![Java](https://img.shields.io/badge/Java-21%2B-orange.svg)](https://www.oracle.com/java/)

SchemaSynchronizer keeps a relational database aligned with a version-controlled
schema definition. It applies additive and otherwise safe changes automatically,
while returning destructive or ambiguous changes as SQL for a human operator to
review.

Use it as:

- two command-line utilities that serialize a source schema and synchronize a target;
- a Spring Boot database initializer that runs before JPA validation; or
- a small Java API over an existing JDBC connection.

## Supported dialects

| Database | Status | Notes |
|---|---|---|
| PostgreSQL 16+ | Supported | Transactional DDL and advisory locking |
| MariaDB 10.3+ | Supported | Named locking; DDL may commit implicitly |
| MySQL | Detected, not yet supported | Fails closed until its compatibility suite is complete |

Each database is an independent dialect. A definition serialized from one dialect
can only be applied to that same dialect; SchemaSynchronizer is not a cross-database
SQL translator.

## How it works

A `schema-definition.json` file has two complementary parts:

1. `tables` describes the desired tables, columns, defaults, nullability, and
   indexes. SchemaSynchronizer compares this state with the live database.
2. `changes` is an ordered, checksummed ledger for operations that cannot be
   inferred reliably from JDBC metadata: backfills, constraints, functions,
   triggers, extensions, comments, and grants.

On each run SchemaSynchronizer detects the database dialect, validates the
definition, obtains a database lock, applies safe changes, and records completed
change sets in `schema_synchronizer_history`. Unsafe or destructive differences are
not executed; they are returned in `pendingSql()` for manual review.

The defaults are fail-closed. A missing definition, dialect mismatch, checksum
drift, failed verification query, or pending destructive change stops startup.

## Quick start: command line

SchemaSynchronizer requires Java 21 and Maven 3.9 or later.

### 1. Build and install locally

```bash
git clone https://github.com/eugenena/SchemaSynchronizer.git
cd SchemaSynchronizer
mvn clean install
```

This installs `io.github.eugenena:schema-synchronizer:0.2.0-SNAPSHOT` in your
local Maven repository.

### 2. Serialize a source database

Set the password in the environment so it does not appear in shell history or the
process list:

```bash
export SCHEMA_DB_PASSWORD='source-password'
```

PostgreSQL example:

```bash
mvn -pl schema-synchronizer exec:java \
  -Dexec.mainClass=io.github.eugenena.schemasynchronizer.SchemaSerializer \
  -Dexec.args="jdbc:postgresql://localhost:5432/source_app app_user - public schema-definition.json"
```

MariaDB example (the schema argument is the database/catalog name):

```bash
mvn -pl schema-synchronizer exec:java \
  -Dexec.mainClass=io.github.eugenena.schemasynchronizer.SchemaSerializer \
  -Dexec.args="jdbc:mariadb://localhost:3306/source_app app_user - source_app schema-definition.json"
```

Review the generated file before committing it. If the file already exists, the
serializer preserves its hand-authored `changes` array.

### 3. Synchronize a target database

```bash
export SCHEMA_DB_PASSWORD='target-password'
```

PostgreSQL example:

```bash
mvn -pl schema-synchronizer exec:java \
  -Dexec.mainClass=io.github.eugenena.schemasynchronizer.SchemaSynchronizer \
  -Dexec.args="jdbc:postgresql://localhost:5432/target_app app_user - schema-definition.json public schema_synchronizer_history"
```

MariaDB example:

```bash
mvn -pl schema-synchronizer exec:java \
  -Dexec.mainClass=io.github.eugenena.schemasynchronizer.SchemaSynchronizer \
  -Dexec.args="jdbc:mariadb://localhost:3306/target_app app_user - schema-definition.json target_app schema_synchronizer_history"
```

The database account must be able to read catalog metadata and execute the DDL in
the definition. The process exits with an error when manual work is required.

## Add the library to an application

Until a release is available from Maven Central, run `mvn clean install` locally
or consume a published build from GitHub Packages:

```xml
<dependency>
  <groupId>io.github.eugenena</groupId>
  <artifactId>schema-synchronizer</artifactId>
  <version>0.2.0-SNAPSHOT</version>
</dependency>
```

For GitHub Packages, add this repository to the consuming POM:

```xml
<repositories>
  <repository>
    <id>github-schema-synchronizer</id>
    <url>https://maven.pkg.github.com/eugenena/SchemaSynchronizer</url>
  </repository>
</repositories>
```

GitHub Packages requires a GitHub credential in Maven `settings.xml`, including
for public packages. Applications should pin a released version instead of relying
on a mutable snapshot.

The library includes PostgreSQL and MariaDB JDBC drivers at runtime. Applications
can override their versions through dependency management.

## CLI reference

### SchemaSerializer

`SchemaSerializer` reads a live database through JDBC and writes a target-state
document:

```text
SchemaSerializer <jdbc-url> <user> <password-or--> <schema> <output-path>
```

| Argument | Description |
|---|---|
| `jdbc-url` | PostgreSQL or MariaDB JDBC URL |
| `user` | Database username |
| `password-or--` | Password, or `-` to read `SCHEMA_DB_PASSWORD` |
| `schema` | PostgreSQL schema or MariaDB database/catalog |
| `output-path` | Definition file to create or update |

The serializer captures tables, columns, primary keys, defaults, nullability, and
indexes. It does not invent changes for backfills, foreign keys, check constraints,
functions, triggers, extensions, comments, or grants. Express those explicitly in
the `changes` array.

### SchemaSynchronizer

`SchemaSynchronizer` applies a definition to a target database:

```text
SchemaSynchronizer <jdbc-url> <user> <password-or--> <schema-file> [schema] [history-table]
```

| Argument | Required | Default | Description |
|---|---:|---|---|
| `jdbc-url` | Yes | — | Target JDBC URL |
| `user` | Yes | — | Database username |
| `password-or--` | Yes | — | Password, or `-` to read `SCHEMA_DB_PASSWORD` |
| `schema-file` | Yes | — | Path to the definition |
| `schema` | No | `public` | PostgreSQL schema or MariaDB database/catalog |
| `history-table` | No | `schema_synchronizer_history` | Change-set ledger table |

Supplying the password directly is supported, but `-` is safer because it avoids
putting the credential in command history and process arguments.

## Spring Boot usage

Add the dependency and put the definition here:

```text
src/main/resources/schema-definition.json
```

Auto-configuration activates when a `DataSource` and Jackson `ObjectMapper` are
available. Recommended settings:

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

| Property | Default | Meaning |
|---|---|---|
| `enabled` | `true` | Enable auto-configuration |
| `resource` | `/schema-definition.json` | Classpath definition to load |
| `schema` | `public` | PostgreSQL schema or MariaDB database/catalog |
| `history-table` | `schema_synchronizer_history` | Applied change-set ledger |
| `advisory-lock-id` | `7249031147` | PostgreSQL advisory-lock key |
| `dry-run` | `false` | Plan changes and roll back PostgreSQL work |
| `fail-on-pending` | `true` | Stop when manual SQL remains |
| `require-definition` | `true` | Stop when the classpath definition is absent |

SchemaSynchronizer registers as a database initializer and completes before JPA
schema validation. Keep `ddl-auto=validate`; do not let JPA and SchemaSynchronizer
both mutate the schema.

MariaDB DDL can commit implicitly, so `dry-run` cannot provide PostgreSQL-style
rollback guarantees. Validate new definitions against a disposable database first.

## Java API

### Apply a definition using an existing JDBC connection

```java
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.eugenena.schemasynchronizer.SchemaDefinition;
import io.github.eugenena.schemasynchronizer.SchemaSynchronizationResult;
import io.github.eugenena.schemasynchronizer.SchemaSynchronizer;
import io.github.eugenena.schemasynchronizer.SchemaSynchronizerOptions;

import java.nio.file.Path;
import java.sql.Connection;

ObjectMapper mapper = new ObjectMapper();
SchemaDefinition definition = mapper.readValue(
        Path.of("schema-definition.json").toFile(),
        SchemaDefinition.class);

SchemaSynchronizerOptions options = new SchemaSynchronizerOptions(
        "public",                         // schema or database/catalog
        "schema_synchronizer_history",   // history table
        7_249_031_147L,                   // PostgreSQL advisory-lock ID
        false,                            // dry run
        true,                             // fail on pending manual SQL
        true);                            // require classpath definition

SchemaSynchronizer synchronizer = new SchemaSynchronizer(
        mapper, null, "", options);

try (Connection connection = dataSource.getConnection()) {
    SchemaSynchronizationResult result =
            synchronizer.synchronizeWithResult(connection, definition);

    System.out.printf(
            "created=%d, columns=%d, altered=%d, changeSets=%d%n",
            result.tablesCreated(),
            result.columnsAdded(),
            result.columnsAltered(),
            result.changeSetsApplied());

    result.pendingSql().forEach(sql ->
            System.err.println("Manual review required: " + sql));
}
```

`plannedSql()` contains SQL selected for the invocation, including a dry run.
`pendingSql()` contains destructive or unsafe reconciliation that was not executed.
`changed()` reports whether any safe table, column, or change-set work occurred.

### Load a definition from the classpath

```java
ObjectMapper mapper = new ObjectMapper();
SchemaSynchronizer synchronizer = new SchemaSynchronizer(
        mapper,
        dataSource,
        "/schema-definition.json",
        SchemaSynchronizerOptions.defaults());

SchemaSynchronizationResult result = synchronizer.synchronizeFromClasspath();
```

The `DataSource` is required by `synchronizeFromClasspath()`. It is not used when
the caller supplies a `Connection` directly.

## Schema definition format

PostgreSQL example:

```json
{
  "formatVersion": 2,
  "dialect": "postgresql",
  "tables": {
    "work_items": {
      "createSql": "CREATE TABLE IF NOT EXISTS work_items (id UUID PRIMARY KEY, status VARCHAR(32))",
      "columns": [
        {"name": "id", "definition": "UUID NOT NULL"},
        {"name": "status", "definition": "VARCHAR(32)"}
      ],
      "indexes": [
        "CREATE INDEX IF NOT EXISTS idx_work_items_status ON work_items (status)"
      ]
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

The same structure is used for MariaDB, with `"dialect": "mariadb"` and SQL valid
for that dialect. Generate the declarative portion with `SchemaSerializer` instead
of manually translating SQL between dialects.

| Top-level field | Description |
|---|---|
| `formatVersion` | Definition format; the current version is `2` |
| `dialect` | `postgresql` or `mariadb`; must match the target |
| `tables` | Declarative desired state keyed by table name |
| `changes` | Ordered ledger of explicit operations |

Legacy definitions without `formatVersion` and `dialect` are interpreted as
PostgreSQL format version 1. New definitions should always declare both fields.

### Change sets

Every change set needs a stable, unique `id`. Once applied, do not edit its
statements: the stored SHA-256 checksum protects history from silent drift. Create
a new change set for subsequent work.

`phase` controls ordering:

- `BEFORE_SCHEMA` is the default and suits preparatory data changes.
- `AFTER_SCHEMA` suits constraints, functions, and triggers that depend on newly
  created tables or columns.

`verificationSql` must be a read-only query whose first column is a boolean. It
verifies a fresh application and can adopt an already-existing change only when the
database proves the expected object or state exists. Any function called by a
verification query must be side-effect-free.

Each item in `statements` must contain exactly one JDBC statement. Definitions are
trusted application artifacts, not untrusted user input: the SQL policy prevents
accidental destructive DDL, but it is not a SQL sandbox.

## Safety model

Automatically applied operations include:

- creating tables;
- adding columns;
- creating indexes;
- setting or dropping defaults;
- supported type widenings;
- relaxing `NOT NULL` to nullable; and
- validated, checksummed change sets.

SchemaSynchronizer never infers or automatically performs:

- dropping tables, columns, indexes, schemas, or constraints;
- truncating or deleting data;
- type narrowing or arbitrary type changes; or
- changing nullable columns to `NOT NULL` without an explicit change set.

For unsafe declarative differences, `pendingSql()` returns the ordered statements
and the logger prints copyable SQL for operator review. PostgreSQL output includes a
transaction and schema-scoped `search_path`. MariaDB prints individually reviewable
statements because its DDL may commit implicitly.

Set `fail-on-pending=false` only when the application may safely start while manual
work remains unresolved. Quoted identifiers are rejected rather than handled
approximately; definitions should use ordinary unquoted identifiers.

## Moving from a migration tool

Do not blindly mark historical migrations complete. Represent durable effects that
are outside `tables` as ordered change sets with `verificationSql`, then test both:

1. an empty database applies the complete definition; and
2. an existing database adopts verified historical changes without rerunning them.

Both paths must converge to the same schema before disabling the previous migration
tool. Preserve historical migration files in source control as audit evidence.

## Testing and development

Run the normal test suite:

```bash
mvn test
```

Run the PostgreSQL acceptance suite:

```bash
mvn verify \
  -Dschema.test.jdbc.url=jdbc:postgresql://localhost:5432/postgres \
  -Dschema.test.jdbc.user="$USER" \
  -Dschema.test.jdbc.password="$SCHEMA_DB_PASSWORD"
```

Run the MariaDB acceptance suite:

```bash
mvn verify \
  -Dschema.test.mariadb.jdbc.url=jdbc:mariadb://localhost:3306/test \
  -Dschema.test.mariadb.jdbc.user=test_user \
  -Dschema.test.mariadb.jdbc.password="$SCHEMA_DB_PASSWORD"
```

## License

SchemaSynchronizer is licensed under the [Apache License 2.0](LICENSE). You may
use, modify, and distribute it in open-source or proprietary software subject to
the license terms. Contributions submitted for inclusion in this repository are
accepted under the same license unless explicitly stated otherwise.
