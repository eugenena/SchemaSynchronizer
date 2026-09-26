# Command-line guide

SchemaSynchronizer 1.3.1 publishes a self-contained executable JAR with four
commands:

- `serialize` captures the declarative portion of a live source schema.
- `validate` checks a definition file offline (hand-authoring).
- `dry-run` previews convergence against a live target without committing work
  on transactional dialects (PostgreSQL, SQL Server); rehearse MariaDB/MySQL/Oracle
  against a disposable instance.
- `sync` applies a definition to a target and reports manual work.

The JAR requires Java 21 and includes PostgreSQL, MariaDB, MySQL, SQL Server, and
Oracle JDBC drivers.

## Download and verify

```bash
curl -fLO https://repo1.maven.org/maven2/com/thinkaillc/schema-synchronizer-cli/1.3.1/schema-synchronizer-cli-1.3.1-standalone.jar
curl -fLO https://repo1.maven.org/maven2/com/thinkaillc/schema-synchronizer-cli/1.3.1/schema-synchronizer-cli-1.3.1-standalone.jar.sha256
```

Compare the JAR's SHA-256 digest with the downloaded checksum before execution. The
same executable and checksum files are attached to the GitHub release.

```bash
java -jar schema-synchronizer-cli-1.3.1-standalone.jar --version
java -jar schema-synchronizer-cli-1.3.1-standalone.jar --help
```

Use the environment for credentials so passwords do not appear in shell history or
process arguments:

```bash
export SCHEMA_DB_PASSWORD='replace-me'
```

## Validate a hand-authored definition

```text
validate <schema-file> [schema]
```

```bash
java -jar schema-synchronizer-cli-1.3.1-standalone.jar validate schema-definition.json
java -jar schema-synchronizer-cli-1.3.1-standalone.jar validate schema-definition.json public
```

No database connection is required. The command checks format, dialect, declarative
targets, SQL safety policy, and change-set structure.

## Dry-run against a target

```text
dry-run <jdbc-url> <user> <password-or--> <schema-file> [schema] [history-table]
```

```bash
java -jar schema-synchronizer-cli-1.3.1-standalone.jar dry-run \
  jdbc:postgresql://localhost:5432/target_app app_user - \
  schema-definition.json public schema_synchronizer_history
```

## Serialize a source database

```text
serialize <jdbc-url> <user> <password-or--> <schema> <output-path>
```

PostgreSQL:

```bash
java -jar schema-synchronizer-cli-1.3.1-standalone.jar serialize \
  jdbc:postgresql://localhost:5432/source_app app_user - public schema-definition.json
```

MySQL:

```bash
java -jar schema-synchronizer-cli-1.3.1-standalone.jar serialize \
  jdbc:mysql://localhost:3306/source_app app_user - source_app schema-definition.json
```

MariaDB uses a `jdbc:mariadb:` URL and `mariadb` dialect. If the output file already
exists, serialization preserves its hand-authored `changes` array. Always review
the generated definition before committing it. Serialize is optional; hand-authoring
is documented in [HAND_AUTHORING.md](HAND_AUTHORING.md).

## Synchronize a target database

```text
sync <jdbc-url> <user> <password-or--> <schema-file> [schema] [history-table]
```

```bash
java -jar schema-synchronizer-cli-1.3.1-standalone.jar sync \
  jdbc:postgresql://localhost:5432/target_app app_user - \
  schema-definition.json public schema_synchronizer_history
```

The process exits unsuccessfully when synchronization fails or pending manual SQL
remains under the default safety policy. Capture the full output in deployment logs.

Exit statuses are stable for automation:

| Status | Meaning |
|---:|---|
| `0` | Command completed successfully |
| `1` | Serialization, connection, validation, or synchronization failed |
| `2` | Command name or CLI usage was invalid |

## Build from source

```bash
git clone https://github.com/eugenena/SchemaSynchronizer.git
cd SchemaSynchronizer
mvn clean package
java -jar schema-synchronizer-cli/target/schema-synchronizer-cli-1.3.1-standalone.jar --help
```

## Credential rules

- Prefer `-` plus `SCHEMA_DB_PASSWORD`.
- Use a dedicated database account with catalog-read and required DDL privileges.
- Do not commit passwords or place them directly in scripts.
- Rotate any credential exposed in command history or CI output.

See [OPERATIONS.md](OPERATIONS.md) for deployment sequencing and recovery.
