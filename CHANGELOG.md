# Changelog

## 1.3.0 — Unreleased

### New engines

- **SQL Server 2019+ and Oracle 19c+** dialects: detection, history DDL, session locking,
  metadata catalog/schema mapping, serialize/sync, and duplicate-object adoption. SQL Server
  uses transactional DDL and `sp_getapplock`; Oracle follows the implicit-DDL path
  (verification + single-statement change sets when unverified) and `DBMS_LOCK` with
  `release_on_commit=false`, so implicit DDL commits do not drop the lock mid-sync.
- JDBC drivers are Maven `<optional>` in the library; applications declare the engines they
  use. The standalone CLI shades all five drivers.

### Breaking changes

- Spring Boot auto-configuration is **off by default**: set `schema-synchronizer.enabled=true`.
- CLI `serialize`/`sync` reject literal passwords on the command line: pass `-` and set
  `SCHEMA_DB_PASSWORD`.
- `schema=public` is rejected on SQL Server, Oracle, and MySQL/MariaDB (use `dbo`, the
  connected user, or the catalog name).
- MySQL Connector/J connected to MariaDB is detected as `mariadb`; definitions declaring
  `"dialect": "mysql"` for a MariaDB server must switch to `mariadb` (or re-snapshot).

### Locking and change sets

- Locks are scoped to the schema, so products sharing one cluster do not serialize on the
  default id; the 1.2.0 lock is acquired as well, so a rolling upgrade still excludes 1.2.0
  peers. PostgreSQL waits at most 30 seconds (`pg_try_advisory_xact_lock`), like MySQL and SQL
  Server. MySQL lock names longer than 64 characters are hashed instead of truncated.
- The history table records `applied_by` (`SCHEMA_SYNCHRONIZER_ACTOR` or `user.name`);
  existing history tables gain the column on the next sync.
- Change-set and verification SQL must target the configured schema (or system catalogs);
  cross-schema references and session namespace changes (`SET search_path`, `USE`,
  `ALTER SESSION SET CURRENT_SCHEMA`, …) are rejected. Forbidden statements are also found
  inside routine bodies. These are guardrails against mistakes, not a sandbox.
- Dry-run no longer executes `verificationSql`, and uses transactional rollback only on
  engines that support it.
- Duplicate-object adoption classifies errors by SQLState and vendor code only.
- SQL Server and Oracle allow 128-character identifiers; offline `validate` enforces each
  engine's limit for the schema name.

### Comparison and validation

Changes that can alter what an existing definition reports after upgrading:

- **Fractional-second precision** is compared on every engine (`TIMESTAMP(3)` vs `(6)`,
  `DATETIME(3)`, `DATETIME2(3)`, Oracle `WITH LOCAL TIME ZONE`). Snapshots from earlier
  versions that wrote bare `TIMESTAMP`/`TIME`/`DATETIME` for a non-default precision now report pending;
  re-snapshot.
- **`BIT`** is its own fixed-length type (bare `BIT` = `BIT(1)`), no longer folded into
  `BOOLEAN`; a `BIT`/`BOOLEAN` change or `BIT(n)` resize is pending. On MySQL/MariaDB,
  `BOOLEAN` compares as `TINYINT` (what the server stores), including when Connector/J reports
  `TINYINT(1)` as `BIT`; snapshots write those columns as `BOOLEAN`. Earlier snapshots wrote
  bare `BIT` for `BIT(n)` and `BIT` for MySQL `BOOLEAN` columns; re-snapshot. PostgreSQL
  `BIT VARYING(n)` keeps its length, and `B'101'` defaults compare with the stored form.
- **MySQL/MariaDB defaults** compare in the stored form, and only defaults the server stores
  exactly are set automatically; expression defaults are pending. A MySQL string default
  `'NULL'` is kept as a string. Binary defaults compare as bytes (a non-ASCII string literal
  depends on the session character set and stays pending; declare it as `X'…'`), and MySQL snapshots write them
  as `0x…` (earlier snapshots that wrote `'0x…'` do not replay; re-snapshot). Words such as `COLLATE` or
  `COMMENT` inside a default literal no longer count as the clause when checking which column
  attributes `MODIFY COLUMN` would reset, and neither do identifiers in an expression default
  (`comment_count`). Binary literal defaults are read from `SHOW CREATE TABLE`, because
  `information_schema` truncates them at a zero byte (MySQL) or replaces invalid bytes (MariaDB
  before 11.8); when that read fails the column is pending. MariaDB defaults are read from `information_schema`
  rather than the driver.
- **MySQL/MariaDB `ON UPDATE`** is compared on every sync; adding, dropping, or changing it is
  pending, and snapshots write it. Validation rejects an `ON UPDATE` other than
  `CURRENT_TIMESTAMP` and its synonyms, on a column that is not `DATETIME`/`TIMESTAMP`, with a
  different precision than the column, or on another engine.
- **MySQL national types** (`NVARCHAR`, `NATIONAL VARCHAR`, …) report charset and collation
  drift even when the type matches.
