# Schema definition reference

`schema-definition.json` is a trusted, version-controlled application artifact. It
combines desired schema state with explicit ordered changes that cannot be inferred
safely from JDBC metadata.

## Top-level fields

| Field | Required | Description |
|---|---:|---|
| `formatVersion` | Yes for new files | Current format is `2` |
| `dialect` | Yes for new files | `postgresql`, `mariadb`, or `mysql` |
| `tables` | Yes | Desired tables keyed by unquoted table name |
| `changes` | No | Ordered explicit change-set ledger |

Legacy files without `formatVersion` and `dialect` are interpreted as PostgreSQL
format version 1. New definitions should never rely on that compatibility path.

## Tables

Each table contains:

- `createSql`: idempotent native SQL used when the table is absent;
- `columns`: column names and native SQL definitions; and
- `indexes`: idempotent native `CREATE INDEX` statements.

The table key, the target in `createSql`, and the targets in index SQL must agree.
Use ordinary unquoted identifiers. Table and column names are normalized to
lowercase; quoted identifiers are rejected. Avoid mixed-case spelling even when it
would normalize successfully, because the definition targets the lowercase object.

The declarative layer owns ordinary tables in the configured schema. A live table,
column, index, or primary key absent from the definition is reported as pending
manual SQL. Keep extension-owned or externally managed tables in another schema.

## Change sets

Use change sets for operations that metadata cannot reconstruct reliably, including
backfills, constraints, functions, triggers, comments, and grants. Install extensions
outside change sets (for example in database provisioning); `CREATE EXTENSION` is rejected.

```json
{
  "id": "002-customer-status",
  "description": "Backfill and constrain customer status",
  "phase": "AFTER_SCHEMA",
  "statements": [
    "UPDATE customers SET status = 'ACTIVE' WHERE status IS NULL",
    "ALTER TABLE customers ALTER COLUMN status SET NOT NULL"
  ],
  "verificationSql": "SELECT NOT EXISTS (SELECT 1 FROM customers WHERE status IS NULL)"
}
```

Rules:

- `id` must be stable and unique.
- Never edit an applied change set; its SHA-256 checksum is stored in history.
- Put each JDBC statement in its own array item. A function or trigger whose body holds
  several statements is one item: PostgreSQL `BEGIN ATOMIC … END` and dollar-quoted
  bodies, MySQL/MariaDB `BEGIN … END` (with `IF`/`CASE`/`WHILE`/`REPEAT`/`LOOP` blocks),
  SQL Server `AS BEGIN … END`, and Oracle PL/SQL blocks. A body with an unmatched `END`
  or a missing one is rejected. The `END` of a `CASE` expression is not a block end, so
  `THEN IF(…)` inside `CASE … END` is a function call.
- SQL Server runs every statement of a batch whether or not `;` separates them, so a
  statement word that begins a second statement (`UPDATE t SET a = 1 GRANT …`,
  `SELECT 1 SHUTDOWN`) is rejected like a second `;`-separated statement.
- `phase` is `BEFORE_SCHEMA` by default; use `AFTER_SCHEMA` for objects that depend
  on newly created tables or columns.
- `verificationSql` must return exactly one row containing one non-null boolean.
- Verification queries must be read-only and must not call side-effecting functions.
- Change-set statements must target the configured schema namespace (or system
  catalogs such as `information_schema` / `pg_catalog` / `sys`). Any `other.object`
  reference is rejected. Prefer unqualified column names in `SET` clauses
  (`SET note = …`); `SET items.note = …` is treated as a namespace reference and will
  fail unless `items` is the configured schema. Read-only verification queries may use
  table aliases (`r.relname`), because `alias.column` cannot be told apart from
  `schema.table` without a parser.
- Session namespace mutators (`SET search_path`, `set_config`, `USE`,
  `ALTER SESSION SET CURRENT_SCHEMA`) are rejected in statements and verification SQL.
- Verification SQL must not write or lock: `INTO` (including `SELECT … INTO` and
  `INTO OUTFILE`), `FOR UPDATE`/`FOR SHARE`, locking hints, advisory/application lock
  functions, sequence functions (`nextval`, `setval`, `seq.NEXTVAL`), transaction-id
  assignment (`txid_current`, `pg_current_xact_id`), and `pg_notify` are rejected.
  Only SQL keywords count, so a column named `"into"`, `[updlock]`, or `` `lock` ``, or
  the same words inside a string, is allowed.
