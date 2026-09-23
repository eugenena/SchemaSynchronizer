# Contributing a database dialect

A dialect is more than SQL spelling. It owns metadata interpretation, safe-change
classification, SQL generation, locking, retry behavior, and compatibility claims.

## Required work

1. Add explicit product detection in `DatabaseDialect` without weakening existing
   detection.
2. Define native type parsing and canonicalization.
3. Implement catalog reads for tables, columns, primary keys, defaults, nullability,
   and indexes.
4. Generate idempotent safe DDL and executable pending destructive SQL.
5. Implement a database-scoped synchronization lock.
6. Document transactional DDL and implicit-commit behavior.
7. Define identifier rules and namespace semantics.
8. Serialize the dialect identifier and reject mismatched targets.

## Acceptance contract

Integration tests against a pinned real database version must prove:

- empty-database creation;
- serialize and replay convergence;
- additive column and index changes;
- supported type widening and default/nullability changes;
- primary-key and index drift detection;
- orphan object planning;
- pending destructive SQL quality;
- lock behavior under concurrent startup;
- retry behavior after partial or ambiguous failure; and
- definition/dialect mismatch rejection.

Mock-only tests are insufficient for a new dialect. JDBC metadata and DDL behavior
vary too much between engines.

## Compatibility claims

Label a database `Available` only when it has its own detected dialect and integration
suite. Label a compatible product only when the same contract passes against a pinned
version. State that vendor extensions outside the documented schema model are not
covered.

Start with an issue describing the target database, versions, container or external
test strategy, DDL transaction behavior, and expected identifier rules before
opening a large implementation pull request.
