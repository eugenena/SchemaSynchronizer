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
  literals. Expression defaults (`DEFAULT_GENERATED`) are read in the server's canonical form,
  wrapped in parentheses (`(uuid())`, `(concat(_utf8mb4'a',_utf8mb4'b'))`); `CURRENT_TIMESTAMP`
  on `DATETIME`/`TIMESTAMP` stays bare. MySQL rewrites expressions (charset introducers,
  spacing), so declare them as a snapshot writes them; quotes inside literals are written doubled
  (`'it''s'`), never backslash-escaped. Defaults compare in the form the server stores them:
  `NOW(n)`, `LOCALTIMESTAMP` and `CURRENT_TIMESTAMP(0)` fold to `CURRENT_TIMESTAMP[(n)]`, numeric
  and bit defaults compare by value (`1` = `1.00`, `FALSE` = `0`, `1` = `b'1'`), and zero
  fraction digits in date/time literals are ignored. A default is set automatically only when
  the server stores it exactly for the column: a whole number on an integer type, a number with
  no more fraction digits than a `DECIMAL` scale, `'YYYY-MM-DD'` on `DATE`,
  `'YYYY-MM-DD hh:mm:ss[.f]'` on `DATETIME`, `'hh:mm:ss[.f]'` on `TIME`, or
  `CURRENT_TIMESTAMP[(n)]` on `DATETIME`/`TIMESTAMP`, within the column's fractional precision,
  or a quoted string without backslashes or trailing spaces on a character type. Any other
  default change (expressions, `TIMESTAMP` literals, which depend on the session time zone,
  `BIT`, `YEAR`, `FLOAT`/`DOUBLE`, `ENUM`, binary types) is reported as pending. Binary defaults
  compare as bytes: `'ab'`, `X'6162'`, and `0x6162` are equal, zero-padded to the length of a
  `BINARY(n)`. A string literal with non-ASCII characters is stored in the session's client
  character set, so it never compares equal; declare such defaults as `X'…'`. `information_schema` reports them lossily (MySQL stops at the first zero byte;
  MariaDB before 11.8 replaces invalid bytes with `?`), so literal binary defaults are read from
  `SHOW CREATE TABLE` with `character_set_results=binary` (restored afterwards), and snapshots
  write them as `0x…`. If that output is denied or has an unexpected shape, the column is
  pending and a snapshot refuses to write it. A string default with a control
  character is never set automatically. `ON UPDATE CURRENT_TIMESTAMP[(n)]` (or a synonym) is
  accepted only on a `DATETIME`/`TIMESTAMP` column of the same precision and is rejected on other
  engines; it is compared on every sync, any difference (added, dropped, or a different
  precision) is pending, and snapshots write it. Dropping a default is applied, subject to the
  `TIMESTAMP` refusal below.
- `TIMESTAMP` columns: when `explicit_defaults_for_timestamp` is OFF (the MariaDB default before
  10.10), `CREATE`, `ADD`, and `MODIFY` give a `TIMESTAMP` an undeclared `NOT NULL` and
  `DEFAULT`/`ON UPDATE CURRENT_TIMESTAMP`. A sync that would create, add, or modify a
  `TIMESTAMP` column is refused before any DDL. Enable the setting in the server configuration,
  or with `sessionVariables=explicit_defaults_for_timestamp=1` where the server accepts a session
  value (MySQL 8, MariaDB 10.5.17+/10.6.9+). Reviewed change sets are not checked. A MySQL string
  default `'NULL'` is kept as a string, distinct from no default. MySQL Connector/J connected
  to MariaDB is detected as MariaDB from the server version; MariaDB defaults are read from
  `information_schema`, because that driver reports them unquoted.
- Column changes (MySQL and MariaDB): changes are applied with
  `MODIFY COLUMN <declared definition>`, which replaces the whole column. When that would
  silently reset something the declaration does not repeat, the change is reported as
  pending instead: a collation that differs from the table default, `ON UPDATE`,
  `AUTO_INCREMENT`, `INVISIBLE`, a column `COMMENT`, or a generated column.
- National types (MySQL and MariaDB): `NVARCHAR`/`NCHAR` compare as `VARCHAR`/`CHAR` by length,
  and additionally require the live column to use `utf8mb3` with that character set's default
  collation. Any other character set or collation is reported as pending even when the length
  already matches. On MariaDB 11.2+ the utf8mb3 default can be changed per session
  (`character_set_collations`); declare plain `VARCHAR`/`CHAR` if sessions differ.
- Character set and collation of non-national columns are not part of the declaration model:
  they are not compared, and a column whose collation differs from the table default is never
  modified automatically (the change is reported as pending instead).
- Types (all dialects): `NCHAR` and `CHAR`, and `BINARY` and `VARBINARY`, are distinct;
  converting between them, or resizing `BINARY`, is always pending. `CHAR`, `NCHAR`, and
  `BINARY` without a length mean length 1.
- Fractional-second precision (all dialects): `TIMESTAMP(n)`, `TIMESTAMP(n) WITH [LOCAL] TIME ZONE`,
  `TIME(n)`, `DATETIME(n)`, `DATETIME2(n)`, and `DATETIMEOFFSET(n)` compare by precision. A
  declaration without `(n)` means the engine default: 6 on PostgreSQL and Oracle, 0 on MySQL and
  MariaDB, 7 on SQL Server. Any precision change is pending (a decrease rounds stored values).
  Validation rejects a precision above the engine maximum (6 on PostgreSQL, MySQL, and MariaDB;
  7 on SQL Server; 9 on Oracle) and a precision on a type that takes none (for example SQL Server
  `DATETIME(3)`) before any DDL runs.
  SQL Server legacy `DATETIME` has no precision and is not compared. Oracle
  `TIMESTAMP WITH LOCAL TIME ZONE` and `TIMESTAMP WITH TIME ZONE` are distinct types.
  Snapshots record non-default precision.

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
