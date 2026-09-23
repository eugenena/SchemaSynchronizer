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

## Current dialect support

| Database | Status | Notes |
|---|---|---|
| PostgreSQL 16+ | Available | Transactional DDL and advisory locking |
| MariaDB 10.3+ | Available | Named locking; DDL may commit implicitly |
| MySQL 8.0+ | Available | Separate dialect; named locking and implicit DDL commits |
| MySQL | Planned | Detected today, but fails closed until its compatibility suite is complete |

PostgreSQL, MariaDB, and MySQL are the first dialect implementations, not a closed list.
SchemaSynchronizer is designed to add more relational database dialects, each with
its own metadata, SQL-generation, locking, safety, and compatibility behavior.

A definition serialized from one dialect can only be applied to that same dialect;
adding dialects expands the databases SchemaSynchronizer can manage, but does not
turn it into a cross-database SQL translator.

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

## Why SchemaSynchronizer instead of a migration tool?

SchemaSynchronizer and tools such as Flyway solve related problems with different
models. Flyway's classic workflow records incremental, versioned scripts and applies
each pending migration once in version order. SchemaSynchronizer starts from the
desired schema state, inspects the database that actually exists, and converges safe
differences on every run. Explicit change sets complement that desired state when an
operation cannot be inferred safely.

| Concern | SchemaSynchronizer | Versioned migration tools |
|---|---|---|
| Primary source of truth | Current desired schema plus explicit exceptional changes | Ordered history of migration scripts |
| Existing database drift | Compares live metadata and repairs supported safe differences | Normally applies migrations missing from the history table |
| New environment | Applies the current definition and its required change ledger | Replays migrations or starts from a maintained baseline |
| Destructive differences | Produces SQL for human review; never auto-applies them | Executes destructive SQL when an authored migration contains it |
| Routine additive changes | Inferred from the desired state | Require a new migration script or generated migration |
| Historical operations and data changes | Ordered, checksummed change sets | Ordered, checksummed migrations |

SchemaSynchronizer is a good fit when:

- applications should repair supported additive drift at startup or deployment;
- teams prefer maintaining the current schema state over a growing chain of routine
  additive migrations;
- destructive reconciliation must always cross a manual-review boundary; or
- many installations may begin from different safe subsets of the desired schema.

