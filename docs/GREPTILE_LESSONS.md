# Greptile lessons

## 2026-09-26 — PR #12 — identifier case folding and per-version DDL syntax

- **Bug:** Scope binding lowercased every namespace comparison, so `appdb.*` / `appdb.t`
  passed with `AppDB` configured on case-sensitive MySQL; the MySQL catalog bind check
  used `equalsIgnoreCase`; Oracle index remediation emitted `DROP INDEX IF EXISTS`
  (23ai-only; 19c/21c reject it); index replacement pending SQL echoed declared
  `CREATE INDEX IF NOT EXISTS` to Oracle/SQL Server; `ON DATABASE::x` was compared to a
  schema name instead of being rejected.
- **Missed because:** The fix round was re-reviewed only against its claimed fixes, and
  the final commit skipped the independent re-review entirely. No test matrix covered
  identifier case per dialect or asserted generated DDL for every dialect.
- **Prevention:** Identifier comparisons go through `ChangeSetSchemaScope.canonical`
  (PG lower, Oracle upper, quoted exact, MySQL/MariaDB/SQL Server exact). Every emitted
  DDL helper has an all-dialects loop test
  (`SchemaSynchronizerTest#dropIndexSqlIsDialectAware`,
  `#pendingIndexRecreateSqlIsExecutableOnEveryDialect`,
  `ChangeSetSchemaScopeTest#mysqlCatalogComparisonIsCaseExact` and siblings).
  `CONTRIBUTING.md` now requires both for new SQL-emitting or name-comparing code.

## 2026-09-26 — PR #12 — Oracle/SQL Server release + public-repo hygiene

- **Bug:** Unquoted SQL Server JDBC URLs in CI; ITs still passed argv passwords after
  `CliCredentials`; Oracle dual-lock leak and PG dual-lock AB-BA wait; `GRANT *.*` /
  `SCHEMA::` escaped binding; UNIQUE/filter/INCLUDE indexes and DEFAULT-blocked
  `ALTER COLUMN` mis-handled; 63-char validation despite 128-char dialects; internal
  audit/resume/LinkedIn drafts committed to a public repo.
- **Missed because:** Workflow quoting and IT serialize paths were not exercised in the
  unit suite; lock dual-acquire lacked failure/ordering contracts; GRANT forms beyond
  `schema.object` were out of the scope matrix; career/audit docs were treated as
  project docs.
- **Prevention:** Quote JDBC `-D` URLs; `SchemaSnapshotWriter.writeSnapshot(Connection…)`;
  release Oracle lock on partial failure; acquire PG locks primary-then-legacy only;
  reject `*.*` and `SCHEMA::`; skip UNIQUE-constraint and complex SQL Server indexes;
  pending SQL Server ALTER when live DEFAULT exists; dialect `maxIdentifierLength` in
  declarative validation; remove internal drafts; keep product narrative in
  `docs/DESIGN.md` only. Contracts in `ChangeSetSchemaScopeTest` and
  `SchemaSynchronizerTest`.

## 2026-09-26 — 1.4.0 — P2 hardening before release

- **Bug / gaps:** Boot on-by-default DDL; argv passwords; message-only duplicate
  classification; unbounded PG advisory wait; history without actor; dry-run API
  unused; `public` default misleading on SQL Server/Oracle/MySQL.
- **Missed because:** Audit P2s deferred after P0/P1 1.3.1 pass.
- **Prevention:** `enabled` default false; `CliCredentials` env-only password;
  SQLState/vendor-code-only `DuplicateObjectSql`; `pg_try_*` 30s; `applied_by`;
  dialect schema reject for `public` on non-PG; wire `supportsTransactionalDryRun`.

## 2026-09-26 — 1.3.1 — schema scope and routine-body policy bypasses

- **Bug:** Change-set schema binding only matched bare `ident.ident` after masking
  double quotes, so `"other"."t"`, `[other].[t]`, and `` `other`.`t` `` escaped.
  `SELECT set_config('search_path', …)` remained allowed and defeated unqualified
  binding. Function-body DROP scanning covered dollar quotes but not
  `AS 'BEGIN DROP … END'`.
- **Missed because:** Scope was treated as a simple regex on scannable SQL; session
  mutators and alternate quote forms were not in the contract matrix. Body scan reused
  `scannableSql`, which blanks single-quoted AS bodies.
- **Prevention:** Target-position qualified-name matching with quote forms; reject
  search_path / CURRENT_SCHEMA / USE; extract AS body (dollar or single-quoted) before
  forbidden-token scan. Contracts in `ChangeSetSchemaScopeTest` and
  `NonDestructiveSqlPolicyTest`. Publish `needs: [oracle-verify]`; dual-acquire legacy
  PG/Oracle locks across the 1.3.0→1.3.1 lock-key change.

## 2026-09-26 — 1.2.0 — fail-closed uniqueness when skipping already-exists DDL

- **Bug risk:** Message-only `"already exists"` matching could treat PostgreSQL
  `23505` DETAIL text (`Key (…) already exists.`) as a skippable DDL duplicate;
  walking `getNextException()` could also mask a hard primary error.
- **Missed because:** Uniqueness was blocked by message heuristics instead of
  SQLState/`1062` first; chain OR-matching treated any linked duplicate as success.
