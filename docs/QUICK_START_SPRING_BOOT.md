# Five-minute Spring Boot quick start

This guide adds SchemaSynchronizer 1.4.0 to an existing Spring Boot application.
It requires Java 21, a configured JDBC `DataSource`, and PostgreSQL 16+, MariaDB
10.3+, or MySQL 8.0+.

## 1. Add the dependency

```xml
<dependency>
  <groupId>com.thinkaillc</groupId>
  <artifactId>schema-synchronizer</artifactId>
  <version>1.4.0</version>
</dependency>
```

The artifact is published on Maven Central; no additional repository is required.

## 2. Add the desired schema

Create `src/main/resources/schema-definition.json`. This PostgreSQL example owns
one table and one index:

```json
{
  "formatVersion": 2,
  "dialect": "postgresql",
  "tables": {
    "customers": {
      "createSql": "CREATE TABLE IF NOT EXISTS customers (id BIGINT PRIMARY KEY, email VARCHAR(320) NOT NULL)",
      "columns": [
        {"name": "id", "definition": "BIGINT NOT NULL"},
        {"name": "email", "definition": "VARCHAR(320) NOT NULL"}
      ],
      "indexes": [
        "CREATE UNIQUE INDEX IF NOT EXISTS idx_customers_email ON customers (email)"
      ]
    }
  },
  "changes": []
}
```

For MariaDB use `"dialect": "mariadb"`; for MySQL use
`"dialect": "mysql"`. SQL inside a definition must use the selected database's
native syntax. Definitions are not cross-database SQL translations.

## 3. Configure startup

```properties
schema-synchronizer.enabled=true
schema-synchronizer.resource=/schema-definition.json
schema-synchronizer.schema=public
schema-synchronizer.fail-on-pending=true
schema-synchronizer.require-definition=true
spring.jpa.hibernate.ddl-auto=validate
```

For MariaDB and MySQL, `schema-synchronizer.schema` is the database/catalog name,
not `public`. For SQL Server use `dbo` (or your app schema). For Oracle use the
connected user/schema name.

Auto-configuration is **opt-in**: `schema-synchronizer.enabled` defaults to
`false` and must be set to `true`.

Do not configure Hibernate, Flyway, or Liquibase to mutate the schema at the same
time. During a migration, follow the controlled cutover in
[MIGRATING_FROM_MIGRATIONS.md](MIGRATING_FROM_MIGRATIONS.md).

## 4. Start the application

At startup SchemaSynchronizer:

1. loads and validates the definition;
2. detects the live database dialect;
3. obtains a database lock;
4. applies supported non-destructive differences;
5. records completed explicit change sets; and
6. lets Hibernate validate the resulting schema.

With the default `fail-on-pending=true`, startup fails when a destructive or
ambiguous change needs human review. The log contains copyable pending SQL. Review
it, execute it intentionally, and restart the application.

## 5. Make the next schema change

Add a nullable column to the definition, for example:

```json
{"name": "display_name", "definition": "VARCHAR(160)"}
```

SchemaSynchronizer adds it automatically on the next startup. Removing a column
from the definition does not drop it automatically; it produces pending operator
SQL instead.

Before production use, read the [operations and safety runbook](OPERATIONS.md).
