# Greptile lessons

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
