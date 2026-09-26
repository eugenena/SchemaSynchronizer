# Migrating from Flyway or another migration tool

SchemaSynchronizer is a desired-state synchronizer, not a migration-history player.
Adoption should prove that a new database and every supported existing database
converge to the same state.

Hand-authoring the definition (rather than only serializing production) is supported
and common; see [HAND_AUTHORING.md](HAND_AUTHORING.md).

## 1. Freeze competing schema writers

Identify every component that can mutate schema: Flyway, Liquibase, Hibernate DDL,
deployment scripts, and manual bootstrap jobs. Plan a release in which exactly one
tool owns mutation at a time.

## 2. Produce a schema definition

Either:

- **Serialize** a representative database with `SchemaSerializer`, review the
  generated tables, columns, keys, defaults, and indexes, and commit the definition;
  or
- **Hand-author** `tables` and `changes` in git, then `validate` offline.

Repeat serialization against materially different deployment ages when you need to
expose structural drift. Prefer one committed definition that both empty and aged
databases can adopt.

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

On existing databases, SchemaSynchronizer 1.2.0+ will:

- record a change set in history when `verificationSql` is already true; and
- when verification is false, skip individual statements that fail only because the
  object already exists, then require verification to succeed.

Compare the resulting schemas. Both must converge before cutover.

## 5. Rehearse pending SQL

Run `dry-run` (or sync with a disposable clone) with production-shaped data and
review every pending statement. Decide whether each live orphan should be removed,
moved to another managed schema, or represented in the definition.

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
