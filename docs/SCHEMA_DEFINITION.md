# Schema definition reference

`schema-definition.json` is a trusted, version-controlled application artifact. It
combines desired schema state with explicit ordered changes that cannot be inferred
safely from JDBC metadata.

## Top-level fields

| Field | Required | Description |
|---|---:|---|
| `formatVersion` | Yes for new files | Current format is `2` |
| `dialect` | Yes for new files | `postgresql`, `mariadb`, or `mysql` |
| `tables` | Yes | Desired tables keyed by unquoted table name |
| `changes` | No | Ordered explicit change-set ledger |

Legacy files without `formatVersion` and `dialect` are interpreted as PostgreSQL
format version 1. New definitions should never rely on that compatibility path.

## Tables

Each table contains:

- `createSql`: idempotent native SQL used when the table is absent;
- `columns`: column names and native SQL definitions; and
- `indexes`: idempotent native `CREATE INDEX` statements.

The table key, the target in `createSql`, and the targets in index SQL must agree.
Use ordinary unquoted identifiers. Table and column names are normalized to
lowercase; quoted identifiers are rejected. Avoid mixed-case spelling even when it
would normalize successfully, because the definition targets the lowercase object.

The declarative layer owns ordinary tables in the configured schema. A live table,
column, index, or primary key absent from the definition is reported as pending
manual SQL. Keep extension-owned or externally managed tables in another schema.

## Change sets

Use change sets for operations that metadata cannot reconstruct reliably, including
backfills, constraints, functions, triggers, extensions, comments, and grants.

```json
{
  "id": "002-customer-status",
  "description": "Backfill and constrain customer status",
  "phase": "AFTER_SCHEMA",
  "statements": [
    "UPDATE customers SET status = 'ACTIVE' WHERE status IS NULL",
    "ALTER TABLE customers ALTER COLUMN status SET NOT NULL"
  ],
  "verificationSql": "SELECT NOT EXISTS (SELECT 1 FROM customers WHERE status IS NULL)"
}
```

Rules:

- `id` must be stable and unique.
- Never edit an applied change set; its SHA-256 checksum is stored in history.
- Put each JDBC statement in its own array item.
- `phase` is `BEFORE_SCHEMA` by default; use `AFTER_SCHEMA` for objects that depend
  on newly created tables or columns.
- `verificationSql` must return exactly one row containing one non-null boolean.
- Verification queries must be read-only and must not call side-effecting functions.

MariaDB and MySQL can commit DDL implicitly. Every unapplied change set for those
dialects therefore requires `verificationSql`; if verification is false, the change
set must contain exactly one statement. Split multi-step work into ordered change
sets.

## What is automatic

SchemaSynchronizer automatically handles supported safe operations: table and
column creation, index creation, defaults, supported type widenings, and relaxing
`NOT NULL`. Drops, narrowing, ambiguous type changes, and tightening nullability are
manual unless expressed intentionally through a validated change set.

Generate the declarative portion with `SchemaSerializer` when helpful, or hand-author
`tables` directly. Maintain exceptional `changes` by hand either way. See
[HAND_AUTHORING.md](HAND_AUTHORING.md) for the recommended application workflow,
including offline `validate`, `dry-run`, and partial adoption of already-present
objects.
