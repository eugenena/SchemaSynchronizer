# Contributing to SchemaSynchronizer

Thank you for helping improve SchemaSynchronizer. Bug reports, documentation fixes,
tests, and database-dialect contributions are welcome.

## Before opening an issue

- Search existing issues.
- Confirm the behavior on the latest released version or current `main`.
- Remove credentials and production data from logs and definitions.
- Include the database product/version, Java version, library version, expected
  result, actual result, and a minimal reproduction.

Report security vulnerabilities privately as described in [SECURITY.md](SECURITY.md).

## Development

Requirements: Java 21+, Maven 3.9+, Docker for database integration tests.

```bash
git clone https://github.com/eugenena/SchemaSynchronizer.git
cd SchemaSynchronizer
mvn test
```

Run integration tests against the databases affected by a change. Commands and
properties are documented in [README.md](README.md#testing-and-development).

## Pull requests

- Keep changes focused and explain the behavior being corrected or added.
- Add regression tests for bug fixes.
- Update user documentation for public behavior changes.
- Preserve fail-closed behavior and the manual boundary for destructive changes.
- Do not weaken SQL validation to accommodate one input shape.
- Do not edit already released change-set examples in ways that encourage checksum
  mutation.

New dialects must meet [the dialect acceptance contract](docs/CONTRIBUTING_A_DIALECT.md).

By submitting a contribution, you agree that it is licensed under the Apache License
2.0 under the same terms as the project.
