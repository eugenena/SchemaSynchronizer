# SchemaSynchronizer

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![Java](https://img.shields.io/badge/Java-17%2B-orange.svg)](https://www.oracle.com/java/)
[![Maven Central](https://img.shields.io/maven-central/v/com.thinkaillc/schema-synchronizer.svg)](https://central.sonatype.com/artifact/com.thinkaillc/schema-synchronizer)
[![Javadocs](https://javadoc.io/badge2/com.thinkaillc/schema-synchronizer/javadoc.svg)](https://javadoc.io/doc/com.thinkaillc/schema-synchronizer)

SchemaSynchronizer keeps a relational database aligned with a version-controlled
schema definition. It applies additive and otherwise safe changes automatically,
while returning destructive or ambiguous changes as SQL for a human operator to
review.

The project and repository are named **SchemaSynchronizer**; the published Maven
artifacts are `com.thinkaillc:schema-synchronizer` (library) and
`com.thinkaillc:schema-synchronizer-cli` (command-line tools), and the Java package is
`com.thinkaillc.schemasynchronizer`.

Use it as:

- two command-line utilities that serialize a source schema and synchronize a target
  (plus `validate` and `dry-run` for hand-authored definitions);
- a Spring Boot database initializer that runs before JPA validation; or
- a small Java API over an existing JDBC connection.

Applications typically **hand-author** `schema-definition.json` in source control and
apply it to each environment's database on startup. Serializing a live database is
optional bootstrap. See [Hand-authoring](docs/HAND_AUTHORING.md).

## Start here

| Goal | Guide |
|---|---|
| Add SchemaSynchronizer to a Spring Boot application | [Five-minute Spring Boot quick start](docs/QUICK_START_SPRING_BOOT.md) |
| Hand-author `schema-definition.json` (normal app workflow) | [Hand-authoring guide](docs/HAND_AUTHORING.md) |
| Serialize or synchronize a database from the command line | [CLI guide](docs/CLI.md) |
| Understand or author `schema-definition.json` | [Schema definition reference](docs/SCHEMA_DEFINITION.md) |
| Prepare a production deployment | [Operations and safety runbook](docs/OPERATIONS.md) |
| Move an application away from Flyway or another migration tool | [Migration guide](docs/MIGRATING_FROM_MIGRATIONS.md) |
| Diagnose an error or unexpected pending SQL | [Troubleshooting](docs/TROUBLESHOOTING.md) |
| Understand database-specific behavior | [Database dialects](docs/DATABASE_DIALECTS.md) |
| Understand the safety model (auto vs pending vs change sets) | [Design](docs/DESIGN.md) |
| See what is planned next | [Roadmap](docs/ROADMAP.md) |
| Add support for another relational database | [Dialect contribution guide](docs/CONTRIBUTING_A_DIALECT.md) |

The [Javadocs](https://javadoc.io/doc/com.thinkaillc/schema-synchronizer) cover the
public Java API. See [CONTRIBUTING.md](CONTRIBUTING.md) for the development workflow
and [SECURITY.md](SECURITY.md) for private vulnerability reporting and the security scope.
Upgrading from 1.2.0? Read [Upgrading from 1.2.0](CHANGELOG.md#upgrading-from-120) first:
2.0.0 contains breaking changes.

## Current dialect support

| Database | Status | Notes |
|---|---|---|
| PostgreSQL 16+ | Available | Transactional DDL and advisory locking |
| MariaDB 10.3+ | Available | Named locking; DDL may commit implicitly |
| MySQL 8.0+ | Available | Separate dialect; named locking and implicit DDL commits |
| SQL Server 2019+ | Available | Transactional DDL and `sp_getapplock` |
| Oracle 19c+ | Available | `DBMS_LOCK`; DDL may commit implicitly. CI tests Oracle XE 21c; 19c is supported by design but not CI-tested |
| Percona Server 8.4 | Compatible | Uses the MySQL dialect; verified with `scripts/verify-mysql-compatible.sh`, not CI-tested |
| TiDB 8.5 LTS | Partial | Uses the MySQL dialect for tables, columns, and indexes. TiDB lacks triggers, stored functions, `INVISIBLE` columns, and other DDL the MySQL suite covers, so 2.0.0 is not verified on TiDB; not CI-tested |

CI runs the full integration suite on PostgreSQL 16, MariaDB 10.11, MySQL 8.4, SQL Server 2022,
and Oracle XE 21c, plus the engine suites on the version floors MariaDB 10.3, MySQL 8.0, and
SQL Server 2019, and on MariaDB 11.8.

PostgreSQL, MariaDB, MySQL, SQL Server, and Oracle are the current dialect implementations.
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
   triggers, comments, and grants. Extensions are installed outside change sets.

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

## Quick start: Spring Boot

SchemaSynchronizer requires Java 17 or later. Add the Maven Central release:

```xml
<dependency>
  <groupId>com.thinkaillc</groupId>
  <artifactId>schema-synchronizer</artifactId>
  <version>2.0.0</version>
</dependency>
```

Put `schema-definition.json` in `src/main/resources`, set
`spring.jpa.hibernate.ddl-auto=validate`, and start the application. Safe differences
are applied before Hibernate validates the schema; unsafe differences stop startup
and are printed as SQL for operator review.

Follow the [five-minute quick start](docs/QUICK_START_SPRING_BOOT.md) for a complete
working definition and configuration.

Connect as a **schema-scoped, least-privilege database account**, never a superuser, `sa`,
`root`, or `SYSTEM`. The change-set SQL checks catch mistakes; they are not a security
boundary. Per-engine grants: [Least-privilege database account](docs/OPERATIONS.md#least-privilege-database-account-required).

## Quick start: command line

The self-contained CLI requires Java 17 or later. It includes the PostgreSQL, MariaDB, MySQL,
SQL Server, and Oracle JDBC drivers, so the standalone JAR carries their licenses as well as
SchemaSynchronizer's Apache-2.0 license: MySQL Connector/J (GPLv2 with the Universal FOSS
Exception), MariaDB Connector/J (LGPL-2.1-or-later), Oracle ojdbc11 (Oracle Free Use Terms
and Conditions), PostgreSQL JDBC (BSD-2-Clause), Microsoft JDBC Driver for SQL Server (MIT),
Protocol Buffers (BSD-3-Clause), Jackson (Apache-2.0), SLF4J (MIT), and Checker Framework
qualifiers (MIT). The JAR contains `LICENSE`, `NOTICE`, and
`META-INF/licenses/THIRD-PARTY.txt` plus each dependency's license text under
`META-INF/licenses/<artifact>/`. Review these terms before redistributing the JAR. The
library artifact `schema-synchronizer` bundles no third-party code.

Connect as a schema-scoped, least-privilege account; see
[Least-privilege database account](docs/OPERATIONS.md#least-privilege-database-account-required).

### 1. Download the executable JAR

```bash
curl -fLO https://repo1.maven.org/maven2/com/thinkaillc/schema-synchronizer-cli/2.0.0/schema-synchronizer-cli-2.0.0-standalone.jar
java -jar schema-synchronizer-cli-2.0.0-standalone.jar --version
```

Maven Central publishes `.sha256` and `.sha512` files beside the JAR for integrity
verification.

### 2. Serialize a source database

Set the password in the environment so it does not appear in shell history or the
process list:

```bash
export SCHEMA_DB_PASSWORD='source-password'
```

PostgreSQL example:

```bash
java -jar schema-synchronizer-cli-2.0.0-standalone.jar serialize \
  jdbc:postgresql://localhost:5432/source_app app_user - public schema-definition.json
```

MariaDB example (the schema argument is the database/catalog name):

```bash
java -jar schema-synchronizer-cli-2.0.0-standalone.jar serialize \
  jdbc:mariadb://localhost:3306/source_app app_user - source_app schema-definition.json
```

MySQL uses the same argument shape with a `jdbc:mysql:` URL. Its serialized
definition declares `"dialect": "mysql"`; MariaDB and MySQL definitions are not
interchanged implicitly.

SQL Server example (the schema argument must be the login's `DEFAULT_SCHEMA`, here `app`;
use a dedicated login, never `sa`):

```bash
java -jar schema-synchronizer-cli-2.0.0-standalone.jar serialize \
  "jdbc:sqlserver://localhost:1433;databaseName=app;encrypt=true;trustServerCertificate=true" \
  schema_sync - app schema-definition.json
```

Keep `encrypt=true`. `trustServerCertificate=true` skips certificate validation and is only
for local or test servers with self-signed certificates; in production, trust the server's
certificate (for example with `trustStore=` or a CA-issued certificate) and drop it.

Oracle example (schema argument is the Oracle user/schema):

```bash
java -jar schema-synchronizer-cli-2.0.0-standalone.jar serialize \
  jdbc:oracle:thin:@localhost:1521/XEPDB1 \
  app_user - app_user schema-definition.json
```

Percona Server and TiDB use the MySQL dialect and Connector/J URL shape. Percona's release
contract can be reproduced with `bash scripts/verify-mysql-compatible.sh`
(`VERIFY_TIDB=1` also runs the suite against TiDB, where the trigger, routine, and
`INVISIBLE`-column cells fail); compatibility does not imply support for vendor extensions
outside SchemaSynchronizer's documented schema model.

Review the generated file before committing it. If the file already exists, the
serializer preserves its hand-authored `changes` array.

### 3. Synchronize a target database

```bash
export SCHEMA_DB_PASSWORD='target-password'
```

PostgreSQL example:

```bash
java -jar schema-synchronizer-cli-2.0.0-standalone.jar sync \
  jdbc:postgresql://localhost:5432/target_app app_user - \
  schema-definition.json public schema_synchronizer_history
```

MariaDB example:

```bash
java -jar schema-synchronizer-cli-2.0.0-standalone.jar sync \
  jdbc:mariadb://localhost:3306/target_app app_user - \
  schema-definition.json target_app schema_synchronizer_history
```

Preview first with `dry-run` (same arguments as `sync`), and check a hand-edited file offline
with `validate schema-definition.json [schema]`. On SQL Server, Oracle, MySQL, and MariaDB pass
the schema argument explicitly: the default `public` is rejected there.

The database account must be able to read catalog metadata and execute the DDL in
the definition, and nothing more; see
[Least-privilege database account](docs/OPERATIONS.md#least-privilege-database-account-required).
The process exits with an error when manual work is required.

## Add the library to an application

Add the Maven Central release to an application:

```xml
<dependency>
  <groupId>com.thinkaillc</groupId>
  <artifactId>schema-synchronizer</artifactId>
  <version>2.0.0</version>
</dependency>
```

The library declares PostgreSQL, MariaDB, MySQL, SQL Server, and Oracle JDBC drivers as
**optional** dependencies. Add the driver for the engine you use (or rely on Spring Boot /
your app's existing JDBC dependency). The standalone CLI JAR still embeds all five drivers.

## Release process for maintainers

GitHub Actions is optional. A release can be published locally after the
`com.thinkaillc` namespace is verified in Central Portal:

1. Import the release PGP private key and publish its public key.
2. Put the Central Portal user-token username and password under server id
   `central` in Maven `settings.xml`.
3. Export the signing-key passphrase as `MAVEN_GPG_PASSPHRASE`.
4. Create a signed `v<project-version>` Git tag on the reviewed release commit.
5. From that tag, run `mvn --batch-mode --no-transfer-progress -Prelease clean deploy`. The
   command validates, publishes, and waits for Central to report the deployment as
   published.
6. Create the GitHub release from the same signed tag and attach
   `schema-synchronizer-cli-<version>-standalone.jar` plus its SHA-256 and SHA-512 files.

The release profile attaches source and Javadoc archives, signs every artifact,
generates MD5, SHA-1, SHA-256, and SHA-512 checksums, and publishes a Central Portal
bundle. Central releases are immutable; never reuse a published version number.

## CLI reference

The standalone JAR has one entry point with six commands:

```text
java -jar schema-synchronizer-cli-2.0.0-standalone.jar serialize <jdbc-url> <user> - <schema> <output-path>
java -jar schema-synchronizer-cli-2.0.0-standalone.jar sync      <jdbc-url> <user> - <schema-file> [schema] [history-table]
java -jar schema-synchronizer-cli-2.0.0-standalone.jar dry-run   <jdbc-url> <user> - <schema-file> [schema] [history-table]
java -jar schema-synchronizer-cli-2.0.0-standalone.jar validate  <schema-file> [schema]
java -jar schema-synchronizer-cli-2.0.0-standalone.jar help      (also --help, -h)
java -jar schema-synchronizer-cli-2.0.0-standalone.jar version   (also --version, -V)
```

Exit codes: `0` success, `1` the command failed (including pending manual SQL), `2` unknown
command or wrong argument count. A failure is printed as one `SchemaSynchronizer failed: …`
line without a stack trace, with `password=`/`pwd=`-style values and URL credentials masked
as `****`.

| Argument | Description |
|---|---|
| `jdbc-url` | JDBC URL for an available dialect |
| `user` | Database username (a least-privilege account) |
| `-` | Required password placeholder; the password is read from `SCHEMA_DB_PASSWORD`. Literal passwords are rejected |
| `schema` | Dialect-specific schema namespace: `public` (PostgreSQL), the login's default schema (SQL Server), the connected user (Oracle), or the database name (MySQL/MariaDB). Defaults to `public`, which is rejected on SQL Server, Oracle, MySQL, and MariaDB |
| `output-path` | Definition file to create or update (`serialize`) |
| `schema-file` | Definition to apply, preview, or validate |
| `history-table` | Change-set ledger table; default `schema_synchronizer_history` |

Optional `SCHEMA_SYNCHRONIZER_ACTOR` is stored in history `applied_by`.

### serialize

Reads a live database through JDBC and writes a target-state document. It captures tables,
columns, primary keys, defaults, nullability, and indexes. PostgreSQL `UNIQUE` constraints
are emitted as equivalent unique indexes, preserving enforcement on the target. PostgreSQL
`EXCLUDE` constraints cannot be represented as ordinary indexes, so serialization stops with
an actionable error instead of silently weakening the target schema; express them in the
`changes` array. The serializer does not invent changes for backfills, foreign keys, check
constraints, functions, triggers, extensions, comments, or grants. An existing file keeps its
hand-authored `changes` array.

### sync

Applies a definition to a target database: safe differences and unapplied change sets are
executed; destructive differences are printed as SQL and the command exits `1`.

### dry-run

Plans the same work as `sync` without executing DDL, change sets, or `verificationSql`.
PostgreSQL and SQL Server also roll back control work; MariaDB, MySQL, and Oracle only skip
execution. It still connects, reads history, and runs the change-set checks.

### validate

Checks a definition offline (no database connection): structure, identifiers, dialect, and the
change-set SQL checks, applied to every change set as for an empty database.

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

Set `schema` to the dialect namespace: `public` (PostgreSQL), `dbo` (SQL Server),
the connected user (Oracle), or the catalog/database name (MySQL/MariaDB).

| Property | Default | Meaning |
|---|---|---|
| `enabled` | `false` | Opt-in auto-configuration (must set `true`) |
| `resource` | `/schema-definition.json` | Classpath definition to load |
| `schema` | `public` | Dialect-specific schema namespace |
| `history-table` | `schema_synchronizer_history` | Applied change-set ledger |
| `advisory-lock-id` | `7249031147` | Legacy lock key that 1.x instances used (PostgreSQL advisory key, Oracle `DBMS_LOCK` id); still acquired for rolling upgrades. The main lock is derived from the schema and history table. |
| `dry-run` | `false` | Plan changes; roll back when `supportsTransactionalDryRun` |
| `fail-on-pending` | `true` | Stop when manual SQL remains |
| `require-definition` | `true` | Stop when the classpath definition is absent |

SchemaSynchronizer registers as a database initializer and completes before JPA
schema validation. Keep `ddl-auto=validate`; do not let JPA and SchemaSynchronizer
both mutate the schema.

Dry-run always skips DDL execution and `verificationSql`. Dialects where
`supportsTransactionalDryRun()` is true (PostgreSQL, SQL Server) also roll back
transactional control work. MariaDB, MySQL, and Oracle only skip execution — use a
disposable instance for any rehearsal that must touch the live catalog.

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
        7_249_031_147L,                   // legacy lock ID (keep the 1.x value for rolling upgrades)
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
`dryRun()` is true when nothing was changed; in that case the counts are what would be
applied, so check it before `changed()`. A dry run against an empty database (no history
table yet) succeeds and reports every change set and table it would create.

`lockReleased()` is false when releasing a session-level synchronization lock failed after
the outcome was decided. The work is still committed, but the session may keep the lock until
the connection closes, so close or discard that connection. `cleanupWarnings()` lists those
release failures, and failures to restore the connection's auto-commit mode, as
`"ExceptionType: message"`. PostgreSQL locks are transaction-scoped and always report `true`.

The synchronization lock is derived from the schema and history table on every engine.
`advisoryLockId` only selects the additional lock that 1.x releases took, which 2.0.0 still
acquires so that a rolling upgrade excludes older instances; keep it equal to the value they
used. Because that lock is still taken, synchronizers that share an `advisoryLockId` on
PostgreSQL or Oracle, or share a schema on MySQL, MariaDB, or SQL Server, run one at a time,
as in 1.2.0. Give unrelated products distinct `advisoryLockId` values on PostgreSQL or Oracle
if they must run concurrently.

#### Exceptions

The public methods throw unchecked exceptions; nothing declares `throws Exception`. Every
failure is a `SchemaSynchronizationException`; catch the subclass that matches the decision you
need to make:

| Exception | Meaning | Retry? |
|---|---|---|
| `SchemaLockUnavailableException` | Another synchronizer held the lock for 30 seconds. Nothing was changed. | Yes, later |
| `SchemaDefinitionException` | The definition is invalid, a change set violates the SQL policy, an applied change set was edited, the live schema conflicts with the declaration, or pending manual work exists while `failOnPending` is set. | No; fix the definition or the database |
| `SchemaDatabaseException` | The database rejected a statement or the connection failed. `getCause()` is the driver's `SQLException`; `getSQLState()` and `getErrorCode()` delegate to it. | Depends on the SQLState |

```java
try {
    synchronizer.synchronizeWithResult(connection, definition);
} catch (SchemaLockUnavailableException busy) {
    // another deployment is synchronizing this schema; retry after it finishes
} catch (SchemaDefinitionException invalid) {
    // fail the deployment: the definition or live schema needs a human
} catch (SchemaDatabaseException database) {
    log.error("database failure, SQLState {}", database.getSQLState(), database);
}
```

Any other `RuntimeException` escaping the API is a defect in the library. On MySQL, MariaDB,
and Oracle the connection must have `autoCommit=true`, because their first DDL statement would
commit the caller's open transaction; a connection with `autoCommit=false` is rejected before
any work.

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

`verificationSql` must be a read-only `SELECT`/`WITH` query that returns exactly one row
whose first column is non-null and readable as a boolean (`TRUE`/`FALSE`, or `1`/`0` on
engines without a boolean type); additional columns are ignored, and zero rows, several
rows, or `NULL` fail the sync. It verifies a fresh application and can adopt an
already-existing change only when the database proves the expected object or state exists.
Any function called by a verification query must be side-effect-free.

Each item in `statements` must contain exactly one JDBC statement. Definitions are
trusted application artifacts, not untrusted user input: the SQL checks catch accidental
destructive DDL, but they are not a security boundary. A change-set author can reach
anything the database account can reach; see [SECURITY.md](SECURITY.md#scope-the-sql-checks-are-not-a-security-boundary).

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
work remains unresolved.

Declared names are plain identifiers, folded like unquoted SQL names, and always emitted
quoted, so reserved words such as `order` work; see
[Reserved words and identifier case](docs/SCHEMA_DEFINITION.md#reserved-words-and-identifier-case).

## Moving from a migration tool

Do not blindly mark historical migrations complete. Represent durable effects that
are outside `tables` as ordered change sets with `verificationSql`, then test both:

1. an empty database applies the complete definition; and
2. an existing database adopts verified historical changes without rerunning them.

The empty-database path is stricter: when the history table does not exist yet, every
change set is checked against the current SQL rules, including change sets an existing
database recorded long ago. A historical change set that uses a now-rejected statement
passes on existing databases and fails on a fresh one. Run `validate` (it applies the
empty-database checks) and a sync against an empty database in CI.

Both paths must converge to the same schema before disabling the previous migration
tool. Preserve historical migration files in source control as audit evidence.

## Testing and development

Run the unit tests (no database needed):

```bash
mvn verify
```

The integration suites run only when their properties are set; otherwise they are skipped.
Pass `-Dschema.test.require.live=true` to turn a missing property into a failure instead of a
skip (CI does this). With it set, every engine's properties must be present, or run one suite
with `-pl schema-synchronizer -Dtest=<Suite>`.

| Engine | Properties |
|---|---|
| PostgreSQL | `schema.test.jdbc.url`, `schema.test.jdbc.user`, `schema.test.jdbc.password` |
| MariaDB | `schema.test.mariadb.jdbc.url`, `.user`, `.password`, `.admin.user`, `.admin.password` (an account that can create databases, for the identifier-quoting suite) |
| MySQL | `schema.test.mysql.jdbc.url`, `.user`, `.password`, `.admin.user`, `.admin.password` (an account that can create databases and users; used by the identifier-quoting suite and the least-privilege convergence test) |
| SQL Server | `schema.test.sqlserver.jdbc.url`, `.user`, `.password` |
| Oracle | `schema.test.oracle.jdbc.url`, `.user`, `.password`, `.admin.user`, `.admin.password` (for example `sys as sysdba`; creates per-run users and grants `DBMS_LOCK`); optional `schema.test.oracle.jdbc.schema` (defaults to the connected user) |

Example with all five engines:

```bash
mvn verify -Dschema.test.require.live=true \
  -Dschema.test.jdbc.url=jdbc:postgresql://localhost:5432/postgres \
  -Dschema.test.jdbc.user=ss -Dschema.test.jdbc.password="$PG_PASSWORD" \
  -Dschema.test.mariadb.jdbc.url=jdbc:mariadb://localhost:3306/test \
  -Dschema.test.mariadb.jdbc.user=test_user -Dschema.test.mariadb.jdbc.password="$MARIADB_PASSWORD" \
  -Dschema.test.mariadb.jdbc.admin.user=root -Dschema.test.mariadb.jdbc.admin.password="$MARIADB_ROOT_PASSWORD" \
  -Dschema.test.mysql.jdbc.url=jdbc:mysql://localhost:3307/test \
  -Dschema.test.mysql.jdbc.user=test_user -Dschema.test.mysql.jdbc.password="$MYSQL_PASSWORD" \
  -Dschema.test.mysql.jdbc.admin.user=root -Dschema.test.mysql.jdbc.admin.password="$MYSQL_ROOT_PASSWORD" \
  "-Dschema.test.sqlserver.jdbc.url=jdbc:sqlserver://localhost:1433;encrypt=true;trustServerCertificate=true" \
  -Dschema.test.sqlserver.jdbc.user=sa -Dschema.test.sqlserver.jdbc.password="$MSSQL_PASSWORD" \
  -Dschema.test.oracle.jdbc.url=jdbc:oracle:thin:@localhost:1521/XEPDB1 \
  -Dschema.test.oracle.jdbc.user=schema_sync -Dschema.test.oracle.jdbc.password="$ORACLE_PASSWORD" \
  "-Dschema.test.oracle.jdbc.admin.user=sys as sysdba" -Dschema.test.oracle.jdbc.admin.password="$ORACLE_SYS_PASSWORD"
```

The test suites create and drop their own schemas, so they use administrative accounts on
disposable local containers; that is not a template for production accounts. The PostgreSQL
properties also drive the CLI end-to-end test in `schema-synchronizer-cli`. Percona Server is
checked with `bash scripts/verify-mysql-compatible.sh` (TiDB with `VERIFY_TIDB=1`). The release build
(`mvn -Prelease -Dgpg.skip=true -DskipTests package`) followed by
`scripts/check-standalone-licenses.sh` checks the license files in the standalone JAR.

## Author

SchemaSynchronizer was created by [Eugene Naoumov](https://github.com/eugenena),
is maintained by Eugene and ThinkAI LLC, and is copyright ThinkAI LLC.

## License

SchemaSynchronizer is licensed under the [Apache License 2.0](LICENSE). You may
use, modify, and distribute it in open-source or proprietary software subject to
the license terms. Contributions submitted for inclusion in this repository are
accepted under the same license unless explicitly stated otherwise.
