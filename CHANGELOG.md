# Changelog

## 1.4.0 — 2026-09-26

Closes remaining enterprise-audit P2s and deferred least-privilege defaults
(**breaking** for Spring Boot and CLI callers):

- **SS-004:** Spring Boot auto-config is **off by default**
  (`schema-synchronizer.enabled` must be `true`; `matchIfMissing=false`).
- **SS-008:** History ledger records `applied_by` (from `SCHEMA_SYNCHRONIZER_ACTOR`
  or `user.name`). Existing history tables gain the column on next sync.
- **SS-009:** PostgreSQL locks use `pg_try_advisory_xact_lock` with a 30s deadline
  (no unbounded wait), matching MySQL/SQL Server timeouts.
- **SS-012:** Rejects misleading `schema=public` on SQL Server, Oracle, and MySQL
  family (require `dbo` / connected user / catalog name).
- **SS-014:** Dry-run paths consult `supportsTransactionalDryRun()`; transactional
  dry-run rollback is asserted only for dialects that support it; others warn and
  skip execution only.
- **SS-016:** CLI/serialize/sync reject literal passwords on argv — password
  argument must be `-` with `SCHEMA_DB_PASSWORD` set.
- **SS-019:** `DuplicateObjectSql` classifies only by SQLState / vendor code
  (no message-substring fail-open).
- **SS-018 / SS-010:** Docs clarify declarative scope (tables/columns/indexes/PK)
  and that UPDATE/INSERT/GRANT are intentional trusted-artifact operations, not a
  sandbox.
- Offline `validate` enforces dialect `maxIdentifierLength` for the schema name.
- MySQL `GET_LOCK` long names use SHA-256 (not 32-bit `hashCode`), and dual-acquire
  the 1.3.1 hashCode form during rolling upgrades.

## 1.3.1 — 2026-09-26

Hardening from the enterprise audit (P0/P1 correctness and packaging):

- **Oracle lock (SS-001):** `DBMS_LOCK.REQUEST` now uses `release_on_commit=false` so
  implicit DDL commits do not drop the session lock mid-sync. Lock id mixes schema
  namespace with the configured advisory id.
- **Change-set schema scope (SS-002):** change-set and verification SQL must target the
  configured namespace (or system catalogs); cross-schema `schema.object` (including
  quoted / bracket / backtick forms) and `IN SCHEMA other` references are rejected.
  Session namespace mutators (`SET search_path`, `set_config('search_path')`,
  `ALTER SESSION SET CURRENT_SCHEMA`, `USE`) are rejected so unqualified DDL cannot
  escape the bound namespace.
- **Function-body policy (SS-003):** forbidden tokens are scanned inside
  `CREATE FUNCTION` / `TRIGGER` / `PROCEDURE` dollar-quoted bodies (string literals
  still masked).
- **Postgres lock namespace (SS-005):** `pg_advisory_xact_lock(key1, key2)` mixes schema
  namespace with the configured lock id so multi-schema clusters do not serialize on
  the default id alone. During 1.3.1 the legacy single-key lock is also acquired so
  rolling upgrades still exclude 1.3.0 peers (the two Postgres lock spaces are
  independent). Oracle acquires both namespaced and legacy `DBMS_LOCK` ids for the
  same reason.
- **Optional JDBC drivers (SS-006):** library drivers are Maven `<optional>`; apps
  declare the engines they use. The standalone CLI still shades all five drivers.
- **Dry-run (SS-007):** dry-run no longer executes `verificationSql`.
- **Identifier length (SS-011):** SQL Server and Oracle allow 128-character unquoted
  identifiers; PostgreSQL/MySQL family remain at 63.
- **MySQL GET_LOCK (SS-013):** lock resource names longer than 64 characters are
  hashed instead of silently truncating.
- **Index schema compare (SS-015):** index schema binding uses case-insensitive match.
- Publish workflow runs Oracle verify before Central deploy (`needs: oracle-verify`)
  and includes SQL Server in the publish job; docs clarify trusted-artifact policy,
  locking, and dry-run side effects.

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
