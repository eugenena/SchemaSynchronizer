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

Change sets do not yet create sequences, views, stored procedures, or Oracle packages and
types: `CREATE SEQUENCE`, `CREATE VIEW`, `CREATE PROCEDURE`, `CREATE PACKAGE`, and
`CREATE TYPE` are rejected. Use identity columns in column definitions instead of
sequences, or create these objects outside SchemaSynchronizer and reference them from change
sets. Support is planned; see [ROADMAP.md](ROADMAP.md).

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
  reference is rejected. The exceptions are two-part `q.column` references in an
  expression (not followed by `(`) whose qualifier is one of:
  - a table name or alias declared by the same query block: after `FROM`, `JOIN`,
    `USING`, `APPLY`, `UPDATE`, `INTO`, or `MERGE` (`UPDATE items AS i SET note = i.note`,
    `UPDATE items i JOIN tags t ON t.id = i.tag_id SET i.note = t.name`,
    `SET items.note = …`, `MERGE … USING staged s … VALUES (s.id)`), including SQL Server
    `OPENJSON(…) WITH (…) AS j` and table variables (`JOIN @t x`), and the MySQL 8.0.19+
    row alias `INSERT … VALUES (…) AS new [(cols)] ON DUPLICATE KEY UPDATE note = new.note`.
    A name counts only inside its own parentheses and its own `UNION`/`INTERSECT`/
    `EXCEPT`/`MINUS` branch, and only in the statement that declares it. A CTE name counts
    once a `FROM` names it; the `WITH` alone declares nothing (so Oracle
    `WITH other AS (…) SELECT other.f FROM dual` is rejected);
  - a trigger or output row: `NEW`/`OLD` in PostgreSQL and MySQL/MariaDB routines,
    `inserted`/`deleted` in SQL Server triggers and `OUTPUT` clauses, PostgreSQL `EXCLUDED`
    in `ON CONFLICT`. On Oracle, `NEW`/`OLD`/`PARENT` and `REFERENCING` aliases count in
    the trigger body only with the colon (`:NEW.note`, `:n.note`), and bare only in the
    `WHEN (…)` condition;
  - a routine name: parameters, the routine's own name (`RETURN f.p`), variables from
    `DECLARE` or a PL/SQL declaration section (including `v items%ROWTYPE` records), block
    labels (`<<blk>>` … `blk.n`), and loop records (`FOR r IN SELECT …`,
    `FOR r IN (SELECT …) LOOP`). In an Oracle compound trigger, the declaration section
    before the first timing point counts in every timing-point section, and a timing
    point's own `IS` declarations count to its `END`. The parameters and declarations of a
    nested PL/SQL `FUNCTION`/`PROCEDURE` count only in that subprogram's body. A routine's
    parameters never count in its own header (parameter list or `RETURN` type): Oracle
    resolves `f(other IN other.t.c%TYPE)` and `RETURN other.t.c%TYPE` to schema `other`, so
    those are checked as schema names (and rejected for a schema other than the configured
    one), on PostgreSQL as well. Items declared before a nested subprogram do count in its
    header (`FUNCTION q(x h.addr.city%TYPE)` for an outer record `h`). PostgreSQL
    positional parameters (`$1.qty`) are not schema names. On PostgreSQL, MySQL/MariaDB, and
    SQL Server these count in the whole routine; on Oracle, where an out-of-scope name would
    resolve to a schema, only to the end of the declaring block, loop, or labelled block;
  - on PostgreSQL, a three-part `label.record.field` or `routine.param.field` that is not
    called (PL/pgSQL reads any other three-part name as `schema.table.column`; write a
    nested field as `(r.addr).city`);
  - on Oracle, a declared routine item followed by a field chain (`h.addr.city`), anywhere
    in the routine, including inside a SQL statement, where Oracle reads the local record
    first; and, in procedural code only, a collection method or method call: `t.COUNT`,
    `t.DELETE`, `t.DELETE(i)`, `t.EXISTS(i)`, `t.EXTEND(n)`, `t.FIRST`, `t.LAST`,
    `t.LIMIT`, `t.NEXT(i)`, `t.PRIOR(i)`, `t.TRIM(n)`, `v.method(…)`. Oracle rejects
    collection methods inside SQL (`ORA-00904`), so `h.addr.upper(1)` or `t.COUNT` in a
    SQL statement is checked as a schema-qualified name;
  - on Oracle, inside a SQL statement, an alias declared in the same query block followed by
    a column attribute or method chain: `UPDATE items i SET note = i.doc.getStringVal()`,
    `i.addr.city` for an object-type column. The alias wins over a schema of the same name
    (`UPDATE items system SET note = system.addr.city` reads the column). Only an alias
    counts: a bare table name (`items.doc.getStringVal()`) may resolve as
    `schema.package.function`, so it is checked as a schema name, and so is a chain in a
    `FROM`/`INTO`/`JOIN`/`UPDATE` target position;
  - a `%TYPE`/`%ROWTYPE` anchor (`n items.note%TYPE`) on PostgreSQL and Oracle. A
    three-part anchor is `schema.table.column%TYPE` and its schema is checked
    (`n app.items.note%TYPE` passes for schema `app`; `other.items.note%TYPE` and four-part
    anchors on PostgreSQL are rejected). On Oracle, an anchor whose first part is a declared
    routine item or label (`x r.a.c%TYPE` for a local record `r`) is a field of that item.
    An earlier local named like a schema (`other NUMBER; x other.t.c%TYPE`) is accepted;
    Oracle does not compile it (`PLS-00487`).

  None of these exemptions applies where a type is expected, because an alias, parameter,
  or variable never qualifies a type: a two-part name there is `schema.type` and its
  schema is checked. Type positions are: after `::`; the target of `CAST`, `TRY_CAST`,
  `TREAT` (including `AS REF`), and `XMLCAST`; the first argument of SQL Server
  `CONVERT`/`TRY_CONVERT`; a PostgreSQL typed literal (`schema.t 'x'`); after `COLLATE`;
  Oracle `IS OF [TYPE] (…)`; routine parameter and `RETURNS`/`RETURN` types, including
  unnamed parameters (`f(schema.t)`) and `RETURNS SETOF`/`TABLE (…)`; `DECLARE` and PL/SQL
  declaration-section types, including `TYPE … IS RECORD (…)` fields, nested subprogram
  headers, and SQL Server `DECLARE @v [AS] schema.t` (a declaration's type ends at its
  default — `:=`, `=`, or `DEFAULT` — or at `NOT NULL`, so `q int = p.qty` reads `p.qty`
  as a parameter field); and column types in `CREATE TABLE`
  and `ALTER TABLE … ADD`. `OPERATOR(schema.op)` is checked the same way. So
  `SELECT c::other.t FROM items other`, `CREATE FUNCTION f(other int, x other.t) …`, and
  Oracle `UPDATE items other SET c = CAST(c AS other.t)` are rejected.

  Oracle also accepts `seq.NEXTVAL`/`seq.CURRVAL` (a sequence in the current schema) and
  these supplied packages, bare or as `SYS.pkg.member`: `DBMS_OUTPUT`, `DBMS_RANDOM`,
  `UTL_RAW`, and `UTL_I18N` (any member; they convert `RAW` and character-set values);
  `DBMS_UTILITY.FORMAT_ERROR_STACK`,
  `FORMAT_ERROR_BACKTRACE`, and `FORMAT_CALL_STACK`; and the `DBMS_LOB` routines that do not
  open files (`APPEND`, `COMPARE`, `CONVERTTOBLOB`, `CONVERTTOCLOB`, `COPY`,
  `CREATETEMPORARY`, `ERASE`, `FREETEMPORARY`, `GETCHUNKSIZE`, `GETLENGTH`, `INSTR`,
  `ISTEMPORARY`, `READ`, `SUBSTR`, `TRIM`, `WRITE`, `WRITEAPPEND`), with every `DBMS_LOB`
  constant (`LOB_READONLY`, `LOB_READWRITE`, `LOBMAXSIZE`, …) and exception
  (`INVALID_ARGVAL`, `ACCESS_ERROR`, …). These Oracle-supplied data types are accepted as
  `SYS.type` (constructor or declared type): the scalar collections `ODCINUMBERLIST`,
  `ODCIVARCHAR2LIST`, `ODCIDATELIST`, `ODCIRAWLIST`, `ODCIGRANULELIST`, and `ODCIRIDLIST`
  (`TABLE(SYS.ODCINUMBERLIST(1, 2))`), and `XMLTYPE` with its `createXML` static function
  (`SYS.XMLTYPE('<a/>')`, `XMLTYPE.createXML(…)`). Other `SYS` types (`ODCIBFILELIST`,
  `ODCIOBJECTLIST`, `HTTPURITYPE`, `ANYDATA`, …) are rejected, and an `XMLTYPE` read from a
  file still needs `BFILENAME`, which is rejected. Supplied packages, types, and sequences
  are accepted only in expression and type positions; as a statement target
  (`INSERT INTO SYS.ODCINUMBERLIST …`, `FROM SYS.XMLTYPE`, `UPDATE`, `JOIN`, `TABLE`
  targets) they are rejected (`TABLE(SYS.ODCINUMBERLIST(…))` is a constructor and passes).
  `SYS.DUAL` is accepted where it is read (`FROM SYS.DUAL`, `JOIN SYS.DUAL`) and rejected
  as a write, lock, comment, or index target.
  PostgreSQL object names given as text are read as qualified names and must use the
  configured schema or a system catalog. A name literal may be written in any string
  spelling (`'…'`, `E'…'`, `U&'…'`, `$$…$$`, `$tag$…$tag$`), inside parentheses, and cast
  through a text type (`text`, `varchar[(n)]`, `character varying`, `char`, `bpchar`,
  `name`) or a `reg*` type (`('items')::regclass`, `'items'::text::regclass`,
  `CAST('items' AS text)::regclass`); the literal inside is what is checked. An argument
  that contains a string but is not a single such literal (`'a' || 'b'`, `lower('x')`,
  `format('%I', 'x')`, `coalesce(col, 'x')`, `CASE … 'x' END`) is rejected, because its
  name cannot be checked. An argument with no string at all (a column or an object id)
  is allowed where noted below.
  - the first argument of the functions that use or change the object they name, which
    must be a name literal: `nextval`, `currval`, `setval`, `pg_get_serial_sequence`,
    `pg_extension_config_dump`, and the index-maintenance functions
    `brin_summarize_new_values`, `brin_summarize_range`, `brin_desummarize_range`, and
    `gin_clean_pending_list`. In `nextval`/`currval`/`setval` it may also be a
    `pg_get_serial_sequence('items', 'id')` call, whose table literal is checked
    (`SELECT setval(pg_get_serial_sequence('items', 'id'), coalesce(max(id), 1)) FROM items`).
    These index-maintenance functions write index state: they are allowed in change sets
    and rejected in verification SQL;
  - the first argument of the functions that return metadata about a relation:
    `to_regclass`, `pg_get_viewdef`, `pg_relation_size`, `pg_table_size`,
    `pg_total_relation_size`, `pg_indexes_size`, `pg_relation_filenode`,
    `pg_relation_filepath`, `pg_sequence_last_value`, `pg_get_replica_identity_index`,
    `pg_column_is_updatable`, `pg_relation_is_updatable`, `pg_relation_is_publishable`,
    `pg_partition_root`, `pg_partition_tree`, `pg_partition_ancestors`,
    `pg_index_has_property`, and `pg_index_column_has_property`. A name literal is checked;
    an object id or column (`pg_relation_size(c.oid)`, `pg_get_viewdef(c.oid, true)`,
    `pg_index_has_property(i.indexrelid, 'clusterable')`) is allowed;
  - `to_regproc`, `to_regprocedure`, `to_regtype`, `to_regtypemod`, `to_regoper`,
    `to_regoperator`, `to_regcollation`, and `to_regnamespace` (a schema name);
    `to_regrole` names a cluster-wide role and is not checked;
  - the object names of `has_table_privilege`, `has_sequence_privilege`,
    `has_column_privilege`, `has_any_column_privilege`, `has_function_privilege`,
    `has_type_privilege`, and the schema of `has_schema_privilege`;
  - the type argument of `pg_input_is_valid`/`pg_input_error_info`, and the value too when
    that type is a `reg*` type. A literal value checked against a `reg*` array type
    (`'regclass[]'`, `'_regclass'`, `'regclass ARRAY'`) is rejected;
  - literals of every `reg*` type, in each spelling (`'…'::regclass`,
    `'…'::pg_catalog.regclass`, `CAST('…' AS regclass)`, `regclass '…'`, `regclass('…')`):
    `regclass` names a relation, `regnamespace` a schema, and `regproc`, `regprocedure`,
    `regtype`, `regoper`, `regoperator`, `regconfig`, `regdictionary`, and `regcollation`
    a qualified object, including the argument types of `regprocedure`/`regoperator`
    (`'public.f(integer, text)'`); `regrole` is not checked. A column or object id cast to
    a `reg*` type (`c.oid::regclass`, `note::regclass`) is allowed. A `reg*` array built
    from strings (`'{a,b}'::regclass[]`, `'{a}'::_regclass`, `ARRAY['a']::regclass[]`) is
    rejected.

  Functions that take an object id or a cluster-wide name (`pg_get_*def(oid)`,
  `has_database_privilege`, text-search functions with a `regconfig`) are not checked;
  functions that export relations or run a query string are rejected (see below).

  Still rejected:
  - calls `q.f(…)` whose qualifier is not the configured schema. On Oracle a package call in
    the same schema must be schema-qualified (`APP.util_pkg.normalize(x)`, not
    `util_pkg.normalize(x)`); other supplied packages, `DBMS_LOB` file routines (`OPEN`,
    `FILEOPEN`, `LOADFROMFILE`, …), and quoted lower-case package names are rejected;
  - object positions (`FROM other.t`, `JOIN other.t`, `INTO other.t`);
  - trigger rows, sequences, and records with more than two parts (`other.s.NEXTVAL`,
    `:new.s.NEXTVAL`), and bare `new.f`/`old.f`/alias references in an Oracle trigger body;
  - a qualifier declared in a subquery, another set-operation branch, another statement of
    a body, or (on Oracle) a block or loop that has ended;
  - PostgreSQL name arguments that contain a string but are not a single name literal
    (`nextval('a' || 'b')`, `nextval(lower('s'))`, `lower('x')::regclass`), a column or
    object id where a name literal is required (`nextval(col)`), and three-part names;
  - Oracle `DBMS_ASSERT` (its members only validate text for dynamic SQL).

  PostgreSQL and Oracle compare these names with their case folding (quoted names exactly),
  MySQL/MariaDB compare exactly, and SQL Server compares without regard to case.
  Read-only verification queries may use table aliases (`r.relname`).
