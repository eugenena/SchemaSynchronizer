# Hand-authoring a schema definition

Hand-editing `schema-definition.json` is a **first-class** workflow. Serializing a
live database is optional bootstrap, not a requirement.

Use this guide when an application owns its schema in source control and applies it
on startup (the Marketworks-style path).

## Mental model

| Artifact | Role |
|---|---|
| `schema-definition.json` (in git) | Desired schema: declarative `tables` plus append-only `changes` |
| Live application database | One JDBC target per environment (local, staging, production) |
| `schema_synchronizer_history` | Checksummed ledger of applied change sets |

SchemaSynchronizer does **not** sync two live databases to each other. The CLI can
serialize *from* a source DB into a file and sync *to* a target DB; Spring Boot
applications normally sync the committed file onto their single datasource.

## Day-to-day workflow

1. Edit `schema-definition.json`:
   - Add or widen columns in `tables` for ordinary additive structure.
   - Append a **new** change set for constraints, triggers, functions, backfills, or
     grants. Never edit a change set that already ran in any environment.
2. Offline check:

   ```bash
   java -jar schema-synchronizer-cli-1.3.0-standalone.jar validate schema-definition.json
   ```

3. Preview against a disposable database:

   ```bash
   export SCHEMA_DB_PASSWORD='…'
   java -jar schema-synchronizer-cli-1.3.0-standalone.jar dry-run \
     jdbc:postgresql://localhost:5432/app_scratch app_user - \
     schema-definition.json public
   ```

4. Commit the definition and deploy. On startup, SchemaSynchronizer converges safe
   differences and records change sets. Destructive diffs fail closed as pending SQL.

## Partial adoption (already-present objects)

Change sets may encounter databases where **some** statements already succeeded
(Flyway leftovers, a prior partial apply, or hand DDL). Starting in 1.3.0:

1. If `verificationSql` is already true, SchemaSynchronizer records the change in
   history and does **not** replay statements.
2. If verification is false, it runs each statement. JDBC errors that mean “object
   already exists” (PostgreSQL `42710` / `42P07` / `42701`, MySQL/MariaDB duplicate
   table/column/key/FK codes) are skipped. Data uniqueness violations (`23505` /
   MySQL `1062`) are **not** skipped. Skipping any statement requires
   `verificationSql`.
3. After the loop, `verificationSql` must pass or startup fails.

Always give multi-statement change sets a verification query that proves the full
postcondition.

## When to serialize instead

Use `serialize` when:

- bootstrapping a definition from a known-good production or staging schema; or
- capturing drift between environments into a reviewable diff.

If the output file already exists, serialization preserves the hand-authored
`changes` array. Review every generated `tables` entry before committing.

## Related guides

- [Schema definition reference](SCHEMA_DEFINITION.md)
- [Migrating from Flyway or another migration tool](MIGRATING_FROM_MIGRATIONS.md)
- [CLI guide](CLI.md)
- [Troubleshooting](TROUBLESHOOTING.md)
