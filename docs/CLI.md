# Command-line guide

SchemaSynchronizer 2.0.0 publishes a self-contained executable JAR with four
working commands (plus `help` / `--help` / `-h` and `version` / `--version` / `-V`):

- `serialize` captures the declarative portion of a live source schema.
- `validate` checks a definition file offline (hand-authoring).
- `dry-run` previews convergence against a live target without committing work
  on transactional dialects (PostgreSQL, SQL Server); rehearse MariaDB/MySQL/Oracle
  against a disposable instance.
- `sync` applies a definition to a target and reports manual work.

The JAR requires Java 17 or later and includes PostgreSQL, MariaDB, MySQL, SQL Server, and
Oracle JDBC drivers.

Connect as a schema-scoped, least-privilege database account, never a superuser, `sa`,
`root`, or `SYSTEM`: the change-set SQL checks catch mistakes, they are not a security
boundary. Grants per engine:
[Least-privilege database account](OPERATIONS.md#least-privilege-database-account-required).

## Licenses in the standalone JAR

The executable JAR bundles third-party code, so it is distributed under several licenses:

| Component | License |
|---|---|
| SchemaSynchronizer | Apache-2.0 |
| MySQL Connector/J | GPLv2 with the Universal FOSS Exception |
| MariaDB Connector/J | LGPL-2.1-or-later |
| Oracle JDBC (`ojdbc11`) | Oracle Free Use Terms and Conditions |
| PostgreSQL JDBC | BSD-2-Clause |
| Microsoft JDBC Driver for SQL Server | MIT |
| Protocol Buffers (used by Connector/J) | BSD-3-Clause |
| Jackson | Apache-2.0 |
| SLF4J | MIT |
| Checker Framework qualifiers (used by PostgreSQL JDBC) | MIT |

The JAR root holds SchemaSynchronizer's `LICENSE` and `NOTICE`; `META-INF/licenses/THIRD-PARTY.txt`
lists every bundled artifact, and `META-INF/licenses/<artifact>/` holds its license text. Review
these terms before redistributing the JAR. If you need only Apache-2.0 code, depend on the
`schema-synchronizer` library artifact instead: it bundles no third-party code and declares the
JDBC drivers as optional dependencies.

## Download and verify

```bash
curl -fLO https://repo1.maven.org/maven2/com/thinkaillc/schema-synchronizer-cli/2.0.0/schema-synchronizer-cli-2.0.0-standalone.jar
curl -fLO https://repo1.maven.org/maven2/com/thinkaillc/schema-synchronizer-cli/2.0.0/schema-synchronizer-cli-2.0.0-standalone.jar.sha256
```

Compare the JAR's SHA-256 digest with the downloaded checksum before execution.

```bash
java -jar schema-synchronizer-cli-2.0.0-standalone.jar --version
java -jar schema-synchronizer-cli-2.0.0-standalone.jar --help
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
java -jar schema-synchronizer-cli-2.0.0-standalone.jar validate schema-definition.json
java -jar schema-synchronizer-cli-2.0.0-standalone.jar validate schema-definition.json public
```

No database connection is required. The command checks format, dialect, declarative
targets, SQL safety policy, and change-set structure.

Without a connection there is no history table, so `validate` applies the SQL safety policy
to every change set. `sync` and `dry-run` apply it only to change sets not yet recorded in
history. A change set applied under an earlier release can therefore fail `validate` while
`sync` accepts it. Leave it unchanged (editing it breaks its checksum) and check the rest
with `dry-run` against a database that records it. See
[SCHEMA_DEFINITION.md](SCHEMA_DEFINITION.md#change-sets).

## Dry-run against a target

```text
dry-run <jdbc-url> <user> - <schema-file> [schema] [history-table]
```

```bash
java -jar schema-synchronizer-cli-2.0.0-standalone.jar dry-run \
  jdbc:postgresql://localhost:5432/target_app app_user - \
  schema-definition.json public schema_synchronizer_history
```

## Serialize a source database

```text
serialize <jdbc-url> <user> - <schema> <output-path>
```

PostgreSQL:

```bash
java -jar schema-synchronizer-cli-2.0.0-standalone.jar serialize \
  jdbc:postgresql://localhost:5432/source_app app_user - public schema-definition.json
```

MySQL:

```bash
java -jar schema-synchronizer-cli-2.0.0-standalone.jar serialize \
  jdbc:mysql://localhost:3306/source_app app_user - source_app schema-definition.json
```

SQL Server (schema must equal the login's `DEFAULT_SCHEMA`):

```bash
java -jar schema-synchronizer-cli-2.0.0-standalone.jar serialize \
  "jdbc:sqlserver://localhost:1433;databaseName=app;encrypt=true;trustServerCertificate=true" \
  schema_sync - app schema-definition.json
```

`trustServerCertificate=true` skips certificate validation; use it only against local or test
servers with self-signed certificates, and keep `encrypt=true`.

Oracle (schema is the connected user):

```bash
java -jar schema-synchronizer-cli-2.0.0-standalone.jar serialize \
  jdbc:oracle:thin:@localhost:1521/XEPDB1 schema_sync - schema_sync schema-definition.json
```

The optional `[schema]` of `sync`, `dry-run`, and `validate` defaults to `public`, which is
rejected for SQL Server, Oracle, MySQL, and MariaDB definitions; pass it explicitly there.

MariaDB uses a `jdbc:mariadb:` URL and `mariadb` dialect. If the output file already
exists, serialization preserves its hand-authored `changes` array. Always review
the generated definition before committing it. Serialize is optional; hand-authoring
is documented in [HAND_AUTHORING.md](HAND_AUTHORING.md).

## Synchronize a target database

```text
sync <jdbc-url> <user> - <schema-file> [schema] [history-table]
```

```bash
java -jar schema-synchronizer-cli-2.0.0-standalone.jar sync \
  jdbc:postgresql://localhost:5432/target_app app_user - \
  schema-definition.json public schema_synchronizer_history
```

The process exits unsuccessfully when synchronization fails or pending manual SQL
remains under the default safety policy. Capture the full output in deployment logs.
A failure is printed as one `SchemaSynchronizer failed: …` line without a stack trace;
`password=`, `pwd=`, and similar parameters and URL credentials in the message are masked as
`****`.

Exit statuses are stable for automation:

| Status | Meaning |
|---:|---|
| `0` | Command completed successfully |
| `1` | Serialization, connection, validation, or synchronization failed, or manual SQL is pending |
| `2` | Unknown command or wrong number of arguments (other usage errors, such as a literal password, exit `1`) |

## Build from source

```bash
git clone https://github.com/eugenena/SchemaSynchronizer.git
cd SchemaSynchronizer
mvn clean package
java -jar schema-synchronizer-cli/target/schema-synchronizer-cli-2.0.0-standalone.jar --help
```

## Credential rules

- Prefer `-` plus `SCHEMA_DB_PASSWORD` (literal passwords on argv are rejected).
- Optional `SCHEMA_SYNCHRONIZER_ACTOR` is written to history `applied_by`.
- Use a dedicated, schema-scoped database account with catalog-read and the required DDL
  privileges only; see [OPERATIONS.md](OPERATIONS.md#least-privilege-database-account-required).
- Do not commit passwords or place them directly in scripts.
- Rotate any credential exposed in command history or CI output.

See [OPERATIONS.md](OPERATIONS.md) for deployment sequencing and recovery.
