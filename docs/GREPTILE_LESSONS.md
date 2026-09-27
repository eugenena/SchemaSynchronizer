# Greptile lessons

## 2026-09-27 — PR #14 — do not open a PR on a FIX-FIRST verdict

- **Bug:** Greptile scored 2/5 for three findings the local Grok review had already reported:
  `DEFAULT '…' COLLATE x` bypassing the charset check, the TIMESTAMP refusal firing for
  `CREATE INDEX`/`INSERT`, and a predicate literal naming a column that was not added.
- **Missed because:** The PR was opened while the last local verdict was FIX-FIRST, and the
  first narrowing of the TIMESTAMP check dropped `DROP TABLE`/`RENAME TABLE` from the
  statements it must still refuse.
- **Prevention:** Open a PR only after the last local review returns SHIP. A narrowed match
  keeps cells for every statement kind it must still catch
  (`DialectDeclarationContractTest#changeSetsReferenceTimestampTablesByTokenOutsideLiterals`,
  `#mySqlColumnClausesAfterTheDefaultFailValidation`,
  `#indexesOnColumnsThatWereNotAddedAreRecognizedByToken`).

## 2026-09-27 — issue #13 — a statement boundary is whatever the engine executes as one

- **Bug:** SQL Server ran `UPDATE t SET a = 1 GRANT CONTROL TO bob` as two statements while the
  guardrail split only on `;` and checked the leading keyword. `CREATE EXTENSION`, non-SQL
  PostgreSQL routine languages, a routine `SET role` clause, MySQL `SET GLOBAL`, `BULK INSERT`,
  and several file/WAL/network functions were accepted. A `CASE` expression's `THEN IF(…)`
  or `END LOOP` was read as block structure, rejecting valid MySQL and PL/SQL routines, and
  the three-part-name check was quadratic in the column count.
- **Missed because:** The single-statement cell assumed every engine needs `;`, the allowlist
  was reviewed for what it accepts rather than what the accepted statement can run, and block
  tracking had no cell for block keywords inside expressions.
- **Prevention:** For each engine, the single-statement check has a cell for a second
  statement without a separator. Every allowlisted statement kind gets a cell for what its
  body, options, or clauses can run. Block tracking has cells for block keywords inside
  expressions. Guardrail scans get a size bound test
  (`SqlTokenizerTest#sqlServerStatementWithoutSemicolonStartsANewStatement`,
  `#caseExpressionEndIsNotABlockEnd`, `GuardrailTokenizerContractTest$SqlServerBatchesWithoutSemicolons`,
  `$PostgresLanguagesAndServerFunctions`, `$RoleAndGlobalSettings`, `$LongLists`).
- **Second round (same class):** SQL Server ended `1E`, `1.E`, and `0x` as complete numbers,
  so `SET a = 1EGRANT …` still ran two statements, and `ADD SIGNATURE TO …` (not a reserved
  word) started a statement the splitter did not know. Oracle `BFILENAME`/`DBMS_LOB` file
  routines and PostgreSQL `log_*`/`pgaudit.*` settings were accepted.
- **Missed because:** The number lexer was written from the documented grammar instead of
  probing the engine at each boundary, and the statement-start list was assembled from
  memory rather than from the engine's full statement list.
- **Prevention:** Lexer boundaries come from a live probe table
  (`SqlTokenizerTest#sqlServerNumericLiteralsEndWhereTheEngineEndsThem`). Every statement form
  of the engine's reference is iterated after several complete statements and must split
  exactly at its first word (`#everyTsqlStatementStartsANewStatementAfterAnotherStatement`);
  forms the engine only accepts after `;` are recorded as such
  (`#receiveIsAStatementOnlyAfterASemicolon`). Forbidden settings are an explicit list plus
  prefixes, checked in routine `SET` clauses, body statements, and `set_config`
  (`GuardrailTokenizerContractTest$RoleAndGlobalSettings`).
- **Third round (same class):** a bare `\r` ended a `--` comment on PostgreSQL and SQL Server
  while the lexer waited for `\n`, so the code after it went unchecked. MySQL `/*+ SET_VAR(…) */`
  hints changed session variables. `LOAD` and `DUMP` were treated as statement words though
  SQL Server accepts them as column names, and `GET`/`MOVE CONVERSATION` and `SEND ON` split
  a column followed by its alias. The docs said `set_config` on other parameters was allowed,
  while the scope check rejects every call.