- **`explicit_defaults_for_timestamp` OFF** (MariaDB before 10.10): a sync that would create,
  add, or modify a `TIMESTAMP` column is refused before any DDL, including a column that a
  missing table's column list adds after `CREATE TABLE`, and before an unapplied
  `BEFORE_SCHEMA` change set that names a table declaring a `TIMESTAMP` column.
- **MySQL/MariaDB character defaults** the column character set cannot store (for example
  `'日本'` on a `latin1` table) are pending for `MODIFY COLUMN` and `ADD COLUMN`, as is an index
  on such a column, instead of failing in strict `sql_mode` or being stored as `'??'` and
  re-applied on every sync. An index on a missing column with no definition is pending too.
- **MySQL Connector/J against MariaDB** is detected as `mariadb`; definitions declaring
  `"dialect": "mysql"` for a MariaDB server must switch to `mariadb` (or re-snapshot).
- **MySQL/MariaDB validation** rejects defaults the server would reject at DDL time (odd-length
  `X'…'`, out-of-range integers, `DECIMAL` overflow, over-long binary and string literals,
  including trailing spaces, `FLOAT`/`DOUBLE` overflow, negatives on `UNSIGNED`, integer type
  synonyms such as `MIDDLEINT`) before any DDL runs. MariaDB's bare `ON UPDATE CURRENT_TIMESTAMP`
  on `DATETIME(n)` is accepted; an explicit `(0)` is not. A bare number on a binary column
  compares as its decimal text. `TINYINT(1) UNSIGNED`, `INT(n) UNSIGNED` and
  `DECIMAL(p,s) UNSIGNED` parse; `UNSIGNED` types normalize like their signed base, their
  numeric defaults and large `DOUBLE` exponents compare by value, and their defaults are set
  automatically like the signed forms. `DECIMAL(p,s) UNSIGNED` compares precision and scale.
- **`DECIMAL` without precision** compares as the type the engine creates: `DECIMAL(10,0)` on
  MySQL/MariaDB, `DECIMAL(18,0)` on SQL Server, `NUMBER(38,0)` for Oracle's ANSI spellings
  (Oracle `NUMBER` and PostgreSQL `NUMERIC` stay unbounded). On MySQL/MariaDB it compared as
  unbounded, which re-ran `MODIFY COLUMN` on every sync and would have rounded a wider live
  column to `(10,0)`. A wider live column is now pending on MySQL/MariaDB, SQL Server, and
  Oracle, and SQL Server's bare declaration no longer stays pending against a live
  `DECIMAL(18,0)`. On Oracle, a bare `DECIMAL`/`NUMERIC` declared for a live unbounded
  `NUMBER` column now reports a pending change; declare `NUMBER`.
- **Metadata name matching:** JDBC drivers match schema and table names with `LIKE`, so
  `user_role` read the columns of `user1role` (every engine), a MySQL-family schema matched a
  sibling database under `databaseTerm=SCHEMA`, and Oracle primary-key lookups matched a
  sibling schema. Rows are now filtered to the exact name.
- **`DEC` and `FIXED`** compare as `DECIMAL`; they stayed pending against the live column.
  `ZEROFILL` implies `UNSIGNED` in either order, and `DECIMAL(p,s) ZEROFILL` parses. `UNSIGNED`,
  `ZEROFILL`, or `FIXED` declared for PostgreSQL, SQL Server, or Oracle, `NUMBER` declared for
  any engine but Oracle, and an attribute before the length (`INT UNSIGNED(10)`), fail
  validation instead of failing at DDL time. A PostgreSQL definition that declared
  `NUMBER(p,s)` for an existing column must switch to `NUMERIC(p,s)`.
- **MySQL/MariaDB schema binding:** the configured schema must equal `DATABASE()` exactly, also
  when the driver reports no catalog; metadata reads are bound to it, including after a `USE`
  (Connector/J caches the URL database) and with `databaseTerm=SCHEMA`/`useCatalogTerm=Schema`.
  A session `TEMPORARY` table that shadows a declared table fails the sync before any DDL on it.
- **MySQL/MariaDB `MODIFY COLUMN`** is pending when it would drop MariaDB `COMPRESSED` or
  `ZEROFILL`, reset a non-default `ZEROFILL` display width (`INT(5) ZEROFILL`), or add or
  remove the `TINYINT(1)` display width of a column kept as `TINYINT`. `COMPRESSED` and
  `INVISIBLE` columns read their binary defaults correctly.
- **MySQL/MariaDB snapshots** keep column character set/collation (when not the table's),
  `INVISIBLE`, `COMMENT`, and MariaDB `COMPRESSED` in `createSql`, write MariaDB
  `TINYINT(1) UNSIGNED` with its display width, keep the precision of `DECIMAL(p,s) UNSIGNED`
  (also with `ZEROFILL`), and write `ZEROFILL` when taken through MySQL Connector/J. A
  `ZEROFILL` column in an older Connector/J snapshot reports a pending change; re-snapshot.
  A snapshot refuses a `ZEROFILL` integer with a non-default display width, which a
  declaration cannot express. Primary-key text inside literals or comments of
  `createSql` is no longer read as a key clause; a key that reads differently with and without
  `ANSI_QUOTES` fails the sync.

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