- **Prevention:** Reject `23505`/`1062` before message heuristics; classify only the
  primary `SQLException`; require `verificationSql` whenever any statement is skipped.
  Contract: `DuplicateObjectSqlTest` DETAIL-only `23505` and chained `42501`+`42710`.

## 2026-09-23 — PR #11 — classify constraint-backed indexes by semantics

- **Bug:** PostgreSQL serialization excluded every constraint-owned index even though
  table DDL only recreated primary keys, silently losing `UNIQUE` and `EXCLUDE`
  enforcement on a newly synchronized target.
- **Missed because:** Constraint-backed indexes were treated as one category instead
  of tracing which constraint types were recreated elsewhere and which semantics an
  ordinary index can preserve.
- **Prevention:** Classify PostgreSQL backing indexes by `pg_constraint.contype`:
  omit only primary keys, preserve unique constraints as unique indexes, and fail
  closed with an actionable message for exclusion constraints that need an explicit
  ordered change set. Cross-schema replay tests must verify enforcement, not just DDL.

## 2026-09-26 — PR #12 — Oracle/SQL Server correctness and guardrail review rounds

- **Bug (verification scope regression):** Commit d7f0dd6 applied the change-set
  namespace check to `verificationSql`, so any `alias.column` failed as a foreign schema.
  The PostgreSQL integration test that caught it had not been run since.
- **Bug (savepoints):** Change sets released savepoints with `Connection.releaseSavepoint`,
  which mssql-jdbc and ojdbc do not support and which fails on MySQL after DDL
  implicitly commits. On SQL Server every successful statement was rolled back and
  rethrown; on Oracle/MySQL with auto-commit off the history row was never written.
- **Bug (dialect correctness):** SQL Server `ALTER COLUMN` ran on columns referenced by
  computed columns, table-level CHECKs, statistics, or filtered-index predicates;
  `NCHAR`→`CHAR` and `BINARY` resizes were planned as widens (data loss); Oracle unbounded
  `NUMBER` snapshotted as ANSI `NUMERIC` (= `NUMBER(38,0)`, fractional data truncated on
  replay); MySQL `MODIFY COLUMN` reset undeclared charset/`ON UPDATE`/comments.
- **Bug (guardrail):** Oracle `ALTER TABLE … ADD (…) SET UNUSED (…)`, logon/database
  triggers, `@dblink`, three-part names, catalog write targets, and DDL inside trigger
  bodies all passed the additive-only allowlist.
- **Missed because:** Integration tests were only run on the engine being edited, never
  with `failOnPending=true` on a second sync or by replaying a snapshot into an empty
  schema. Type normalization folded types for convenience without asking whether the
  fold hides a lossy conversion. Allowlist checks matched the leading clause of a
  statement and never looked at trailing top-level clauses. The JDBC savepoint API was
  assumed to be portable.
- **Prevention:**
  - Every dialect-touching change runs the full five-engine suite (180+ tests, zero
    skipped) before commit; see CONTRIBUTING.
  - `strictResyncAndSnapshotReplayHaveNoPendingDrift` integration tests (MySQL,
    SQL Server, Oracle) sync twice with `failOnPending=true` and replay the snapshot
    into an empty schema; PostgreSQL covers replay in
    `serializedDefinitionReplaysIntoDifferentSchema`.
  - `DialectDeclarationContractTest#nationalAndFixedBinaryTypesNeverSilentlyConvert`,
    `SchemaSynchronizerTest#sqlServerBlockReasonCoversEveryDependencyKind`,
    `DialectDeclarationContractTest#mysqlModifyColumnIsPendingWhenItWouldResetUndeclaredAttributes`.
  - `GuardrailBypassTest` holds one counterexample per bypass plus its legitimate
    neighbour; `SchemaSynchronizerSqlServerIntegrationTest#appliesChangeSetsWithAndWithoutACallerOwnedTransaction`
    covers savepoints on a live server.
  - Rule: a type normalization may only merge two types when every engine stores them
    identically; otherwise keep them distinct and send conversions to pending.

## 2026-09-23 — PR #10 — document observed normalization behavior

- **Miss:** The schema reference repeated the intended lower-case-only boundary as
  rejection, but definition table and column names are normalized before identifier
  validation.
- **Prevention:** Trace public input through normalization and validation before
  documenting whether an unsupported shape is rejected, normalized, or ignored.
- **Coverage:** The schema reference, troubleshooting guide, and production audit now
  consistently describe lowercase normalization and quoted-identifier rejection.

## 2026-09-23 — PR #8 — database readiness must cross the SQL boundary

- **Bug:** TiDB verification proceeded when its TCP port accepted a connection,
  before the MySQL handshake and query layer were necessarily ready.
- **Missed because:** Listener readiness was treated as database readiness; the
  first JDBC test became the accidental readiness probe and could fail intermittently.
- **Prevention:** Containerized database checks must authenticate and execute a
  trivial query through the target database protocol before starting integration
  tests. The verification script now runs `SELECT 1` through a MySQL client.

## 2026-09-23 — PR #7 — a release workflow must reach its promised terminal state

- **Bug:** The tag workflow ran `mvn deploy`, but the Central plugin stopped at
  `uploaded`; nobody or nothing published the deployment.
- **Missed because:** Packaging and upload were verified, while the workflow's
  advertised outcome—publication—was not traced through the Central plugin state
  machine.
- **Prevention:** Release automation must wait for `published`, and maintainer
  documentation must describe the same terminal state as the automated path.