- **Missed because:** Comment ends were assumed rather than probed per engine, reserved-word
  status was taken from documentation, and the docs were written from the policy check alone.
- **Prevention:** Every lexical boundary (comment end, hint, number) has a per-dialect cell
  probed on the engine (`SqlTokenizerTest#lineCommentsEndWhereTheEngineEndsThem`,
  `GuardrailTokenizerContractTest$CarriageReturnLineComments`, `$OptimizerHints`). Each
  statement word is checked as an unquoted column name on the engine before it may split
  (`SqlTokenizerTest#unreservedWordsAreColumnsNotStatements`,
  `#serviceBrokerStatementsStartOnlyAfterASemicolon`). Docs for a rejection are checked
  against both the policy and the scope check.

## 2026-09-27 — issue #13 — guardrails must read SQL the way the engine does

- **Bug:** The change-set guardrails matched regular expressions against masked text. Quoted
  admin function names (`"pg_read_file"(…)`, `[xp_cmdshell]`), escaped PostgreSQL bodies
  (`E'\x44ROP …'`, `U&'\0044ROP …'`), and role or trigger statements inside T-SQL, PL/SQL,
  and MySQL bodies passed. `UPDATE TOP (@n) sys.objects` and aliased catalog writes were not
  seen as writes, `dbo.items.id` was reported as a three-part name, verification rejected a
  column named `"into"`, and PL/SQL bodies with inner `;` could not be applied.
- **Missed because:** Each check re-derived statement structure from text with its own
  pattern, and the contract matrix listed forbidden forms without the quoted, escaped, and
  aliased spellings of each one, per engine.
- **Prevention:** One per-dialect tokenizer (`SqlTokenizer`) feeds every guardrail; names
  compare by the engine's folding, and bodies split as one statement with inner statements
  scanned. Every new forbidden form gets a cell per engine for the quoted/escaped spelling,
  a look-alike in a literal, identifier, and comment, and a mixed list
  (`GuardrailTokenizerContractTest`, `SqlTokenizerTest`, and the
  `multiStatement…` integration tests on all five engines).

## 2026-09-27 — issue #13 — fail-closed checks must not block converged schemas

- **Bug:** The `explicit_defaults_for_timestamp` refusal was checked in the preflight, but a
  missing table's column list (added right after `CREATE TABLE`) and `BEFORE_SCHEMA` change
  sets ran before the apply pass, so DDL could commit before the refusal. The first fix
  refused whenever any change set was unrecorded, which blocked MariaDB 10.3 users whose
  `TIMESTAMP` columns already matched, and adopting a database whose change sets already
  verify. A non-ASCII default the column character set cannot hold failed in strict
  `sql_mode` or was stored as `'??'` and re-applied on every sync. The first fix judged
  character sets in Java (so a `gbk` column that already stored its default blocked every
  widening) and read the table character set through a `COLLATIONS` join that finds nothing
  for MariaDB 11's default `utf8mb4_uca1400_ai_ci`.
- **Missed because:** The refusal was placed where DDL was planned, not where the first DDL
  could run. The follow-up fixes were tested only for the case they refuse, not for a schema
  that already converged, and only on MySQL.
- **Prevention:** `CONTRIBUTING.md` requires a converged-case cell for every new pending or
  refusal check, asking the server for character-set verdicts, MariaDB 10.3 and 11.x runs,
  and covering what runs between the preflight and the apply pass. Contracts:
  `SchemaSynchronizerMySqlIntegrationTest#timestampRefusalPrecedesCreatedTablesAndChangeSets`,
  `#defaultsTheCharsetCannotStoreArePendingInAnySqlMode`,
  `SchemaSynchronizerMariaDbIntegrationTest#addedDefaultsFollowTheTableCharsetAndUnrelatedChangeSetsSkipTheTimestampRefusal`,
  `DialectDeclarationContractTest#changeSetsReferenceTimestampTablesByTokenOutsideLiterals`.

## 2026-09-27 — issue #13 — newly parseable types must reach every comparison

