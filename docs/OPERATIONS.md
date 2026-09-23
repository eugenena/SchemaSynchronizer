# Operations and safety runbook

Use this checklist before enabling SchemaSynchronizer in a production application.

## Before deployment

- Commit and review `schema-definition.json` like production code.
- Test an empty database and a production-shaped upgrade database.
- Back up the target and test the restore path.
- Confirm only one schema mutation mechanism is active.
- Use `spring.jpa.hibernate.ddl-auto=validate` when Hibernate is present.
- Keep `fail-on-pending=true` and `require-definition=true` unless a documented
  operational reason requires otherwise.
- Give the database account only the catalog-read and DDL privileges it needs.
- Keep externally managed tables in another schema.

## Deployment sequence

1. Deploy one instance while the remaining fleet stays on the previous version.
2. Preserve complete startup output.
3. Confirm the database lock was acquired and released.
4. Confirm safe changes and change sets completed.
5. Stop if pending SQL is reported; do not repeatedly restart the fleet.
6. Let JPA validation or application health checks complete.
7. Scale out the new version.

Concurrent starters serialize through the dialect's database lock. That protects
the synchronization operation, but a controlled one-instance rollout still makes
diagnosis and rollback clearer.

## Handling pending SQL

Pending SQL is a safety boundary, not an error to suppress.

1. Compare the live object and desired definition.
2. Confirm whether the object should be removed or the definition corrected.
3. Review data loss, lock duration, and application compatibility.
4. Take or verify a backup.
5. Execute approved SQL through the organization's normal database-change process.
6. Run SchemaSynchronizer again and require a clean result.

Setting `fail-on-pending=false` allows startup with unresolved differences. Use it
only when the application is known to be compatible with that drift and monitoring
will keep the pending work visible.

## Dry run

PostgreSQL can roll back supported DDL selected during a dry run. MariaDB and MySQL
may commit DDL implicitly, so their dry-run mode cannot promise a side-effect-free
rehearsal. Use disposable database instances for reliable preflight testing.

## Transactions and locks

When the Java API receives a connection already inside a caller-owned transaction,
SchemaSynchronizer does not commit it. Changes and the transaction-scoped PostgreSQL
advisory lock remain active until the caller commits or rolls back.

## Recovery

- Preserve the original failure; cleanup errors are attached as suppressed causes.
- On MariaDB/MySQL, inspect the database before retrying after a connection failure
  because DDL may have committed before history recording.
- Verification queries and single-statement change sets allow safe retry decisions.
- Never edit the history table to bypass checksum drift without an incident review.
