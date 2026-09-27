# Contributing to SchemaSynchronizer

Thank you for helping improve SchemaSynchronizer. Bug reports, documentation fixes,
tests, and database-dialect contributions are welcome.

## Before opening an issue

- Search existing issues.
- Confirm the behavior on the latest released version or current `main`.
- Remove credentials and production data from logs and definitions.
- Include the database product/version, Java version, library version, expected
  result, actual result, and a minimal reproduction.

Report security vulnerabilities privately as described in [SECURITY.md](SECURITY.md).

## Development

Requirements: Java 21+, Maven 3.9+, Docker for database integration tests.

```bash
git clone https://github.com/eugenena/SchemaSynchronizer.git
cd SchemaSynchronizer
mvn test
```

Run integration tests against the databases affected by a change. Changes to shared
code (planner, type normalization, snapshot writer, change-set guardrails, executor)
affect every dialect: run the integration suite against all five engines and confirm
no integration test was skipped. Commands and properties are documented in
[README.md](README.md#testing-and-development).

## Pull requests

- Keep changes focused and explain the behavior being corrected or added.
- Add regression tests for bug fixes.
- Update user documentation for public behavior changes.
- Preserve fail-closed behavior and the manual boundary for destructive changes.
- Do not weaken SQL validation to accommodate one input shape.
- Code that emits SQL (executed or reported as pending) needs a test that loops over
  every `DatabaseDialect` and asserts syntax valid on the oldest supported version of
  each (for example, Oracle 19c has no `IF [NOT] EXISTS`).
- Code that compares schema, catalog, or object names must follow the dialect's
  identifier folding (`ChangeSetSchemaScope.canonical`), never `equalsIgnoreCase` or
  `toLowerCase` on both sides; test mixed-case configured names per dialect.
- Type normalization may merge two type names only when every engine stores them
  identically (for example `INT`/`INTEGER`). Keep lossy pairs distinct (`NCHAR`/`CHAR`,
  `BINARY`/`VARBINARY`) and send conversions between them to pending.
- An attribute the type comparison ignores (character set, collation, precision, and any
  clause the parser strips, such as `ON UPDATE`) must be checked on every sync, not only when
  another change is planned, and written by the snapshot; test the cell where the types are
  equal and the attribute differs. A value the server reports lossily is pending, never
  compared in its lossy form.
- JDBC metadata fields that snapshot or compare code relies on (`DECIMAL_DIGITS`,
  `COLUMN_SIZE`, `TYPE_NAME`) must be proven against each real driver in an integration
  test; drivers disagree (MySQL and MariaDB drivers report no temporal precision).
- A type the parser newly accepts (a new spelling or attribute such as `UNSIGNED`) needs a
  comparison cell against a live column of different length, precision, or scale, and one
  for the bare form without precision on every engine, which compares as that engine's
  default (`DECIMAL` is `(10,0)` on MySQL/MariaDB, `(18,0)` on SQL Server, `(38,0)` on
  Oracle, unbounded on PostgreSQL). Arithmetic on
  `BigDecimal` precision and scale uses `long`. When a change makes a spelling normalize to a
  portable type (`FIXED`, `NUMBER` → `NUMERIC`), reject it in validation on every engine that
  does not accept it, with a cell per engine, because comparison can no longer tell it apart.
- Rows from `getTables`, `getColumns`, and `getPrimaryKeys` go through
  `DatabaseDialect.isRequestedObject`: drivers match those schema and table arguments with
  `LIKE` (Oracle even the `getPrimaryKeys` schema), and escaping them breaks Connector/J under
  `NO_BACKSLASH_ESCAPES`. Pass names unescaped. Integration fixtures include a
  sibling name that differs only where the real name has `_`.
- A check that makes a change pending must not fire where the change is already safe. Add a cell
  for the converged case: the live value already satisfies it (a legacy character set that
  stores the default; a change set that does not touch the table). When the answer depends on
  server character sets or collations, ask the server, not a Java approximation or an
  `information_schema` join: MariaDB 10.10+ reports `utf8mb4_uca1400_ai_ci` in `TABLES` but
  `uca1400_ai_ci` in `COLLATIONS`. Run such integration tests on MariaDB 10.3 and 11.x as well.
- A refusal meant to precede all DDL has to cover what runs between the preflight and the
  apply pass: `BEFORE_SCHEMA` change sets, and the column list of a table the preflight skips
  because it does not exist yet.
- A column-change path must pass the strict round trip: a second sync with
  `failOnPending=true` reports nothing, and a snapshot replayed into an empty schema
  reports nothing.
- Every auto-applied change needs an apply-then-resync integration cell. Servers rewrite
  what they store (`NOW(3)` is reported as `CURRENT_TIMESTAMP(3)`, `1` as `1.00`); compare in
  the stored form, or send the change to pending when the stored form is not predictable.
- Keyword checks on a column definition (`COLLATE`, `COMMENT`, `IDENTITY`, `TIMESTAMP`, …) run
  on `SqlLexer.mask`ed text, and each has a cell with the keyword inside a default literal.
- A new metadata query documents the privilege it needs (OPERATIONS least privilege) and
  degrades to pending when access is denied; it must never fail a sync after DDL has run.
  Prove it with a `NOT NULL` column on an empty table and against a least-privilege account
  (no `SELECT` on application tables), not only against the test superuser.
- A value converted for comparison (charset encoding, rounding, padding) must be converted the
  way the server does under every session setting, or be left pending; test it over a
  non-UTF-8 connection.
- A keyword check on a definition matches tokens (`\bON\s+UPDATE\b`), never substrings, so an
  identifier such as `comment_count` in an expression default is not a clause.
- The snapshot-replay integration table covers every type family the snapshot writer has a
  branch for, each with a default, so every written default form is proven to replay.
- Change-set allowlist rules must inspect the whole statement, including trailing
  top-level clauses and routine bodies, not only the leading keyword; add the bypass
  and its legitimate neighbour to `GuardrailBypassTest`.
- Do not edit already released change-set examples in ways that encourage checksum
  mutation.

New dialects must meet [the dialect acceptance contract](docs/CONTRIBUTING_A_DIALECT.md).

By submitting a contribution, you agree that it is licensed under the Apache License
2.0 under the same terms as the project.