- **Bug:** `DECIMAL(p,s) UNSIGNED` became parseable, but live precision was read and compared
  only for the exact type name `NUMERIC`, so a nullability or default change could run
  `MODIFY COLUMN` to a narrower `DECIMAL`. A bare `DECIMAL` compared as unbounded on
  MySQL/MariaDB, although the server creates `DECIMAL(10,0)`, so every sync re-ran `MODIFY`
  (and would round a wider live column). JDBC `getTables`/`getColumns` received schema and
  table names as LIKE patterns, so `user_role` read `user1role`'s columns and a
  `databaseTerm=SCHEMA` schema matched sibling databases. `precision() - scale()` overflowed
  an `int` for `1E2147483647`, letting the default through validation. The first fix escaped
  the metadata arguments with `getSearchStringEscape()`, which made Connector/J under
  `NO_BACKSLASH_ESCAPES` find no tables at all (every change set would re-run, every column
  would be re-added). Normalizing `DEC` to `NUMERIC` exposed that a bare `DECIMAL` compared as
  unbounded on SQL Server and Oracle too, and filling SQL Server's `(18,0)` for every bare
  `NUMERIC` spelling let an Oracle-only `NUMBER` reach an applied `ALTER COLUMN c NUMBER`.
- **Missed because:** The parser change was tested for parsing and snapshots, not for
  comparison with a live column of different precision, and the bare-form cell covered only
  MySQL/MariaDB. Metadata arguments were assumed to be names; no fixture had a sibling name
  that differs only where the real name has `_`, and the escaping fix was verified with the
  default `sql_mode` only. Exponent arithmetic had no cells at the `int` boundary.
- **Prevention:** `CONTRIBUTING.md` requires a live-vs-declared precision cell for every
  newly parseable type, including the bare form on every engine, rejects normalized spellings
  on engines that do not accept them (`#unsignedAndZerofillAreRejectedOutsideMySqlFamily`),
  and filters metadata rows through `DatabaseDialect.isRequestedObject` instead of escaping.
  Contracts:
  `DialectDeclarationContractTest#unsignedDecimalComparesPrecisionAndScale`,
  `#metadataPatternsMatchOnlyTheExactName`, the int-boundary exponent cells in
  `#mysqlDefaultsTheServerRejectsFailValidation`, `#unsignedZerofillDecimalKeepsPrecision`,
  and the `namesAreNotPatternsAndDecimalPrecisionIsCompared` (MySQL, MariaDB, which also
  sync and snapshot under `NO_BACKSLASH_ESCAPES`), `schemaAndTableNamesAreNotMetadataPatterns`
  (PostgreSQL), and `tableNamesAreNotMetadataPatterns` (SQL Server, Oracle, with bare `DEC`
  cells) integration tests. A metadata workaround is verified against every driver and
  under every session mode the code already supports, not per the JDBC javadoc.

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
  drafts committed to a public repo.
- **Missed because:** Workflow quoting and IT serialize paths were not exercised in the
  unit suite; lock dual-acquire lacked failure/ordering contracts; GRANT forms beyond
  `schema.object` were out of the scope matrix; internal drafts were treated as
  project docs.
- **Prevention:** Quote JDBC `-D` URLs; `SchemaSnapshotWriter.writeSnapshot(Connection…)`;
  release Oracle lock on partial failure; acquire PG locks primary-then-legacy only;
  reject `*.*` and `SCHEMA::`; skip UNIQUE-constraint and complex SQL Server indexes;
  pending SQL Server ALTER when live DEFAULT exists; dialect `maxIdentifierLength` in
  declarative validation; remove internal drafts; keep product narrative in
  `docs/DESIGN.md` only. Contracts in `ChangeSetSchemaScopeTest` and
  `SchemaSynchronizerTest`.

## 2026-09-26 — pre-1.3.0 — least-privilege defaults

- **Bug / gaps:** Boot on-by-default DDL; argv passwords; message-only duplicate
  classification; unbounded PG advisory wait; history without actor; dry-run API
  unused; `public` default misleading on SQL Server/Oracle/MySQL.
- **Missed because:** Defaults were chosen for convenience, not least privilege.
- **Prevention:** `enabled` default false; `CliCredentials` env-only password;
  SQLState/vendor-code-only `DuplicateObjectSql`; `pg_try_*` 30s; `applied_by`;
  dialect schema reject for `public` on non-PG; wire `supportsTransactionalDryRun`.

## 2026-09-26 — pre-1.3.0 — schema scope and routine-body policy bypasses

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
  `NonDestructiveSqlPolicyTest`. Publish `needs: [oracle-verify]`; dual-acquire the 1.2.0
  lock alongside the schema-scoped lock.

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

