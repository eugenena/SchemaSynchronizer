# Roadmap

Planned work for releases after 2.4.0. Items are tracked as GitHub issues; this page
summarizes them and their intended order. Nothing here is a commitment to a date.

## 2.3.0 — adoption observability and profile tooling (shipped)

Delivered with per change-set outcomes, SchemaFingerprints and ConstraintInventory,
SqlScriptSplitter, and the offline profile tooling with the CLI compare and fingerprint
commands. See the [changelog](../CHANGELOG.md).

## 2.4.0 — change-set support for more object types (shipped 2026-11-27)

Issue [#16](https://github.com/eugenena/SchemaSynchronizer/issues/16) is implemented and
released as `v2.4.0`. See the [changelog](../CHANGELOG.md) for the release contents and the
[release audit](ISSUE_16_RELEASE_AUDIT.md) for verification details.

## 2.5.0 and later — planned

- An adoption test kit with versioned baselines so consumer test suites can retire their
  migration-tool test dependencies.
- Single-canonical-definition tooling.
- Spring Test ergonomics.
- Safer DDL execution: lock and statement timeout guardrails, concurrent index builds,
  and a complete dry-run SQL export for DBA review.
- A migration risk report classifying every planned change (destructive, lock/rewrite,
  data-loss risk).
- Placeholders for environment-specific values, and callbacks/hooks for integrations.
- Repeatable change sets and batched backfills for large data updates.


## Change-set support for more object types

Tracked in [#16](https://github.com/eugenena/SchemaSynchronizer/issues/16).

Change sets create (in addition to tables, indexes, functions, and table triggers, and
running additive `ALTER TABLE`, `INSERT`, `UPDATE`, `COMMENT ON`, and object-level
`GRANT`):

| Object | State |
|---|---|
| Sequences | `CREATE SEQUENCE [IF NOT EXISTS]` on PostgreSQL, SQL Server, Oracle, and MariaDB. The name and `OWNED BY` destination are schema-scoped. MySQL has no sequences (rejected), and H2 keeps using identity columns |
| Views | `CREATE [OR REPLACE] VIEW` (`CREATE OR ALTER VIEW` on SQL Server) on every supported dialect. The view's query goes through the same schema-scope and read-only checks as verification SQL |
| Stored procedures | `CREATE [OR REPLACE] PROCEDURE` (`CREATE OR ALTER PROCEDURE` on SQL Server) on every dialect except H2. The body goes through the same routine-body scanner as functions |
| Oracle packages and types | `CREATE [OR REPLACE] PACKAGE` and `PACKAGE BODY` (spec and body are separate change-set statements), `TYPE`, and `TYPE BODY`. Every routine in a body is checked; package variables, cursors, and loop records resolve like a function's locals |
| Ownership elevation | `DEFINER`, `SQL SECURITY DEFINER`, Oracle `AUTHID`, and SQL Server `WITH EXECUTE AS` are rejected by the automatic policy; record the statement in a change set with `reviewedSql: true` after review |

Views and sequences stay change-set only for now: they are not part of the declarative
`tables` model that is compared against metadata, so a fresh database and an adopted one
converge only through ordered, checksummed change sets.

Still rejected (fail-closed, kept for later releases): materialized views on every
dialect, `CREATE TYPE` and `CREATE PACKAGE` outside Oracle, sequences and stored
procedures on H2, and `ALTER SEQUENCE` / `ALTER VIEW` (only additive `ALTER TABLE` is
allowed). Create those outside SchemaSynchronizer — for example with your database
provisioning or a migration tool run by a privileged role — and reference them from
change sets and verification SQL as needed.

## Spring Boot and H2 compatibility

Spring Boot 3 and 4 are both supported through version-specific integration artifacts; the
shared Java API and schema definition format are the same. H2 is supported for local/test use
through its own dialect, with native H2 SQL. Its DDL commits implicitly, so synchronization
requires an auto-commit connection and an administrator connection for the database-wide lock.
