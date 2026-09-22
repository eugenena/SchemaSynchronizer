# SchemaApplier production audit

Audit date: 2026-09-22

## Scope

The audit covered every production source, test, Maven configuration, and GitHub
workflow in `thinkai-shared`, plus integration behavior against PostgreSQL 16.
It reviewed transaction ownership, concurrent startup, SQL-policy boundaries,
schema introspection, drift detection, serialization, Spring auto-configuration,
credential handling, and release packaging.

## Findings addressed

- Restricted custom SQL to explicit supported statement shapes. Broad verb checks
  previously admitted dangerous forms such as `DO`, `ALTER SYSTEM`, and table rename.
- Validated declarative table and index targets before any transaction mutates the
  database, including duplicate column/index definitions.
- Preserved the original apply exception when rollback or connection restoration
  also fails by attaching cleanup failures as suppressed exceptions.
- Made verification queries fail closed unless they return exactly one non-null
  boolean row.
- Rejected duplicate history rows and bounded history fields to their database
  column sizes before insertion.
- Added primary-key, explicit-index, orphan-index, and orphan-table reconciliation.
  Constraint-owned PostgreSQL indexes are excluded from orphan detection.
- Added exact `NUMERIC(precision, scale)`, `CHAR(length)`, and `vector(dimension)`
  introspection. Numeric changes now reject loss of integer or fractional capacity,
  and vector dimension changes remain manual.
- Removed the serializer's fabricated vector-dimension fallback.
- Made serializer output atomic and moved the documented password path from a
  process-list-visible command argument to `SCHEMA_DB_PASSWORD`.
- Made Spring auto-configuration back off when more than one `DataSource` exists,
  rather than selecting an ambiguous database.
- Enforced PostgreSQL's 63-byte ASCII identifier boundary used by this library.

Each behavior above has unit or PostgreSQL integration coverage. The complete
PostgreSQL 16 verification suite passes.

## Deliberate boundaries

- This release is PostgreSQL-only. It deliberately rejects other database products.
- Definitions are trusted, version-controlled application artifacts. The SQL policy
  is defense in depth, not a sandbox for hostile SQL. A `SELECT` can call a volatile
  user-defined function, so change authors must keep verification functions
  side-effect-free.
- Only unquoted lower-case identifiers are supported. Quoted or mixed-case names are
  rejected to avoid ambiguous catalog matching.
- The declarative layer owns all ordinary tables in its configured schema. Extension-
  owned or externally managed tables should live in another schema; otherwise they
  are intentionally reported as pending orphans.
- When called inside a caller-owned transaction, changes remain part of that
  transaction and the transaction-scoped advisory lock remains held until the caller
  commits or rolls back.

## Publication blocker

The repository does not yet contain a `LICENSE` file. No public release should be
described as open source until the owner selects and adds a license (for example,
Apache-2.0 or MIT). License selection is a product/legal decision and was not guessed
by this audit.
