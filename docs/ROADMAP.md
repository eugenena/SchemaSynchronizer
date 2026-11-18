# Roadmap

Planned work for releases after 2.3.0. Items are tracked as GitHub issues; this page
summarizes them and their intended order. Nothing here is a commitment to a date.

## 2.3.0 — adoption observability and profile tooling (shipped)

Delivered with per change-set outcomes, SchemaFingerprints and ConstraintInventory,
SqlScriptSplitter, and the offline profile tooling with the CLI compare and fingerprint
commands. See the [changelog](../CHANGELOG.md).

## 2.4.0 and later — planned

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

Today, change sets can create tables, indexes, functions, and table triggers, and can run
additive `ALTER TABLE`, `INSERT`, `UPDATE`, `COMMENT ON`, and object-level `GRANT`.
They reject:

| Object | Current state | Planned |
|---|---|---|
| Sequences | `CREATE SEQUENCE` rejected; identity columns (`GENERATED … AS IDENTITY`, `BIGSERIAL`, `AUTO_INCREMENT`, `IDENTITY(1,1)`) work in column definitions | `CREATE SEQUENCE` with schema-scope checks |
| Views | `CREATE VIEW` rejected | `CREATE [OR REPLACE] VIEW` with its query checked as read-only and schema-scoped |
| Stored procedures | `CREATE PROCEDURE` rejected | `CREATE [OR REPLACE] PROCEDURE` with the same body checks as functions |
| Oracle packages and types | Rejected | Package spec and body as separate statements, with each routine body checked |

Until then, create these objects outside SchemaSynchronizer, for example with your
database provisioning or a migration tool run by a privileged role, and reference them
from change sets and verification SQL as needed.

## Spring Boot and H2 compatibility

Spring Boot 3 and 4 are both supported through version-specific integration artifacts; the
shared Java API and schema definition format are the same. H2 is supported for local/test use
through its own dialect, with native H2 SQL. Its DDL commits implicitly, so synchronization
requires an auto-commit connection and an administrator connection for the database-wide lock.
