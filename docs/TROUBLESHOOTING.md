# Troubleshooting

## The definition was not found

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

An already-applied change set was edited. Restore the original text and add a new
change set for the next operation. Do not update the stored checksum to hide drift.

## Verification SQL is rejected

It must be a read-only query returning exactly one row with exactly one non-null
boolean first column. Make the query prove the durable postcondition. Do not use a
function with side effects.

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

Version 1.0.0 supports ordinary unquoted identifiers. Definition table and column
names are normalized to lowercase, while quoted identifiers are rejected. Use
lowercase spelling explicitly so the target object is unambiguous. Rename a quoted
or mixed-case live object, or manage it outside the synchronized schema.

## The CLI reports `Unable to access jarfile`

Confirm the downloaded filename and working directory, then run the executable JAR
directly. The library JAR and CLI JAR are separate artifacts; use
`schema-synchronizer-cli-<version>-standalone.jar` as described in [CLI.md](CLI.md).

If the problem remains, open a GitHub issue with the database product/version,
SchemaSynchronizer version, redacted definition, full exception, and minimal
reproduction. Never include credentials or production data.