- A statement may start with `WITH` when the engine supports the statement after it:
  `WITH … SELECT` on every engine, `WITH … UPDATE` and `WITH … INSERT` on PostgreSQL and
  SQL Server, and `WITH … UPDATE` on MySQL 8.0. MariaDB and Oracle have no `WITH` form of
  `UPDATE` or `INSERT` (Oracle writes `INSERT INTO t WITH … SELECT …`), so those are
  rejected. CTE bodies are checked like any subquery, and `DELETE` is rejected anywhere.
- Session namespace mutators (`SET search_path`, `set_config`, `USE`,
  `ALTER SESSION SET CURRENT_SCHEMA`) are rejected in statements and verification SQL.
  On PostgreSQL, a routine-level `SET search_path` clause of `CREATE [OR REPLACE] FUNCTION`
  (or of `ALTER FUNCTION`/`PROCEDURE`/`ROUTINE`, which the statement policy does not
  otherwise support) is accepted when every item is the configured schema, `pg_catalog`,
  `pg_temp`, or `''`, in any order, quoted or not:
  `CREATE FUNCTION f() … SECURITY DEFINER SET search_path = app, pg_temp AS $$…$$`. It is
  rejected when it names any other schema, uses `FROM CURRENT` (which copies the session
  value) or `DEFAULT`, or is written as one string (`'app, pg_temp'` is a single schema
  name). `SET [LOCAL|SESSION] search_path` as a statement, inside a body, or in
  verification SQL stays rejected.
