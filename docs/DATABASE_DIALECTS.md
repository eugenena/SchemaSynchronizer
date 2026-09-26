# Database dialects

SchemaSynchronizer detects the database through JDBC and requires the definition's
declared dialect to match. A serialized definition is portable between compatible
instances of the same dialect, not between SQL dialects.

## PostgreSQL

- Supported baseline: PostgreSQL 16+
- Namespace: schema, normally `public`
- Locking: two-key `pg_advisory_xact_lock` (schema namespace + configured lock id)
- DDL: transactional for supported operations
- Dry run: planned work is rolled back; `verificationSql` is not executed
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

## MySQL

- Supported baseline: MySQL 8.0+
- Namespace: database/catalog name
- Locking: named `GET_LOCK` (hashed when the resource would exceed 64 characters)
- DDL: may commit implicitly
- Dry run: statements and verification are not executed; live apply may still commit DDL

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

## Oracle

- Supported baseline: Oracle Database 19c+ (tested with Oracle XE 21c)
- Namespace: user/schema (Oracle folds unquoted identifiers to uppercase)
- Locking: `DBMS_LOCK` with `release_on_commit=false` (grant `EXECUTE ON DBMS_LOCK` to
  the application user)
- DDL: may commit implicitly
- Dry run: statements and verification are not executed; live apply may still commit DDL
- Identifiers: serialize and hand-author lower-case names up to 128 characters; metadata
  lookups upper-case
- Idempotent DDL: no `IF NOT EXISTS`; change-set adoption recognizes ORA-00955 / ORA-01430
  and related codes. Unapplied change sets without verification must be single-statement,
  matching the MySQL/MariaDB implicit-DDL contract.

## Choosing a dialect

Do not edit only the `dialect` field to move a definition between databases. Native
types, default expressions, index syntax, verification queries, and change-set SQL
must all be valid for the target engine. Serialize the target dialect and reconcile
definitions deliberately.
