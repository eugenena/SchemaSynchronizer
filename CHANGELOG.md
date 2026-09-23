# Changelog

## 1.0.0 — 2026-09-23

First public release.

- Desired-state schema synchronization for PostgreSQL, MariaDB, and MySQL.
- Automatic non-destructive table, column, index, default, widening, and nullability changes.
- Explicit pending SQL for destructive or unsafe reconciliation.
- Ordered, checksummed change sets for operations that cannot be inferred from metadata.
- Standalone schema serialization and synchronization CLIs.
- Spring Boot auto-configuration with configurable startup policy.
- Apache-2.0 licensing and Maven Central release packaging.