## 2026-09-26 — PR #12 (review 4) — attributes invisible to the type comparison

- **Bug:** `NVARCHAR` over a live `utf8mb4` `VARCHAR` of the same length was reported as
  converged: the national check ran only when some other change was planned. A national
  widen over `utf8mb3_bin` reset the collation. MySQL `VARCHAR DEFAULT 'CURRENT_TIMESTAMP'`
  snapshotted as the expression `CURRENT_TIMESTAMP`. Fractional-second precision was
  discarded on both sides for every engine, so `TIMESTAMP(3) WITH TIME ZONE` vs `(6)` (and
  `DATETIME(3)` vs `DATETIME`) never drifted; Oracle `WITH LOCAL TIME ZONE` folded into
  `WITH TIME ZONE`.
- **Also found while fixing:** Connector/J and MariaDB Connector/J return `DECIMAL_DIGITS = null`
  for `DATETIME`/`TIMESTAMP`/`TIME`, so the MySQL snapshot rule `DATETIME(scale)` never fired
  and `DATETIME(3)` snapshots replayed as `DATETIME`. The unit test passed a scale the driver
  never supplies. The internal review also found that MySQL expression defaults snapshotted
  as `DEFAULT uuid()` (MySQL requires parentheses), and that `shouldSkipAlter` substring-matched
  `IDENTITY`/`SERIAL` inside string literals, silently skipping the whole column comparison.
  A later round found MySQL defaults that the server stores rewritten (`NOW(3)` becomes
  `CURRENT_TIMESTAMP(3)`, `1` becomes `1.00`, `'… 00:00:00'` becomes `'… 00:00:00.000'`) were
  auto-applied with `MODIFY COLUMN` on every sync, because nothing tested apply-then-resync.
  Masking literals in `shouldSkipAlter` then exposed that the parser stripped `IDENTITY` and
  `AUTO_INCREMENT` inside quoted defaults (`'my IDENTITY'` became `'my'`): a fix to one
  literal-blind matcher must audit every other matcher applied to the same text.
- **Missed because:** Attribute checks (charset, collation) were gated on "the planner
  proposed a change", but an attribute that the type comparison ignores can never cause a
  proposed change. Type normalization stripped precision "for comparison" without a cell
  asserting that a precision change drifts. Unit tests fed JDBC metadata values from memory
  instead of from a real driver.
- **Prevention:**
  - Rule (CONTRIBUTING): an attribute the planner does not compare must be checked on
    every sync, not only when a change is planned; add a "types equal, attribute differs"
    cell.
  - Rule (CONTRIBUTING): JDBC metadata fields used by snapshot/compare code must be proven
    against each real driver in an integration test, not assumed in unit tests.
  - `DialectDeclarationContractTest#temporalPrecisionChangesAreNeverSilent`,
    `#mysqlNationalDeclarationReportsCharsetDriftEvenWhenTypesMatch`,
    `#mysqlUnquotedLiteralDefaultsAreQuotedButExpressionsAndNumbersAreNot`,
    `#zonedTemporalFormsParseWithPrecision` (per-engine precision limits),
    `SchemaSynchronizerTest#skipsNextvalIdentityDefaults` (keywords inside literals).
  - Rule (CONTRIBUTING): every auto-applied change needs an apply-then-resync cell; a
    server that stores the value rewritten must not re-apply on the next sync.
    `DialectDeclarationContractTest#mysqlModifyColumnIsPendingWhenItWouldResetUndeclaredAttributes`
    (default lattice), `SchemaSynchronizerMySqlIntegrationTest` and
    `SchemaSynchronizerMariaDbIntegrationTest#appliedDefaultsThatTheServerRewritesConvergeOnTheNextSync`.
  - Integration: `SchemaSynchronizerMySqlIntegrationTest#nationalCharsetCollationAndPrecisionDriftArePending`,
    `SchemaSynchronizerMariaDbIntegrationTest#nationalAndTemporalDeclarationsConvergeAndDriftIsPending`,
    temporal columns and precision-drift cells in the PostgreSQL, SQL Server, and Oracle
    round-trip tests.
