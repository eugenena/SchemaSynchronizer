# Security policy

## Supported versions

Security fixes are provided for the latest released version. Upgrade to the newest
release before reporting a problem that may already have been corrected.

## Reporting a vulnerability

Do not open a public issue for a vulnerability. Email
[info@thinkaillc.com](mailto:info@thinkaillc.com) and state that the message concerns
a private SchemaSynchronizer security report.

Include affected versions, database and Java versions, impact, reproduction steps,
and any proposed mitigation. Do not include live credentials or production data.

The maintainer will acknowledge the report, assess severity, coordinate a fix and
release when appropriate, and credit the reporter unless anonymity is requested.

## Scope clarification

Schema definitions are trusted application artifacts. The SQL policy rejects accidental
destructive DDL (including forbidden tokens inside `CREATE FUNCTION` / `TRIGGER` /
`PROCEDURE` bodies) and binds change-set SQL to the configured schema namespace. It is
still not a sandbox for hostile SQL: `UPDATE` / `INSERT` / `GRANT` remain allowed for
intentional data backfills and privileges. Applications must restrict who can modify
definitions and deployment artifacts.