- System catalogs are readable but never write or DDL targets, including through an alias,
  CTE, or derived table (`UPDATE c SET … FROM sys.objects c`, `UPDATE TOP (@n) sys.objects`,
  a MySQL multi-table `UPDATE … JOIN information_schema.tables`). Three-part names
  (`db.schema.object`) on PostgreSQL and SQL Server are rejected where an object belongs
  (after `FROM`, `JOIN`, `UPDATE`, `INTO`, …, or before `(`); in an expression,
  `schema.table.column` (`dbo.items.id`) is a column reference and only its schema is
  checked. Oracle database links are rejected.
- Function names are matched as each engine resolves them: `"pg_read_file"(…)`,
  `pg_catalog."pg_read_file"(…)`, and `U&"…"` spellings are the PostgreSQL built-in, while
  `"PG_READ_FILE"` is a different function; on Oracle `"DBMS_SQL"` is the package and
  `"dbms_sql"` is not; MySQL/MariaDB and SQL Server compare names without regard to case,
  so `` `load_file` `` and `[xp_cmdshell]` are rejected.
- Triggers must be table triggers: server, database, schema, and logon/startup triggers
  are rejected. Oracle clauses such as `SET UNUSED`, `MODIFY`, or `RENAME` chained after
  `ADD (…)` in one `ALTER TABLE` are rejected.
- Routine and trigger bodies are checked as code on every engine: PostgreSQL bodies given as
  `$$…$$`, `'…'`, `E'…'`, or `U&'…'` are decoded first, and the statements inside
  `BEGIN … END` blocks are scanned one by one. Their comments and string literals, such as
  `RAISE` messages, are ignored. Besides DDL, `GRANT`/`REVOKE`, and `RENAME`, a body must
  not change roles or the session user (`SET ROLE`, `SET SESSION AUTHORIZATION`,
  `SET DEFAULT ROLE`, `RESET ROLE`, `DBMS_SESSION.SET_ROLE`), run `DENY`,
  `DISABLE`/`ENABLE TRIGGER`, `LOCK`, `FLUSH`, or other administrative statements, or create
  a table with `SELECT … INTO` (SQL Server, and PostgreSQL `LANGUAGE sql`; in PL/pgSQL,
  MySQL/MariaDB, and PL/SQL, `SELECT … INTO` assigns variables and is allowed).
- PostgreSQL routines with an `AS '…'` or `AS $$…$$` body must declare `LANGUAGE sql` or
  `LANGUAGE plpgsql` (PostgreSQL itself rejects such a body without `LANGUAGE`). SQL-standard
  bodies (`BEGIN ATOMIC … END` or `RETURN …`) are SQL and need no `LANGUAGE`. Other languages
  (`plpython3u`, `plperlu`, `plv8`, `c`, `internal`, …) are rejected because their bodies
  cannot be checked.
- A PostgreSQL routine's `SET` clause and a `SET` statement inside a body must not change
  `role`, `session_authorization`, `session_replication_role`, `row_security`,
  `session_preload_libraries`, `local_preload_libraries`, `shared_preload_libraries`,
  `dynamic_library_path`, `jit_provider`, any `log_*` parameter, or any `pgaudit.*`
  parameter. Other parameters such as `work_mem` are allowed there. Every `set_config(…)`
  call is rejected, in statements, bodies, and verification SQL, whatever parameter it
  names.
- SQL Server `ADD SIGNATURE`, `ADD COUNTERSIGNATURE`, and `ADD SENSITIVITY CLASSIFICATION`
  are rejected, including inside trigger and routine bodies.
- MySQL/MariaDB `SET GLOBAL`, `SET PERSIST`, `SET PERSIST_ONLY`, and `SET @@global.…`
  assignments are rejected; reading `@@global.…` is allowed.
