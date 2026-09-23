# Migrating from Flyway or another migration tool

SchemaSynchronizer is a desired-state synchronizer, not a migration-history player.
Adoption should prove that a new database and every supported existing database
converge to the same state.

## 1. Freeze competing schema writers

Identify every component that can mutate schema: Flyway, Liquibase, Hibernate DDL,
deployment scripts, and manual bootstrap jobs. Plan a release in which exactly one
tool owns mutation at a time.

## 2. Serialize a representative database

Use `SchemaSerializer`, review the generated tables, columns, keys, defaults, and
indexes, and commit the definition. Repeat against materially different deployment
ages to expose drift.

## 3. Represent non-declarative effects

Historical migrations may have created constraints, functions, triggers,
extensions, grants, comments, or data invariants. Express their durable effects as
ordered change sets with verification queries. Do not blindly mark old migrations
as applied.

## 4. Prove both paths

Test at least:

1. an empty database applying the complete definition; and
2. an existing production-shaped database adopting verified history without
   replaying destructive or non-idempotent work.

Compare the resulting schemas. Both must converge before cutover.

## 5. Rehearse pending SQL

Run with production-shaped data and review every pending statement. Decide whether
each live orphan should be removed, moved to another managed schema, or represented
in the definition.

## 6. Cut over

- Back up the database and confirm restore procedures.
- Disable the previous migration runner.
- Enable SchemaSynchronizer with `fail-on-pending=true`.
- Keep Hibernate at `ddl-auto=validate`.
- Deploy one application instance first and retain complete startup logs.
- Scale out only after synchronization and validation succeed.

Keep historical migration files in source control as audit evidence. They no longer
need to run, but they remain part of the system's operational history.

## Rollback

Rolling back application code does not automatically reverse schema changes.
Because automatic changes are additive or otherwise non-destructive, the previous
application version will often remain compatible, but that must be verified during
release planning. Treat operator-executed destructive SQL as a separate, explicitly
backed-up change window.
