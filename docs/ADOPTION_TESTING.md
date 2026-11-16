# Adoption testing and observability

SchemaSynchronizer 2.3.0 adds the tooling that proves a migration-tool cutover and
observes what each synchronization actually did — without hand-written
`information_schema` queries: **per change-set outcomes**, **structural fingerprints**
for definitions and live schemas, a **JDBC constraint inventory**, a **plain-SQL
baseline splitter**, and **offline profile tooling** (foreign-key preservation findings
and a two-profile compare), exposed as a Java API and as two CLI commands. Everything
except the live fingerprint and the inventory works without a database connection.

The problem these solve: after a cutover, an application's startup synchronization must
behave identically on a fresh database and on every existing database, and a test suite
must be able to *assert* that — which change sets ran, which were adopted through
verification, whether both databases converged, and which foreign keys exist.

## Per change-set outcomes

Every synchronization records one `ChangeSetOutcome` per change set, **in application
order**, on `SchemaSynchronizationResult.changeSets()`:

| Status | Meaning | `executedStatements` | `skippedStatements` |
|---|---|---:|---:|
| `EXECUTED` | The statements ran and the change set was recorded in history | statements that executed | statements skipped because the object already exists |
| `VERIFIED_ADOPTED` | `verificationSql` proved the change is already in effect, so no statement ran and the change set was recorded | 0 | 0 |
| `ALREADY_RECORDED` | The change set was already recorded in the history table by an earlier run | 0 | 0 |
| `SKIPPED_DRY_RUN` | Dry run: the change set would execute; its statements are in `plannedSql` | 0 | 0 |
| `FAILED` | Executing the change set failed and the run aborted | what ran before the failure | what was skipped before the failure |

`durationMs` is the execution time (0 when nothing was executed); `duration()` returns it
as a `Duration`. A legacy database adopting through verification reports `VERIFIED_ADOPTED`
entries; a second, already-converged run reports `ALREADY_RECORDED` entries — the counts
tell adoption from execution without parsing logs.

Wrap the result with `AdoptionReport` for assertions:

```java
SchemaSynchronizationResult result = synchronizer.synchronizeWithResult(connection, definition);
AdoptionReport report = AdoptionReport.from(result);

report.counts();                  // e.g. {EXECUTED=1, ALREADY_RECORDED=61}
report.statusOf("add-cash-fk");   // Optional<ChangeSetOutcome.Status>
report.recordedIds();             // ids recorded in history by this run
report.failed();                  // normally empty
result.plannedSql();              // non-empty only in a dry run
```

`AdoptionReport.from(result)` requires a successful run. When a change set fails, the
synchronization throws `ChangeSetExecutionException`, and the outcomes recorded **before**
the failure stay reachable — everything earlier is known to have been applied, adopted, or
recorded:

```java
try {
    synchronizer.synchronizeWithResult(connection, definition);
} catch (ChangeSetExecutionException failure) {
    AdoptionReport report = AdoptionReport.from(failure);
    // report.failed() holds the failing change set, the last entry in report.all()
}
```

Two details worth knowing:

- On MySQL, MariaDB, Oracle, and H2 the DDL commits implicitly. A failed `AFTER_SCHEMA`
  phase therefore still reports every `BEFORE_SCHEMA` outcome: that earlier work may
  already be committed. Rollback, lock-release, and connection-restore problems are
  attached to the exception as suppressed errors, never dropped.

## Structural fingerprints

`SchemaFingerprints` computes two stable SHA-256 hashes (returned as
`sha256:<64 hex characters>`) with one promise: two schemas that are equally correct
produce the same hash. Formatting, JSON key order, physical column order, the machine's
default locale, and JDBC row order never participate.

### Definition fingerprint

`SchemaFingerprints.definitionFingerprint(definition)` hashes the *parsed* shape of a
definition, not its JSON text:

- tables sorted by folded name, each with its whitespace-normalized `createSql`
  (whitespace outside string literals and quoted identifiers collapses — `DEFAULT 'a  b'`
  and `DEFAULT 'a b'` stay distinct because string contents are data);
- columns sorted by name, hashed as `name:definition`;
- indexes sorted.

Absent `tables`, `columns`, or `indexes` count as empty, matching synchronization, and a
`null` definition is rejected. Use it to detect drift between profile files or branches:

```java
String postgres = SchemaFingerprints.definitionFingerprint(postgresDefinition);
String h2 = SchemaFingerprints.definitionFingerprint(h2Definition);
```

The two hashes of differently-spelled dialect profiles will differ — the fingerprint is
for comparing the *same* schema over time or across branches, not across dialects.

### Live fingerprint

`SchemaFingerprints.liveSchemaFingerprint(connection, options)` hashes the live schema as
JDBC reports it:

- user tables (`TABLE` type only — views are excluded), with the synchronization history
  table always excluded;
- each table's columns as `name:TYPE_NAME:nullable=N`, **sorted by name**, so physical
  column order never participates;
- foreign keys as `table:column->referencedTable:referencedColumn:delete=RULE`, sorted.

The reads go through the same dialect-aware name mapping as the synchronizer: the
configured schema is resolved to its stored spelling (`app` finds Oracle/H2 `APP`), each
table's exact catalog name is used for the JDBC lookups, and rows that a `_`/`%` wildcard
merely admitted (requesting `app_db` also matching `app1db`) are excluded.

The classic adoption assertion — a migrated legacy database and a fresh database are
equally correct, and an idempotent second sync changes nothing:

```java
SchemaSynchronizerOptions options = SchemaSynchronizerOptions.defaults();

String legacyHash = SchemaFingerprints.liveSchemaFingerprint(legacyConnection, options);
synchronizer.synchronizeWithResult(freshConnection, definition);
String freshHash = SchemaFingerprints.liveSchemaFingerprint(freshConnection, options);
assertThat(freshHash).isEqualTo(legacyHash);

String afterSecondSync = SchemaFingerprints.liveSchemaFingerprint(freshConnection, options);
assertThat(afterSecondSync).isEqualTo(freshHash);
```

The fingerprint covers what JDBC metadata reports — tables, columns (name, type,
nullability), and foreign keys. Indexes, views, check constraints, and row data are
outside it; judge those with the declarative planner's `pendingSql` and your own data
checks. Compare fingerprints only within one SchemaSynchronizer release: the hashing
rules are versioned with the library.

## Constraint inventory

`ConstraintInventory` reads constraints through `DatabaseMetaData` with the same
dialect-aware matching as the synchronizer:

- `foreignKeys(connection, schema, excludeTable)` — every foreign key as
  `ForeignKey(table, column, referencedTable, referencedColumn, deleteRule)`, sorted by
  table and column. `deleteRule` is one of `CASCADE`, `SET NULL`, `SET DEFAULT`,
  `RESTRICT`, or `NO ACTION`.
- `countForeignKeys(connection, schema, excludeTable)` — the count of the list above.
- `primaryKeys(connection, schema, table)` — column names in **declaration order**
  (`KEY_SEQ`), so composite keys compare correctly instead of alphabetically.

The `schema` argument follows the synchronizer's namespace rules: `app` finds Oracle/H2
`APP` and PostgreSQL `app`; pass `null` for the driver's default (H2's `PUBLIC` when no
schema is selected). The table argument is folded the same way, so `child` finds H2's
`CHILD`. Rows that a LIKE wildcard merely admitted are filtered out, and `excludeTable`
removes one table (typically the history table) from the walk:

```java
assertThat(ConstraintInventory.countForeignKeys(connection, "app",
        "schema_synchronizer_history")).isEqualTo(62);

assertThat(ConstraintInventory.foreignKeys(connection, "app", null))
        .filteredOn(fk -> fk.table().equals("bank_receipt_job"))
        .singleElement()
        .satisfies(fk -> {
            assertThat(fk.column()).isEqualTo("cash_event_id");
            assertThat(fk.deleteRule()).isEqualTo("CASCADE");
        });

assertThat(ConstraintInventory.primaryKeys(connection, "app", "cash_event"))
        .containsExactly("id", "posted_at");   // declaration order, not alphabetical
```

## Replaying a SQL baseline

Adoption tests need a production-shaped legacy database. `SqlScriptSplitter.split(script,
dialectId)` turns a plain SQL script — typically a versioned snapshot of the pre-cutover
schema — into executable statements:

- Statement boundaries are dialect-aware: `h2`, blank, and unknown ids follow the
  PostgreSQL rules (what H2 in `MODE=PostgreSQL` expects); `postgresql`, `oracle`,
  `mysql`, `mariadb`, and `sqlserver` use their own rules.
- Routines stay intact: a PostgreSQL dollar-quoted body, a MySQL compound statement, or
  an Oracle PL/SQL block is one statement. On Oracle the block ends at the `END` closing
  its outermost block (plus an optional `END name;`), and local procedure and function
  declarations stay inside their parent, so the script continues with the statements
  after it.
- String literals and quoted identifiers never end a statement, and each statement is
  returned as the **original slice of the script**: whitespace inside strings and the
  newline after an interior `--` comment survive, so
  `INSERT INTO notes VALUES ('a  b');` still inserts `'a  b'`.
- Comments between statements are dropped, and a missing final `;` is appended.
- An unknown dialect id is rejected, as is input whose routine bodies do not balance.

```java
String baseline = Files.readString(Path.of("src/main/resources/db/migration/legacy-baseline.sql"));
for (String statement : SqlScriptSplitter.split(baseline, "postgresql")) {
    String executable = statement.endsWith(";")
            ? statement.substring(0, statement.length() - 1)
            : statement;
    try (Statement jdbc = connection.createStatement()) {
        jdbc.execute(executable);
    }
}
```

## Offline profile tooling

Definitions that exist per dialect — for example `schema-definition-postgresql.json` and
`schema-definition-h2.json` for the same application — can drift apart silently. Two
offline checks keep them honest; neither needs a database connection.

### Foreign-key preservation coverage

`SchemaDefinitionValidator.preservationFindings(definition)` returns one human-readable
finding per table whose `createSql` declares an inline `FOREIGN KEY` that no change set's
`verificationSql` claims. Coverage matching reads SQL tokens, not substrings: a line
break inside `FOREIGN KEY` is still found, a mention of `order_items` does not cover
`items`, and a table name passed as a string literal (as catalog queries do) counts. An
empty list means every inline foreign key is claimed.

Why it matters: on a fresh database the declarative planner creates the constraint from
`createSql`; on an existing database where the table already exists, only a change set
whose `verificationSql` proves the constraint can adopt it. An uncovered foreign key
means adoption cannot verify the constraint without re-executing DDL.

### Two-profile compare

`SchemaDefinitionValidator.compare(left, right)` reports tables and columns that exist on
only one side (`table only in the second profile: items`). Type spelling differences
between dialects are expected and not reported. An empty list means the profiles agree.

### The CLI commands

```text
compare <schema-file-a> <schema-file-b>
fingerprint <schema-file>
```

`compare` prints `diff: …` lines for structural differences and
`preservation: <file>: …` lines for each profile's uncovered foreign keys — **both**
files are checked, and each finding is labeled with the file it belongs to. It exits `0`
when the profiles agree, `1` when they differ (or a file cannot be read), and `2` for
usage errors. `fingerprint` prints the definition fingerprint hash and exits `0`.


- Dry runs record `ALREADY_RECORDED` and `SKIPPED_DRY_RUN` outcomes with
  `changeSetsApplied() == 0`, and the statements they would run in `plannedSql`.

## Putting it together: an adoption test