- Server-file, network, and bulk-load access is rejected: SQL Server `BULK INSERT`,
  `OPENROWSET`, and `OPENDATASOURCE`; PostgreSQL server-file, large-object, backup, and WAL
  functions (`pg_read_file`, `pg_ls_dir`, `pg_stat_file`, `lo_import`, `lo_put`,
  `pg_switch_wal`, `pg_create_restore_point`, `pg_logical_emit_message`, …); Oracle
  `UTL_HTTP`, `UTL_TCP`, `UTL_SMTP`, `UTL_MAIL`, `UTL_FILE`, `UTL_INADDR`, `DBMS_XMLGEN`,
  `DBMS_XMLQUERY`, `DBMS_PIPE`, `HTTPURITYPE`, `BFILENAME`, and the `DBMS_LOB` file routines
  (`FILEOPEN`, `FILEEXISTS`, `LOADFROMFILE`, `LOADBLOBFROMFILE`, `LOADCLOBFROMFILE`, …).
- The SQL is read with each engine's quoting rules: PostgreSQL `E'…'`, `U&'…'` with
  `UESCAPE`, and `U&"…"`; Oracle `q'[…]'`; MySQL/MariaDB backslash escapes; SQL Server
  `N'…'`. A backslash before a closing quote in a plain PostgreSQL string (`'a\'`) or a
  MySQL/MariaDB string is rejected, because where the string ends depends on server
  settings (`standard_conforming_strings`, `NO_BACKSLASH_ESCAPES`); use `E'…'` or double the
  quote. Unterminated strings, comments, and bodies are rejected. A `--` comment ends at a
  line feed or a bare carriage return on PostgreSQL and SQL Server, and only at a line feed
  on Oracle and MySQL/MariaDB, matching each engine. MySQL/MariaDB executable comments (`/*! … */`) and optimizer
  hints (`/*+ … */`) are rejected; Oracle `/*+ … */` hints are allowed.
- On MySQL/MariaDB, double-quoted text is treated as a string unless it is part of a
  dotted name (`"db"."t"`).
- Not covered: SQL built at run time (`EXECUTE` of a variable, `EXECUTE IMMEDIATE`,
  `sp_executesql`, and `PREPARE` are rejected outright, but a routine that passes a string to
  another procedure that runs it is not followed), the definitions of views and other
  routines a statement calls, and string arguments of MySQL, SQL Server, and Oracle bodies,
  which are treated as data. Table-qualified columns (`SET items.note = …`) are still read
  as a schema, and a SQL Server body without `;` terminators resolves aliases across the
  whole body; both only reject more.
- These checks are a guardrail against accidental unsafe change sets, not a security
  boundary against a hostile author.
- During sync, the SQL checks above apply only to change sets not yet recorded in the history
  table. A recorded change set whose checksum matches never runs again, so it is not
  re-checked; an edited one fails with a checksum error. If the history table does not exist
  yet, every change set is checked. The checks run after history is read and before any
  DDL or change-set statement, in dry-run too. The id, length, duplicate, and empty-statement
  checks still run for every change set before connecting.
- Offline `validate` (and `SchemaDefinitionValidator`) cannot see history, so it checks
  every change set. A change set applied under an earlier release may fail offline
  `validate` while sync still accepts it; do not edit it (its checksum would no longer
  match). Validate the rest of the definition by running a dry-run sync against a database
  whose history records it, or by validating a copy of the definition without the recorded
  change sets.

MariaDB, MySQL, and Oracle can commit DDL implicitly. Every unapplied change set for
those dialects therefore requires `verificationSql`; if verification is false, the
change set must contain exactly one statement. Split multi-step work into ordered
change sets.

## What is automatic

SchemaSynchronizer automatically handles supported safe operations: table and
column creation, index creation, defaults, supported type widenings, and relaxing
`NOT NULL`. Drops, narrowing, ambiguous type changes, and tightening nullability are
manual unless expressed intentionally through a validated change set.

Foreign keys, check constraints, partitions, clustered/filtered indexes, tablespaces,
FILEGROUPs, and collations are **not** inferred from JDBC metadata into the declarative
`tables` model. Express them as ordered change sets with `verificationSql`.

Generate the declarative portion with `SchemaSerializer` when helpful, or hand-author
`tables` directly. Maintain exceptional `changes` by hand either way. See
[HAND_AUTHORING.md](HAND_AUTHORING.md) for the recommended application workflow,
including offline `validate`, `dry-run`, and partial adoption of already-present
objects.
