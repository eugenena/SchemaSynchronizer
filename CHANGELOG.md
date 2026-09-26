# Changelog

## 1.3.0 — 2026-09-26

- Added SQL Server and Oracle dialects with detection, history DDL, session locking,
  metadata catalog/schema mapping, serialize/sync paths, and duplicate-object adoption
  codes.
- SQL Server uses transactional DDL and `sp_getapplock`; Oracle follows the implicit-DDL
  path (verification + single-statement change sets when unverified) and `DBMS_LOCK`.
- Bundled Microsoft SQL Server and Oracle JDBC drivers in the library and standalone CLI.
- CI covers SQL Server 2022; Oracle XE runs as a dedicated workflow job.

## 1.2.0 — 2026-09-26

- Hand-authoring is a first-class workflow: edit `schema-definition.json` in git,
  validate offline, dry-run, then sync on startup. Serialize remains optional bootstrap.
- Change-set apply tolerates already-present DDL objects (PostgreSQL duplicate
  object/table/column SQLSTATES; MySQL/MariaDB duplicate table/column/key/FK codes)
  so partial Flyway or hand-DDL adoption no longer fails mid-change. Each statement
  runs under a savepoint on PostgreSQL so a skipped duplicate does not abort the
  transaction. Skipping at least one statement requires `verificationSql`, which must
  still pass. SQLState `23505` / MySQL `1062` uniqueness failures are never skipped.
- Fully verified but unrecorded change sets continue to be adopted into history
  without replaying statements.
- CLI adds `validate` (offline) and `dry-run` (live preview) commands.
- Docs: [HAND_AUTHORING.md](docs/HAND_AUTHORING.md), adoption and troubleshooting
  updates for partial apply and hand-authored definitions.

## 1.1.0 — 2026-09-23

- Added task-oriented adoption guides for Spring Boot, CLI use, schema authoring,
  database dialects, production operations, migration, and troubleshooting.
- Added contributor, security, issue, and pull-request guidance.
- Added Maven Central and Javadocs discovery links.
- Aligned copyright notices with ThinkAI LLC ownership while retaining Eugene
  Naoumov as creator and maintainer.
- Added a self-contained executable CLI JAR with `serialize`, `sync`, `help`, and
  `version` commands, bundled JDBC drivers, stable exit statuses, and Maven Central
  checksum publication.
- Made PostgreSQL serializer output portable between source and target schemas by
  removing source-schema qualification, omitting primary-key indexes recreated by
  table DDL, preserving `UNIQUE` enforcement as unique indexes, and failing closed
  for unsupported `EXCLUDE` constraints.

## 1.0.0 — 2026-09-23

First public release.

- Desired-state schema synchronization for PostgreSQL, MariaDB, and MySQL.
- MySQL-dialect compatibility certified against Percona Server 8.4 and TiDB 8.5 LTS.
- Automatic non-destructive table, column, index, default, widening, and nullability changes.
- Explicit pending SQL for destructive or unsafe reconciliation.
- Ordered, checksummed change sets for operations that cannot be inferred from metadata.
- Standalone schema serialization and synchronization CLIs.
- Spring Boot auto-configuration with configurable startup policy.
- Apache-2.0 licensing and Maven Central release packaging.