- **Round 12 (same class, still missed):** `mySqlBlockReason` accepted `DEFAULT 'COLLATE'`,
  `'CHARACTER SET'`, or `'COMMENT'` as the declared clause, so `MODIFY COLUMN` reset a live
  collation, charset, or comment. The literal-blind audit above covered the parser and
  `shouldSkipAlter`, not this third matcher. MySQL binary defaults (`0x6162`) were quoted into
  `'0x6162'`: permanent pending, and snapshots that did not replay. The snapshot-replay
  integration test had no binary column.
  - Rule (CONTRIBUTING): every keyword check on a column definition runs on
    `SqlLexer.mask`ed text; each needs a cell with the keyword inside a default literal
    (`DialectDeclarationContractTest#keywordsInsideDefaultLiteralsDoNotSatisfyMySqlAttributeChecks`).
  - Rule (CONTRIBUTING): the snapshot-replay integration table covers every type family the
    snapshot writer has a branch for, with a default
    (`DialectDeclarationContractTest#mysqlBinaryDefaultsCompareAsBytes`, binary columns in
    `SchemaSynchronizerMySqlIntegrationTest#strictResyncAndSnapshotReplayHaveNoPendingDrift`).
- **Round 13:** the parser stripped `ON UPDATE` off the default and nothing compared it, so a
  drop-not-null `MODIFY` silently added it and snapshots dropped it: the "attribute the
  planner does not compare" rule was applied to charset/collation, not to clauses the parser
  itself removes. MariaDB reports non-UTF-8 binary bytes as `?`, and control characters
  escaped, so those defaults could not round-trip.
  - Rule (CONTRIBUTING, extended): anything the parser strips from a definition is an attribute
    the planner does not compare; it needs its own every-sync comparison and a snapshot branch
    (`DialectDeclarationContractTest#onUpdateIsParsedOutOfTheDefaultAndComparedOnEverySync`,
    `SchemaSynchronizerMariaDbIntegrationTest#binaryDefaultsAreReadExactlyAndOnUpdateIsCompared`).
  - A value information_schema reports lossily is read another way or kept pending, never
    compared in its lossy form. Round 14 found MySQL also truncates binary defaults at the
    first zero byte, so binary literal defaults were read another way (see rounds 16–17).
  - Round 15: that read needs table `SELECT`, which a DDL-only account lacks, and it ran after
    auto-committed `CREATE TABLE`. Rule (CONTRIBUTING): a new metadata query must state its
    privilege and degrade to pending on access denied, never abort mid-apply
    (`DialectDeclarationContractTest#binaryDefaultsWithoutTableSelectStayPending`).
  - Rounds 16–17: new tables with a binary default still stalled for accounts without SELECT.
    The `DEFAULT(col)` read also answered NULL for every `NOT NULL` column (it needs a real
    row, and the outer-join row is all NULL), falling back to the lossy value silently. It
    failed on a column named like the probe's alias. Every binary cell was nullable, and every
    test account had SELECT, so neither showed up. The read now uses `SHOW CREATE TABLE` with
    binary results: exact on every supported version and needs no SELECT. Rules
    (CONTRIBUTING): a metadata read is proven with a `NOT NULL` cell and an empty table, and a
    least-privilege account runs the convergence test
    (`SchemaSynchronizerMySqlIntegrationTest#binaryDefaultsConvergeForADdlOnlyAccount`, run
    with `-Dschema.test.mysql.jdbc.admin.user/.password`;
    `DialectDeclarationContractTest#binaryDefaultsAreReadFromRawShowCreateTableBytes`).
  - Round 17: `mySqlBlockReason` let `comment_count` in an expression default count as a
    `COMMENT` clause. The parser rejects `COMMENT`/`COLLATE`/`CHARACTER SET`, so those now
    always block; `ON UPDATE`/`AUTO_INCREMENT` match as tokens.
  - Round 18: a declared binary string literal was encoded as UTF-8 for comparison, but the
    server stores the session's `character_set_client` bytes. On a latin1 connection `'é'`
    compared equal to `0xC3A9`, and the widen's `MODIFY COLUMN` rewrote it to `0xE9`. Only
    ASCII literals are compared as bytes now; others stay pending. Rule (CONTRIBUTING): a value
    converted for comparison must be converted the way the server does in every session
    setting, or not compared (`DialectDeclarationContractTest#mysqlBinaryDefaultsCompareAsBytes`,
    `SchemaSynchronizerMySqlIntegrationTest#nonAsciiBinaryStringDefaultsAreNeverRewrittenByAWiden`).

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
- **Coverage:** The schema reference and troubleshooting guide now
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