The pieces compose into the cutover proof from
[MIGRATING_FROM_MIGRATIONS.md](MIGRATING_FROM_MIGRATIONS.md#4-prove-both-paths). The
shape below is what the maintainers run against H2 in `MODE=PostgreSQL` and PostgreSQL:

```java
// 1. Build the production-shaped legacy database from a versioned SQL baseline.
for (String statement : SqlScriptSplitter.split(baselineSql, "postgresql")) {
    try (Statement jdbc = legacy.createStatement()) {
        jdbc.execute(statement.endsWith(";")
                ? statement.substring(0, statement.length() - 1) : statement);
    }
}

// 2. The empty database applies the complete definition.
synchronizer.synchronizeWithResult(fresh, definition);

// 3. The legacy database adopts: verified change sets are recorded, partial
//    applies skip already-present statements, and nothing fails.
AdoptionReport adoption = AdoptionReport.from(synchronizer.synchronizeWithResult(legacy, definition));
assertThat(adoption.failed()).isEmpty();
assertThat(adoption.statusOf("add-cash-fk")).contains(ChangeSetOutcome.Status.VERIFIED_ADOPTED);

// 4. Both databases converge: same live fingerprint, same foreign keys.
assertThat(SchemaFingerprints.liveSchemaFingerprint(fresh, options))
        .isEqualTo(SchemaFingerprints.liveSchemaFingerprint(legacy, options));
assertThat(ConstraintInventory.countForeignKeys(legacy, "app", historyTable))
        .isEqualTo(42);

// 5. A second run is a no-op that changes nothing.
String beforeSecondRun = SchemaFingerprints.liveSchemaFingerprint(legacy, options);
synchronizer.synchronizeWithResult(legacy, definition);
assertThat(SchemaFingerprints.liveSchemaFingerprint(legacy, options)).isEqualTo(beforeSecondRun);
```

## Notes and limits

- Outcomes and fingerprints describe what JDBC metadata reports: tables, columns (name,
  type, nullability), and foreign keys. Indexes, views, check constraints, and row data
  are outside the fingerprint — judge those with `pendingSql` and your own data checks.
- The history table is excluded everywhere; pass its name as `excludeTable` to the
  inventory, and `liveSchemaFingerprint` excludes it automatically.
- Fingerprints are stable across machines, locales, and JDBC row order, and identical
  schemas always hash identically. They are **not** guaranteed stable across
  SchemaSynchronizer releases: compare within one release.
- Outcomes exist only in the Java API; the CLI's `sync` output remains a log, not a
  report.

## API quick reference

| Class | Entry points |
|---|---|
| `ChangeSetOutcome` | record: `changeSetId()`, `status()`, `executedStatements()`, `skippedStatements()`, `durationMs()`, `duration()` |
| `AdoptionReport` | `from(result)`, `from(failure)`, `all()`, `byStatus()`, `counts()`, `executed()`, `verifiedAdopted()`, `alreadyRecorded()`, `skippedDryRun()`, `failed()`, `statusOf(id)`, `recordedIds()`, `result()` |
| `SchemaFingerprints` | `definitionFingerprint(definition)`, `liveSchemaFingerprint(connection, options)` |
| `ConstraintInventory` | `foreignKeys(connection, schema, excludeTable)`, `countForeignKeys(…)`, `primaryKeys(connection, schema, table)` |
| `SqlScriptSplitter` | `split(script, dialectId)` |
| `SchemaDefinitionValidator` | `preservationFindings(definition)`, `compare(left, right)` |
| CLI | `compare <schema-file-a> <schema-file-b>`, `fingerprint <schema-file>` |

## Related guides

- [Migrating from Flyway or another migration tool](MIGRATING_FROM_MIGRATIONS.md)
- [Command-line guide](CLI.md)
- [Hand-authoring the definition](HAND_AUTHORING.md)
- [Schema definition reference](SCHEMA_DEFINITION.md)

