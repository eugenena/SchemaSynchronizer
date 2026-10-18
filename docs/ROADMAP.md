# Roadmap

Planned work for releases after 2.0.0. Items are tracked as GitHub issues; this page
summarizes them and their intended order. Nothing here is a commitment to a date.

## Change-set support for more object types

Tracked in [#16](https://github.com/eugenena/SchemaSynchronizer/issues/16).

Today, change sets can create tables, indexes, functions, and table triggers, and can run
additive `ALTER TABLE`, `INSERT`, `UPDATE`, `COMMENT ON`, and object-level `GRANT`.
They reject:

| Object | Current state | Planned |
|---|---|---|
| Sequences | `CREATE SEQUENCE` rejected; identity columns (`GENERATED … AS IDENTITY`, `BIGSERIAL`, `AUTO_INCREMENT`, `IDENTITY(1,1)`) work in column definitions | `CREATE SEQUENCE` with schema-scope checks |
| Views | `CREATE VIEW` rejected | `CREATE [OR REPLACE] VIEW` with its query checked as read-only and schema-scoped |
| Stored procedures | `CREATE PROCEDURE` rejected | `CREATE [OR REPLACE] PROCEDURE` with the same body checks as functions |
| Oracle packages and types | Rejected | Package spec and body as separate statements, with each routine body checked |

Until then, create these objects outside SchemaSynchronizer, for example with your
database provisioning or a migration tool run by a privileged role, and reference them
from change sets and verification SQL as needed.

## Adoption gaps found by LedgerPulse

These two gaps block replacing Flyway in Spring Boot 4 applications whose tests use H2.
LedgerPulse stays on Flyway until both are fixed in this library.

1. **Spring Boot 4 auto-configuration does not load.** 2.0.0 is built against Spring Boot 3.5.16.
   `SchemaSynchronizerAutoConfiguration` names
   `org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration` and
   `org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration`.
   Boot 4 moved those classes to
   `org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration` and
   `org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration`.
   Spring fails while loading the auto-configuration, before any schema comparison.
2. **No H2 dialect.** LedgerPulse tests apply schema on H2. A definition is still not a
   cross-engine translation: an H2 dialect must emit H2 SQL, and PostgreSQL definitions
   stay on PostgreSQL.
