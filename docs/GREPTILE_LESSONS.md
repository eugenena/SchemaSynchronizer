# Greptile lessons

## 2026-09-23 — PR #11 — classify constraint-backed indexes by semantics

- **Bug:** PostgreSQL serialization excluded every constraint-owned index even though
  table DDL only recreated primary keys, silently losing `UNIQUE` and `EXCLUDE`
  enforcement on a newly synchronized target.
- **Missed because:** Constraint-backed indexes were treated as one category instead
  of tracing which constraint types were recreated elsewhere and which semantics an
  ordinary index can preserve.
- **Prevention:** Classify PostgreSQL backing indexes by `pg_constraint.contype`:
  omit only primary keys, preserve unique constraints as unique indexes, and fail
  closed with an actionable message for exclusion constraints that need an explicit
  ordered change set. Cross-schema replay tests must verify enforcement, not just DDL.

## 2026-09-23 — PR #10 — document observed normalization behavior

- **Miss:** The schema reference repeated the intended lower-case-only boundary as
  rejection, but definition table and column names are normalized before identifier
  validation.
- **Prevention:** Trace public input through normalization and validation before
  documenting whether an unsupported shape is rejected, normalized, or ignored.
- **Coverage:** The schema reference, troubleshooting guide, and production audit now
  consistently describe lowercase normalization and quoted-identifier rejection.

## 2026-09-23 — PR #8 — database readiness must cross the SQL boundary

- **Bug:** TiDB verification proceeded when its TCP port accepted a connection,
  before the MySQL handshake and query layer were necessarily ready.
- **Missed because:** Listener readiness was treated as database readiness; the
  first JDBC test became the accidental readiness probe and could fail intermittently.
- **Prevention:** Containerized database checks must authenticate and execute a
  trivial query through the target database protocol before starting integration
  tests. The verification script now runs `SELECT 1` through a MySQL client.

## 2026-09-23 — PR #7 — a release workflow must reach its promised terminal state

- **Bug:** The tag workflow ran `mvn deploy`, but the Central plugin stopped at
  `uploaded`; nobody or nothing published the deployment.
- **Missed because:** Packaging and upload were verified, while the workflow's
  advertised outcome—publication—was not traced through the Central plugin state
  machine.
- **Prevention:** Release automation must wait for `published`, and maintainer
  documentation must describe the same terminal state as the automated path.
