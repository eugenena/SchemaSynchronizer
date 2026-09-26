# Database dialects

SchemaSynchronizer detects the database through JDBC and requires the definition's
declared dialect to match. A serialized definition is portable between compatible
instances of the same dialect, not between SQL dialects.

## PostgreSQL

- Supported baseline: PostgreSQL 16+
- Namespace: schema, normally `public`
- Locking: two-key `pg_try_advisory_xact_lock` (schema namespace + configured lock id),
  30s timeout; legacy single-key also acquired during 1.3.x upgrades
- DDL: transactional for supported operations
- Dry run: planned work is rolled back (`supportsTransactionalDryRun()=true`);
  `verificationSql` is not executed
- Identifiers: unquoted lower-case, within PostgreSQL's 63-byte limit

PostgreSQL pending destructive output is wrapped in a transaction and includes a
schema-scoped `search_path` for operator review.

## MariaDB

- Supported baseline: MariaDB 10.3+
- Namespace: database/catalog name
- Locking: named `GET_LOCK` (hashed when the resource would exceed 64 characters)
- DDL: may commit implicitly
- Dry run: statements and verification are not executed; live apply may still commit DDL

Retry-safe explicit DDL requires a verification query and one statement per change
set when verification is false.

MariaDB before 10.8 parses but ignores `DESC` in index column lists; declare
ascending indexes there or the live index will not match the declaration.

## MySQL

- Supported baseline: MySQL 8.0+
- Namespace: database/catalog name
- Locking: named `GET_LOCK` (hashed when the resource would exceed 64 characters)
- DDL: may commit implicitly
- Dry run: statements and verification are not executed; live apply may still commit DDL
- Identifiers: unquoted lower-case, up to 64 characters (MariaDB as well)
- Defaults: MySQL reports literal defaults unquoted; SchemaSynchronizer quotes non-numeric
  literals and leaves expression defaults (`DEFAULT_GENERATED`, e.g. `CURRENT_TIMESTAMP`,
  `(uuid())`) as written
- Column changes (MySQL and MariaDB): changes are applied with
  `MODIFY COLUMN <declared definition>`, which replaces the whole column. When that would
  silently reset something the declaration does not repeat, the change is reported as
  pending instead: a collation that differs from the table default, `ON UPDATE`,
  `AUTO_INCREMENT`, `INVISIBLE`, a column `COMMENT`, a generated column, or `NVARCHAR`/`NCHAR`
  declared on a column whose character set is not `utf8mb3`. `NVARCHAR`/`NCHAR` compare as
  `VARCHAR`/`CHAR`.
- Types (all dialects): `NCHAR` and `CHAR`, and `BINARY` and `VARBINARY`, are distinct;
  converting between them, or resizing `BINARY`, is always pending. `CHAR`, `NCHAR`, and
  `BINARY` without a length mean length 1.

The MySQL dialect is also compatibility-tested against Percona Server 8.4 and TiDB
8.5 LTS. This covers SchemaSynchronizer's documented schema model, not every vendor
extension.

## SQL Server

- Supported baseline: SQL Server 2019+ / Azure SQL (compatibility level 150+)
- Namespace: schema inside a database, normally `dbo`
- Locking: `sp_getapplock` / `sp_releaseapplock` (session owner)
- DDL: transactional for supported operations
- Dry run: planned work is rolled back; `verificationSql` is not executed
- Identifiers: unquoted portable names up to 128 characters
- Idempotent DDL: no `IF NOT EXISTS` for `CREATE TABLE` / `CREATE INDEX`; existence is
  checked via metadata before apply, and change-set adoption recognizes SQL Server
  duplicate-object codes (`2714`, `1913`, `2705`, …)
- Column changes: `ALTER COLUMN` is applied only when SQL Server accepts it without side
  effects. These are reported as pending instead:
  - a base-type change under a DEFAULT constraint;
  - any change to a column used by a primary key, foreign key, computed column, or
    view/function expression;
  - any change to a column used by an index, CHECK constraint (column- or table-level),
    or `CREATE STATISTICS` object, except widening a bounded `VARCHAR`/`NVARCHAR`/`VARBINARY`
    (widening such a column to `(MAX)` is pending);
  - `text`, `ntext`, `image`, and `timestamp` columns;
  - columns with a non-default collation (`ALTER COLUMN` would reset it);
  - declarations that omit the live length or precision (for example `DATETIME2` or
    `DECIMAL` without arguments), because `ALTER COLUMN` would apply the type default.

  DEFAULT changes are always pending because defaults are named constraints; replace them
  with a change set. `getdate()` and `CURRENT_TIMESTAMP` compare as the same default.
- Declared indexes: plain column lists only (`ASC`/`DESC` allowed). Filtered, `INCLUDE`,
  clustered, and columnstore indexes belong in change sets; live ones are left alone and
  omitted from serialized snapshots.

## Oracle

- Supported baseline: Oracle Database 19c+ (tested with Oracle XE 21c)
- Namespace: user/schema (Oracle folds unquoted identifiers to uppercase)
- Locking: `DBMS_LOCK` with `release_on_commit=false` (grant `EXECUTE ON DBMS_LOCK` to
  the application user)
- DDL: may commit implicitly
- Dry run: statements and verification are not executed; live apply may still commit DDL
- Identifiers: serialize and hand-author lower-case names up to 128 characters (requires
  `COMPATIBLE` ≥ 12.2; older compatibility settings limit names to 30 bytes); metadata
  lookups upper-case
- Statements: a trailing `;` (including one followed by a comment) is removed before
  execution, except on statements that start with `BEGIN`, `DECLARE`, or
  `CREATE [OR REPLACE] TRIGGER|PROCEDURE|FUNCTION|PACKAGE|TYPE`. The change-set guardrail
  accepts one statement per array item, so PL/SQL bodies that contain inner `;` are not
  supported yet; create such objects outside SchemaSynchronizer.
- Types: `INTEGER` and ANSI `NUMERIC`/`DECIMAL` without precision are stored as
  `NUMBER(38,0)` and compare equal to those declarations; `DOUBLE PRECISION` and `REAL`
  are stored as `FLOAT` and compare equal. Unbounded `NUMBER` serializes as `NUMBER`.
  `VARCHAR2`/`NVARCHAR2` lengths up to 32767 (`MAX_STRING_SIZE=EXTENDED`) are preserved.
  Identity columns (`ISEQ$$` sequence defaults) serialize as
  `GENERATED BY DEFAULT AS IDENTITY`
- Change sets must not reference database links (`object@link`).
- Declared indexes: plain ascending column lists only. `DESC`, function-based, bitmap,
  and domain indexes belong in change sets; live ones are left alone and omitted from
  serialized snapshots.
- Idempotent DDL: no `IF NOT EXISTS`; change-set adoption recognizes ORA-00955 / ORA-01430
  and related codes. Unapplied change sets without verification must be single-statement,
  matching the MySQL/MariaDB implicit-DDL contract.

## Choosing a dialect

Do not edit only the `dialect` field to move a definition between databases. Native
types, default expressions, index syntax, verification queries, and change-set SQL
must all be valid for the target engine. Serialize the target dialect and reconcile
definitions deliberately.