- Verification SQL must not write or lock: `INTO` (including `SELECT … INTO` and
  `INTO OUTFILE`), `FOR UPDATE`/`FOR SHARE`, locking hints, advisory/application lock
  functions, sequence functions (`nextval`, `setval`, `seq.NEXTVAL`), transaction-id
  assignment (`txid_current`, `pg_current_xact_id`), `pg_notify`, and index maintenance
  (`brin_summarize_new_values`, `brin_summarize_range`, `brin_desummarize_range`,
  `gin_clean_pending_list`) are rejected.
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
  `UTL_HTTP`, `UTL_TCP`, `UTL_SMTP`, `UTL_MAIL`, `UTL_FILE`, `UTL_INADDR`, `DBMS_PIPE`,
  `BFILENAME`, and the `DBMS_LOB` file routines (`FILEOPEN`, `FILEEXISTS`, `LOADFROMFILE`,
  `LOADBLOBFROMFILE`, `LOADCLOBFROMFILE`, …).
- SQL passed as text, and access to other schemas by name through XML, URI, or OLAP
  functions, is rejected:
  - PostgreSQL `query_to_xml*`, `cursor_to_xml*`, `table_to_xml*`, `schema_to_xml*`, and
    `database_to_xml*` (every variant, including `…_xmlschema` and `…_and_xmlschema`),
    `ts_stat`, `ts_rewrite` (run a query string), and `pg_nextoid`;
  - Oracle `OPEN cur FOR` followed by anything but a query (`SELECT`, `WITH`, or `(`), so
    `OPEN c FOR 'SELECT …'` and `OPEN c FOR v_sql` are rejected;
  - Oracle packages with members that parse, explain, schedule, or run SQL text:
    `DBMS_SQL`, `DBMS_SYS_SQL`, `DBMS_XMLGEN`, `DBMS_XMLQUERY`, `DBMS_XMLSTORE`,
    `DBMS_XMLSAVE`, `DBMS_AW`, `DBMS_ODCI`, `DBMS_PARALLEL_EXECUTE`, `DBMS_SQLTUNE*`,
    `DBMS_SQLDIAG`, `DBMS_SQLPA`, `DBMS_SQLSET`, `DBMS_SQLQ`, `DBMS_SPM`,
    `DBMS_SQL_TRANSLATOR`, `DBMS_SQL_MONITOR`, `DBMS_XPLAN`, `DBMS_ADVISOR`, `DBMS_ADDM`,
    `DBMS_SNAPSHOT`, `DBMS_MVIEW`, `DBMS_SYNC_REFRESH`, `DBMS_SPACE`, `DBMS_DATA_MINING`,
    `DBMS_DIMENSION`, `DBMS_SUMMARY`, `DBMS_DEBUG`, `DBMS_JOB`, `DBMS_SCHEDULER`, `DBMS_DDL`,
    `DBMS_REDEFINITION`, `DBMS_HS_PASSTHROUGH`, `DBMS_DATAPUMP`, `DBMS_XSLPROCESSOR`,
    `OWA_UTIL`, and `DBMS_UTILITY.EXEC_DDL_STATEMENT`/`EXPAND_SQL_TEXT`;
  - Oracle `HTTPURITYPE`, `DBURITYPE`, `XDBURITYPE`, `FTPURITYPE`, `URIFACTORY`,
    `CUBE_TABLE`, `OLAP_TABLE`, and `OLAPRC_TABLE`, and `XMLQUERY`/`XMLTABLE`/`XMLEXISTS`
    whose XQuery text calls `collection`, `uri-collection`, `doc`, `doc-available`, or an
    `ora:` function (`fn:collection("oradb:/OTHER/T")`); XQuery over a passed value
    (`XMLQUERY('/a/b' PASSING x …)`) is allowed;
  - Oracle `SQL_MACRO` functions (their returned text runs as SQL) and call specifications
    (`LANGUAGE JAVA`, `LANGUAGE C`, `AS EXTERNAL`), whose code cannot be read.
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
  `OPEN … FOR` a string, `sp_executesql`, and `PREPARE` are rejected outright, but a routine that passes a string to
  another procedure that runs it is not followed), the definitions of views and other
  routines a statement calls, and string arguments of MySQL, SQL Server, and Oracle bodies,
  which are treated as data. A SQL Server body without `;` terminators resolves
  system-catalog aliases across the whole body, which only rejects more.
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
