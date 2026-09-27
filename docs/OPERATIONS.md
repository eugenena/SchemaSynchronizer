# Operations and safety runbook

Use this checklist before enabling SchemaSynchronizer in a production application.

## Least-privilege database account (required)

The change-set SQL checks catch mistakes; they are **not a security boundary** (see
[SECURITY.md](../SECURITY.md#scope-the-sql-checks-are-not-a-security-boundary)). A change-set
author can reach anything the database account can reach, for example through a
schema-local helper that runs dynamic SQL or a `SECURITY DEFINER` function. The database
account is therefore the only real boundary. Run SchemaSynchronizer (Spring Boot, CLI, or
Java API) as a dedicated account that can change **only the application schema**:

- never a superuser, `sa`, `root`, `SYSTEM`, `db_owner`, or an account holding `ANY` or
  global (`*.*`) privileges;
- no privilege to create roles, users, logins, databases, or extensions;
- ideally separate from the application's runtime account, which needs only DML on the
  application tables.

What SchemaSynchronizer itself executes: `CREATE TABLE`, `ALTER TABLE`, and `CREATE INDEX`
in the configured schema; `CREATE TABLE`, `SELECT`, `INSERT`, and `ALTER TABLE` (adding
`applied_by`) on its history table (`schema_synchronizer_history`); catalog and
`information_schema` reads for that schema; and the engine's lock primitive. Change sets need
whatever their own statements do (for example `UPDATE` for a backfill, or trigger/routine
privileges).

The grants below were verified with the CLI (`sync` twice, then `serialize`) on PostgreSQL 16,
MySQL 8.4, MariaDB 10.3, SQL Server 2022, and Oracle XE 21c. Replace `app`, `app_sync`, and the
password placeholders.

### PostgreSQL

`ALTER TABLE` requires table ownership, so the sync account owns the application schema and
its tables. Advisory locks need no grant.

```sql
-- as a superuser
CREATE ROLE app_sync LOGIN PASSWORD 'change-me' NOSUPERUSER NOCREATEDB NOCREATEROLE;
CREATE SCHEMA app AUTHORIZATION app_sync;
REVOKE CREATE ON SCHEMA public FROM PUBLIC;          -- already the default on PostgreSQL 15+
-- runtime account (optional): DML only
GRANT USAGE ON SCHEMA app TO app_runtime;
ALTER DEFAULT PRIVILEGES FOR ROLE app_sync IN SCHEMA app
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO app_runtime;
ALTER DEFAULT PRIVILEGES FOR ROLE app_sync IN SCHEMA app
    GRANT USAGE, SELECT ON SEQUENCES TO app_runtime;   -- serial / identity inserts
-- after the first sync, keep the runtime account out of the change-set ledger
REVOKE INSERT, UPDATE, DELETE ON app.schema_synchronizer_history FROM app_runtime;
```

Configure `schema=app`. Install extensions (`CREATE EXTENSION`) separately as a DBA; change
sets reject them.

### MySQL and MariaDB

`GET_LOCK` needs no grant. `information_schema` lists a table's columns only to an account
with a privilege on it; `REFERENCES` is enough and reads no data.

```sql
CREATE USER 'app_sync'@'%' IDENTIFIED BY 'change-me';
GRANT CREATE, ALTER, INDEX, REFERENCES, SELECT, INSERT, UPDATE, DELETE ON app.* TO 'app_sync'@'%';
-- only if change sets create them:
-- GRANT TRIGGER, CREATE ROUTINE, ALTER ROUTINE ON app.* TO 'app_sync'@'%';
```

With binary logging on (the MySQL 8.0+ default), an account without `SUPER` gets
`ERROR 1419 … You do not have the SUPER privilege and binary logging is enabled` for
`CREATE TRIGGER` and `CREATE FUNCTION` even with the grants above. Do not grant `SUPER`: either a
DBA sets `log_bin_trust_function_creators=1` (functions must then be `DETERMINISTIC`,
`NO SQL`, or `READS SQL DATA`), or triggers and routines are created outside
SchemaSynchronizer.

Never grant on `*.*`, and never grant `SUPER`, `FILE`, `PROCESS`, `CREATE USER`, or
`GRANT OPTION`. If your change sets do no DML, you can narrow `SELECT, INSERT, UPDATE, DELETE`
to `app.schema_synchronizer_history` (the MySQL integration suite proves that variant).
Configure `schema=app` (the database name, exactly as the server reports it) and connect to
that database in the JDBC URL.

### SQL Server

Use a database user, not `sa` or `db_owner`/`db_ddladmin` membership. `CREATE TABLE` is a
database-level permission, but a table can only be created in a schema where the user has
`ALTER`. `sp_getapplock` needs only `public` membership.

The application schema must be owned by a dedicated user **without a login**, never by `dbo`.
Objects in a schema belong to the schema owner, and SQL Server skips permission checks along an
ownership chain: if `dbo` owned `app`, a trigger or view the sync account creates in `app` could
read or write any `dbo`-owned table.

```sql
-- in the application database, as a DBA (sqlcmd / SSMS batches)
CREATE LOGIN app_sync WITH PASSWORD = 'change-me';   -- Azure SQL Database: run in master
CREATE USER app_sync FOR LOGIN app_sync WITH DEFAULT_SCHEMA = app;
CREATE USER app_owner WITHOUT LOGIN;                 -- owns the schema; nobody logs in as it
GO
CREATE SCHEMA app AUTHORIZATION app_owner;           -- must be alone in its batch
GO
-- existing dbo-owned schema instead: ALTER AUTHORIZATION ON SCHEMA::app TO app_owner;
-- (this drops every grant on the schema and its objects, including the runtime account's:
--  re-run the GRANT lines below and re-grant the runtime account; dbo-owned views and
--  procedures that read app tables lose ownership chaining and need explicit grants)
GRANT CREATE TABLE TO app_sync;
GRANT ALTER, SELECT, INSERT, UPDATE, DELETE, REFERENCES, VIEW DEFINITION ON SCHEMA::app TO app_sync;
-- only if change sets create them:
-- GRANT CREATE FUNCTION, CREATE PROCEDURE, CREATE VIEW TO app_sync;
```

Configure `schema=app`; it must equal the user's `DEFAULT_SCHEMA`. With this setup a trigger in
`app` that selects from a `dbo` table fails with `The SELECT permission was denied on the object`
(verified on SQL Server 2022). Connect with
`encrypt=true` and a trusted server certificate; `trustServerCertificate=true` is for
disposable local containers only.

### Oracle

The sync account is the schema-owning user. Indexes on its own tables need no extra
privilege; identity columns need `CREATE SEQUENCE`.

```sql
-- as a DBA, in the PDB
CREATE USER app IDENTIFIED BY "change-me" DEFAULT TABLESPACE users QUOTA UNLIMITED ON users;
GRANT CREATE SESSION, CREATE TABLE, CREATE SEQUENCE TO app;
GRANT EXECUTE ON SYS.DBMS_LOCK TO app;
-- only if change sets create them:
-- GRANT CREATE TRIGGER, CREATE PROCEDURE TO app;
```

Do not grant `DBA`, `RESOURCE`, or any `… ANY …` privilege. Configure `schema` as the user
name.

## Before deployment

- Commit and review `schema-definition.json` like production code.
- Test an empty database and a production-shaped upgrade database.
- Back up the target and test the restore path.
- Confirm only one schema mutation mechanism is active.
- Use `spring.jpa.hibernate.ddl-auto=validate` when Hibernate is present.
- Keep `fail-on-pending=true` and `require-definition=true` unless a documented
  operational reason requires otherwise.
- Run as the [least-privilege database account](#least-privilege-database-account-required).
- Never write to application tables as a superuser, `sa`, `db_owner`, or `SYSTEM`: triggers that
  change sets created run with the privileges of whoever fires them (PostgreSQL, SQL Server), so
  a privileged session would run change-set code with its privileges.
- Run `validate` (or a sync against an empty database) on the definition. With no history
  table every change set is checked against the current SQL policy, so a definition that
  syncs on an existing database can still fail on a new or rebuilt one (disaster recovery,
  a new region, CI).
- Keep externally managed tables in another schema.

## Deployment sequence

1. Deploy one instance while the remaining fleet stays on the previous version.
2. Preserve complete startup output.
3. Confirm the database lock was acquired and released.
4. Confirm safe changes and change sets completed.
5. Stop if pending SQL is reported; do not repeatedly restart the fleet.
6. Let JPA validation or application health checks complete.
7. Scale out the new version.

Concurrent starters serialize through the dialect's database lock. That protects
the synchronization operation, but a controlled one-instance rollout still makes
diagnosis and rollback clearer.

## Handling pending SQL

Pending SQL is a safety boundary, not an error to suppress.

1. Compare the live object and desired definition.
2. Confirm whether the object should be removed or the definition corrected.
3. Review data loss, lock duration, and application compatibility.
4. Take or verify a backup.
5. Execute approved SQL through the organization's normal database-change process.
6. Run SchemaSynchronizer again and require a clean result.

Setting `fail-on-pending=false` allows startup with unresolved differences. Use it
only when the application is known to be compatible with that drift and monitoring
will keep the pending work visible.

## Dry run

Dry-run plans declarative DDL and unapplied change-set statements without executing them
and **does not run `verificationSql`** (verification can call admin UDFs). PostgreSQL and
SQL Server can also roll back supported DDL selected during a dry run when using the
transactional path. MariaDB, MySQL, and Oracle may commit DDL implicitly on a live apply;
their dry-run mode still skips statement execution, but use disposable instances for any
preflight that must touch the database at all.

## Transactions and locks

When the Java API receives a connection already inside a caller-owned transaction,
SchemaSynchronizer does not commit it. Changes and the transaction-scoped PostgreSQL
advisory lock remain active until the caller commits or rolls back.

Every dialect derives its lock from the configured schema and history table, waiting at most
30 seconds in total, and then also takes the locks 1.x took so a rolling upgrade still
excludes older instances; `advisoryLockId` selects only those legacy locks. Those legacy locks
mean runs sharing an `advisoryLockId` (PostgreSQL, Oracle) or a schema (MySQL, MariaDB, SQL
Server) still wait for each other, as in 1.2.0. PostgreSQL uses
`pg_try_advisory_xact_lock` (unbounded `pg_advisory_xact_lock` is not used). MySQL/MariaDB
`GET_LOCK` resource names are 64-character SHA-256 names; SQL Server uses `sp_getapplock`.
Oracle `DBMS_LOCK` uses `release_on_commit=false` so implicit DDL commits do not release
the lock mid-sync. A lock that is not acquired in time raises
`SchemaLockUnavailableException`, and nothing is changed. History rows record `applied_by` from `SCHEMA_SYNCHRONIZER_ACTOR`
or the JVM `user.name`.

## Recovery

- Preserve the original failure; cleanup errors are attached as suppressed causes.
- A sync that succeeded returns its result even when releasing the lock or restoring
  auto-commit fails afterwards; that failure is logged as a warning. MySQL/MariaDB, SQL Server,
  and Oracle locks are session-scoped, so the session keeps the lock until the connection
  closes; a pooled connection should be discarded.
- On MariaDB/MySQL, inspect the database before retrying after a connection failure
  because DDL may have committed before history recording.
- Verification queries and single-statement change sets allow safe retry decisions.
- Never edit the history table to bypass checksum drift without an incident review.