A versioned migration tool remains a better fit when every transition must be
authored and reviewed explicitly, existing deployment infrastructure already relies
on migration versions, or database changes need features outside the currently
implemented dialect contract. The approaches can coexist during adoption; see
[Moving from a migration tool](#moving-from-a-migration-tool).

For comparison, Flyway documents its
[versioned migrations](https://documentation.red-gate.com/fd/versioned-migrations-273973333.html)
as ordered, checksum-tracked migrations applied once. This distinction is about the
operating model, not a claim that one approach is universally better.

## Quick start: command line

SchemaSynchronizer requires Java 21 and Maven 3.9 or later.

### 1. Build and install locally

```bash
git clone https://github.com/eugenena/SchemaSynchronizer.git
cd SchemaSynchronizer
mvn clean install
```

This installs `com.thinkaillc:schema-synchronizer:1.0.0` in your
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
  -Dexec.mainClass=com.thinkaillc.schemasynchronizer.SchemaSerializer \
  -Dexec.args="jdbc:postgresql://localhost:5432/source_app app_user - public schema-definition.json"
```

MariaDB example (the schema argument is the database/catalog name):

```bash
mvn -pl schema-synchronizer exec:java \
  -Dexec.mainClass=com.thinkaillc.schemasynchronizer.SchemaSerializer \
  -Dexec.args="jdbc:mariadb://localhost:3306/source_app app_user - source_app schema-definition.json"
```

MySQL uses the same argument shape with a `jdbc:mysql:` URL. Its serialized
definition declares `"dialect": "mysql"`; MariaDB and MySQL definitions are not
interchanged implicitly.

Review the generated file before committing it. If the file already exists, the
serializer preserves its hand-authored `changes` array.

### 3. Synchronize a target database

```bash
export SCHEMA_DB_PASSWORD='target-password'
```

PostgreSQL example:

```bash
mvn -pl schema-synchronizer exec:java \
  -Dexec.mainClass=com.thinkaillc.schemasynchronizer.SchemaSynchronizer \
  -Dexec.args="jdbc:postgresql://localhost:5432/target_app app_user - schema-definition.json public schema_synchronizer_history"
```

MariaDB example:

```bash
mvn -pl schema-synchronizer exec:java \
  -Dexec.mainClass=com.thinkaillc.schemasynchronizer.SchemaSynchronizer \
  -Dexec.args="jdbc:mariadb://localhost:3306/target_app app_user - schema-definition.json target_app schema_synchronizer_history"
```

The database account must be able to read catalog metadata and execute the DDL in
the definition. The process exits with an error when manual work is required.

## Add the library to an application

Add the Maven Central release to an application:

```xml
<dependency>
  <groupId>com.thinkaillc</groupId>
  <artifactId>schema-synchronizer</artifactId>
  <version>1.0.0</version>
</dependency>
```

The library includes PostgreSQL, MariaDB, and MySQL JDBC drivers at runtime. Applications
can override their versions through dependency management.

## Release process for maintainers

GitHub Actions is optional. A release can be published locally after the
`com.thinkaillc` namespace is verified in Central Portal:

1. Import the release PGP private key and publish its public key.
2. Put the Central Portal user-token username and password under server id
   `central` in Maven `settings.xml`.
3. Export the signing-key passphrase as `MAVEN_GPG_PASSPHRASE`.
4. Run `mvn --batch-mode --no-transfer-progress -Prelease clean deploy`. The
   command validates, publishes, and waits for Central to report the deployment as
   published.
5. Create the signed `v1.0.0` Git tag and GitHub release from the same commit.

The release profile attaches source and Javadoc archives, signs every artifact,
and publishes a Central Portal bundle. Central releases are immutable; never reuse
a published version number.

## CLI reference

### SchemaSerializer

`SchemaSerializer` reads a live database through JDBC and writes a target-state
document:

```text
SchemaSerializer <jdbc-url> <user> <password-or--> <schema> <output-path>
```

| Argument | Description |
|---|---|
| `jdbc-url` | JDBC URL for an available dialect |
| `user` | Database username |
| `password-or--` | Password, or `-` to read `SCHEMA_DB_PASSWORD` |
| `schema` | Dialect-specific schema namespace (a database/catalog in MariaDB and MySQL) |
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
| `schema` | No | `public` | Dialect-specific schema namespace |
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
| `schema` | `public` | Dialect-specific schema namespace |
| `history-table` | `schema_synchronizer_history` | Applied change-set ledger |
| `advisory-lock-id` | `7249031147` | PostgreSQL advisory-lock key |
| `dry-run` | `false` | Plan changes and roll back PostgreSQL work |
| `fail-on-pending` | `true` | Stop when manual SQL remains |
| `require-definition` | `true` | Stop when the classpath definition is absent |

SchemaSynchronizer registers as a database initializer and completes before JPA
schema validation. Keep `ddl-auto=validate`; do not let JPA and SchemaSynchronizer
both mutate the schema.

MariaDB and MySQL DDL can commit implicitly, so `dry-run` cannot provide PostgreSQL-style
rollback guarantees. Validate new definitions against a disposable database first.

## Java API

### Apply a definition using an existing JDBC connection

```java
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thinkaillc.schemasynchronizer.SchemaDefinition;
import com.thinkaillc.schemasynchronizer.SchemaSynchronizationResult;
import com.thinkaillc.schemasynchronizer.SchemaSynchronizer;
import com.thinkaillc.schemasynchronizer.SchemaSynchronizerOptions;

import java.nio.file.Path;
import java.sql.Connection;

ObjectMapper mapper = new ObjectMapper();
SchemaDefinition definition = mapper.readValue(
        Path.of("schema-definition.json").toFile(),
        SchemaDefinition.class);

SchemaSynchronizerOptions options = new SchemaSynchronizerOptions(
        "public",                         // dialect-specific schema namespace
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

The structure is shared by every dialect implementation. For example, MariaDB uses
`"dialect": "mariadb"` and SQL valid for MariaDB. Future dialects will use their
own registered identifier and native SQL. Generate the declarative portion with
`SchemaSerializer` instead of manually translating SQL between databases.

| Top-level field | Description |
|---|---|
| `formatVersion` | Definition format; the current version is `2` |
| `dialect` | Registered dialect identifier; must match the target database |
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

Because MariaDB and MySQL DDL may commit implicitly, every unapplied change set for either dialect must
provide `verificationSql`. If verification is false, the change set must contain
exactly one statement. This lets a retry distinguish “the statement committed but
the history insert failed” from “the statement still needs to run,” without
replaying the first half of a multi-statement operation. Use multiple ordered change
sets when a MariaDB or MySQL operation requires several statements.

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

## Author

SchemaSynchronizer was created and is maintained by
[Eugene Naoumov](https://github.com/eugenena).

## License

SchemaSynchronizer is licensed under the [Apache License 2.0](LICENSE). You may
use, modify, and distribute it in open-source or proprietary software subject to
the license terms. Contributions submitted for inclusion in this repository are
accepted under the same license unless explicitly stated otherwise.
