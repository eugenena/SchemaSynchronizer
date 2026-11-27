# Issue 16 release audit — 2026-11-27

Scope: sequences, views, stored procedures, and Oracle packages/types in change sets.
The starting point is merged PR 48 (`225041e`). This audit also runs existing database
regressions because the SQL tokenizer and JDBC metadata paths are shared.

## Findings and fixes

| Finding | Fix and prevention |
|---|---|
| Oracle forward declarations were counted as unfinished executable package members | A declaration ending in `;` before a depth-zero `IS`/`AS` does not open a member. Contracts cover procedures, functions, nested declarations, initialization, forbidden initializer SQL, missing `END`, and script boundaries. The live package body has a forward declaration and an observable initializer; catalog verification requires `VALID`. |
| The PostgreSQL procedure test failed after synchronization restored the caller's search path | The test procedure pins its own search path to the generated schema. It is called after synchronization returns, then checked through the view and resynchronized. |
| MariaDB Connector/J 4.0.0 rejected the binary character-set mode used by lossless snapshots | Pin 3.5.10. With 4.0.0 the live suite closes connections when `character_set_results=binary` is used; with 3.5.10 those existing binary-default regressions pass. Reconsider 4.x only after the lossless snapshot path works under its stricter connection rules. |
| The MariaDB collation test assumed only the old spelling | Accept both `utf8_bin` and the server's canonical `utf8mb3_bin`, retaining the required column and unsafe-collation checks. |

## SQL contracts

Oracle forward declarations provide a subprogram heading without a body; its definition
must appear later in the same block. An initializer belongs to the enclosing package body.
PostgreSQL procedure `SET` clauses apply at procedure entry and restore the prior value
at exit; synchronization's transaction-local search path does not establish the search
path for future calls.

Sources: [Oracle forward declarations](https://docs.oracle.com/en/database/oracle/oracle-database/19/lnpls/forward-declaration.html),
[Oracle packages](https://docs.oracle.com/en/database/oracle/oracle-database/21/lnpls/plsql-packages.html),
[PostgreSQL CREATE PROCEDURE](https://www.postgresql.org/docs/16/sql-createprocedure.html).

## Release gates

Validated locally on PostgreSQL 16 (pgvector), MariaDB 10.11, MySQL 8.4, SQL Server
2022, and Oracle XE 21c. The full reactor `mvn verify` with all connection/admin
properties and `schema.test.require.live=true` passed: core 761, Boot 4 integration 2,
CLI 81 tests — **844 total, zero failures, zero skips**. This includes the existing
adoption and identifier-quoting suites, not only the new object-creation cases.

The unsigned release build (`mvn -Prelease -Dgpg.skip=true -DskipTests package`) and
`scripts/check-standalone-licenses.sh` also pass with MariaDB Connector/J 3.5.10.
Engine floor variants and the JDK 17/21 runtime matrix remain CI checks; this local
run does not replace them.

Record live-suite outcomes separately from the offline reactor build: a skip is not a pass.
Use `schema.test.require.live=true` for the selected engines. Run release artifact generation
and the standalone license check against the final dependency versions. Publication also
requires a new, unpublished project version, a matching changelog entry, and the signed-tag
process in the README; this audit does not publish artifacts. Release metadata is now prepared
for 2.4.0 in the release-preparation branch. It remains unpublished; no signed tag, Maven
Central deployment, or GitHub release has been created.
