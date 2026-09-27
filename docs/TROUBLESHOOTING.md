# Troubleshooting

Error messages below are quoted literally; `<id>` is a change-set id and `<dialect>` a dialect
id such as `postgresql`.

## Schema synchronization stopped running after upgrading to 2.0.0

`[SchemaSynchronizer] /schema-definition.json is on the classpath but schema-synchronizer.enabled is not set, so schema synchronization is OFF (it is opt-in since 2.0.0).`

Spring Boot auto-configuration is opt-in from 2.0.0. Set `schema-synchronizer.enabled=true`.
Without it nothing runs, even with a definition on the classpath and other
`schema-synchronizer.*` properties set. Set it to `false` to opt out and silence the warning. See
[Upgrading from 1.2.0](../CHANGELOG.md#upgrading-from-120).

## A change set that worked in 1.2.0 is rejected

2.0.0 checks change-set SQL more strictly. Typical messages:

- `unsupported schema change SQL: …` or `destructive or manually-reviewed SQL is not allowed: …`:
  the statement is outside the allowlist (for example `CREATE EXTENSION`, `CREATE SEQUENCE`,
  `CREATE VIEW`, a `DROP`, or a statement that reaches another schema).
- `… definitions must not use schema 'public'; pass dbo / catalog / connected user`: SQL Server,
  Oracle, MySQL, and MariaDB definitions need their real namespace.
- `only LANGUAGE sql and plpgsql bodies can be checked; …`: a PostgreSQL routine in another
  language must be created outside SchemaSynchronizer.
- `unsupported schema change SQL: CREATE VIEW is not yet supported in change sets; create this
  object outside SchemaSynchronizer (see docs/ROADMAP.md): …` (also `SEQUENCE`, `PROCEDURE`,
  `PACKAGE`, `TYPE`): these object types are planned in
  [ROADMAP](ROADMAP.md#change-set-support-for-more-object-types). Use an identity column
  instead of a sequence, or create the object in provisioning.

A change set **already recorded** in history is not re-checked, so existing databases keep
working. The same change set fails on an **empty database**, where every change set is checked.
Do not edit a recorded change set (that breaks its checksum). Create the object outside
SchemaSynchronizer (for example in provisioning) and have a new change set's `verificationSql`
confirm it, then test with `validate` and against an empty database. See
[Upgrading from 1.2.0](../CHANGELOG.md#upgrading-from-120).

## Handling failures from the Java API

2.0.0 methods throw unchecked `SchemaSynchronizationException` subclasses instead of declaring
`throws Exception`. Decide by type, not by message text:

- `SchemaLockUnavailableException`: another instance held the lock for 30 seconds and nothing
  was changed. Retry after it finishes (see [The synchronization lock is busy](#the-synchronization-lock-is-busy)).
- `SchemaDefinitionException`: the definition, a change set, or the live schema needs a
  human. Retrying gives the same result; read the message and fix the definition or database.
- `SchemaDatabaseException`: the driver failed. `getSQLState()`, `getErrorCode()`, and
  `getCause()` (the original `SQLException`) tell whether a retry can help, for example after
  a dropped connection.

Code that relied on checked `SQLException` or `IOException` must catch these instead. A
result with `lockReleased() == false` is not a failure: the work is committed, but close or
discard the connection, and see `cleanupWarnings()` for the cause. See
[Java API](../README.md#exceptions).

## A qualified column type is rejected or pending

`schema-qualified column types are supported only on PostgreSQL: <table>.<column> …` means a
MySQL, MariaDB, SQL Server, or Oracle definition declares `schema.type`; remove the
qualifier. On PostgreSQL, `declared type is in schema <x> but the live type is in <y>` means
the qualifier does not name the schema the type is installed in. For pgvector installed in
`public` with another synchronized schema, declare `public.vector(n)`; see
[Extension types in another schema](SCHEMA_DEFINITION.md#extension-types-in-another-schema).

## The definition was not found

`Required schema definition is missing from classpath: /schema-definition.json`

The Spring integration loads `/schema-definition.json` by default and fails closed.
Confirm the file is under `src/main/resources`, is included in the built JAR, and
matches `schema-synchronizer.resource`. Disabling `require-definition` is appropriate
only when an intentionally optional definition is part of the application design.

## The declared dialect does not match

The live JDBC database and the definition's `dialect` differ. Do not relabel a file
from another database engine. Serialize or author a definition using native SQL for
the target dialect.

## Startup reports pending SQL

SchemaSynchronizer found a destructive or ambiguous difference and refused to
execute it. Follow [OPERATIONS.md](OPERATIONS.md#handling-pending-sql). Common causes
are removed columns, orphan tables or indexes, primary-key drift, type narrowing,
and tightening nullability.

## A change set fails with "already exists"

Before 1.2.0, replaying a multi-statement change against a database that already
had some constraints or tables aborted the whole change. From 1.2.0, duplicate DDL
objects are skipped and `verificationSql` must still pass. If verification fails
after skips, the remaining statements did not establish the postcondition — fix the
definition or the live schema, then retry.

## Hand-authored definition will not start

Run `validate` on the file first. Confirm `formatVersion` 2 includes `dialect`,
change-set ids are stable, and you did not edit an already-applied change set.

## A change-set checksum changed

`checksum mismatch for applied schema change '<id>': committed changes are immutable`

An already-applied change set was edited. Restore the original text and add a new
change set for the next operation. Do not update the stored checksum to hide drift.

## An applied change set is missing

`applied schema change is missing from the immutable ledger: <id>`

History records a change set that the definition no longer contains. Restore it; change sets
are never removed from a definition once applied anywhere.

## The synchronization lock is busy

`Could not acquire <dialect> schema synchronization lock within 30s…; another synchronization of this schema is running`

Another instance is synchronizing the same schema. Retry after it finishes. If none is
running, look for a session that still holds the lock (a hung deployment) and end it.

## Verification SQL is rejected or fails

Messages: `verification SQL must be a SELECT or WITH query`,
`verification query returned no row for schema change: <id>`,
`verification query returned NULL for schema change: <id>`,
`verification query must return exactly one row for schema change: <id>`,
`verification failed after schema change '<id>'`, and on MariaDB/MySQL/Oracle
`unapplied <dialect> schema change requires verificationSql: <id>`.

`verificationSql` must be a read-only `SELECT`/`WITH` query that returns exactly one row whose
first column is non-null and readable as a boolean (`TRUE`/`FALSE`, or `1`/`0` on engines
without a boolean type). Additional columns are ignored. Make the query prove the durable
postcondition. Do not use a function with side effects.

## Spring Boot has multiple data sources

Auto-configuration intentionally backs off because selecting one database would be
ambiguous. Construct a `SchemaSynchronizer` for the intended `DataSource` explicitly
or isolate synchronization in a dedicated configuration.

## Dry run changed MariaDB or MySQL

Dry-run skips statement execution and `verificationSql` on every dialect. Engines
where `supportsTransactionalDryRun()` is false (MariaDB, MySQL, Oracle) do not get
a transactional rollback of control SQL either. Rehearse against a disposable
instance when you need a live catalog preview.

## An externally managed table is reported as orphaned

The declarative layer owns ordinary tables in its configured schema. Move extension-
owned or externally managed objects to another schema, or represent the object in
the definition if SchemaSynchronizer should own it.

## Identifier casing is unexpected

Declared names are folded like unquoted SQL names (upper case on Oracle, lower case
elsewhere) and always emitted quoted. A live object whose name differs only by case fails on
a case-sensitive engine with `live table "Customer" differs from declared table …`. Rename the
live object, or manage it outside the synchronized schema. See
[Reserved words and identifier case](SCHEMA_DEFINITION.md#reserved-words-and-identifier-case).

## The CLI reports `Unable to access jarfile`

Confirm the downloaded filename and working directory, then run the executable JAR
directly. The library JAR and CLI JAR are separate artifacts; use
`schema-synchronizer-cli-<version>-standalone.jar` as described in [CLI.md](CLI.md).

If the problem remains, open a GitHub issue with the database product/version,
SchemaSynchronizer version, redacted definition, full exception, and minimal
reproduction. Never include credentials or production data.
