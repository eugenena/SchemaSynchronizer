# Command-line guide

SchemaSynchronizer 1.0.0 exposes two Java entry points:

- `SchemaSerializer` captures the declarative portion of a live source schema.
- `SchemaSynchronizer` applies a definition to a target and reports manual work.

The 1.0.0 CLI is run from a source checkout with Maven; it is not yet distributed
as a self-contained executable.

## Prepare the checkout

```bash
git clone https://github.com/eugenena/SchemaSynchronizer.git
cd SchemaSynchronizer
mvn clean install
```

Use the environment for credentials so passwords do not appear in shell history or
process arguments:

```bash
export SCHEMA_DB_PASSWORD='replace-me'
```

## Serialize a source database

```text
SchemaSerializer <jdbc-url> <user> <password-or--> <schema> <output-path>
```

PostgreSQL:

```bash
mvn -pl schema-synchronizer exec:java \
  -Dexec.mainClass=com.thinkaillc.schemasynchronizer.SchemaSerializer \
  -Dexec.args="jdbc:postgresql://localhost:5432/source_app app_user - public schema-definition.json"
```

MySQL:

```bash
mvn -pl schema-synchronizer exec:java \
  -Dexec.mainClass=com.thinkaillc.schemasynchronizer.SchemaSerializer \
  -Dexec.args="jdbc:mysql://localhost:3306/source_app app_user - source_app schema-definition.json"
```

MariaDB uses a `jdbc:mariadb:` URL and `mariadb` dialect. If the output file already
exists, serialization preserves its hand-authored `changes` array. Always review
the generated definition before committing it.

## Synchronize a target database

```text
SchemaSynchronizer <jdbc-url> <user> <password-or--> <schema-file> [schema] [history-table]
```

```bash
mvn -pl schema-synchronizer exec:java \
  -Dexec.mainClass=com.thinkaillc.schemasynchronizer.SchemaSynchronizer \
  -Dexec.args="jdbc:postgresql://localhost:5432/target_app app_user - schema-definition.json public schema_synchronizer_history"
```

The process exits unsuccessfully when synchronization fails or pending manual SQL
remains under the default safety policy. Capture the full output in deployment logs.

## Credential rules

- Prefer `-` plus `SCHEMA_DB_PASSWORD`.
- Use a dedicated database account with catalog-read and required DDL privileges.
- Do not commit passwords or place them directly in scripts.
- Rotate any credential exposed in command history or CI output.

See [OPERATIONS.md](OPERATIONS.md) for deployment sequencing and recovery.
