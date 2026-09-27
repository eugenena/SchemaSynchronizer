# Security policy

## Supported versions

Security fixes are provided for the latest released version. Upgrade to the newest
release before reporting a problem that may already have been corrected.

## Reporting a vulnerability

Do not open a public issue, discussion, or pull request for a vulnerability. The single
private channel is email: [info@thinkaillc.com](mailto:info@thinkaillc.com), with a subject
that starts with `SchemaSynchronizer security`.

Include affected versions, database and Java versions, impact, reproduction steps,
and any proposed mitigation. Do not include live credentials or production data.

The maintainer will acknowledge the report, assess severity, coordinate a fix and
release when appropriate, and credit the reporter unless anonymity is requested.

## Scope: the SQL checks are not a security boundary

Schema definitions are trusted application artifacts, reviewed and deployed like code.
The change-set SQL checks (destructive-statement rejection, schema scoping, routine-body
inspection) exist to **catch mistakes**. They are not a sandbox and do not contain a
hostile or compromised change-set author:

- A change-set author can reach anything the database account can reach. A schema-local
  helper that runs dynamic SQL (`SELECT app.run_sql('DROP TABLE public.audit')`), a
  `SECURITY DEFINER` function, a trigger, or a routine created by an earlier change set runs
  with the account's privileges, and the checks do not follow SQL built at run time.
- `UPDATE`, `INSERT`, and object-level `GRANT` stay allowed for intentional backfills and
  privileges.
- A change set already recorded in history is never re-checked.

Therefore a **schema-scoped, least-privilege database account is required**: the account
that runs SchemaSynchronizer must be able to change only the application schema. Never run
it as a superuser, `sa`, `root`, `SYSTEM`, `db_owner`, or an account with `ANY` or global
privileges. Copy-pasteable grants for every engine are in
[docs/OPERATIONS.md](docs/OPERATIONS.md#least-privilege-database-account-required).

Also:

- Restrict who can modify definitions and deployment artifacts, and review change sets like
  production code.
- Pass passwords via `SCHEMA_DB_PASSWORD`; the CLI rejects literal passwords on the command
  line and redacts `password=`/`pwd=` URL parameters from its error output.
- Spring Boot auto-sync is opt-in (`schema-synchronizer.enabled=true`).
- The declarative layer covers tables, columns, indexes, and primary keys only; foreign keys,
  checks, partitions, and similar objects belong in ordered change sets.

A report that shows the SQL checks can be bypassed by a change-set author is a guardrail bug,
welcome as a normal issue, not a vulnerability. A report that shows SchemaSynchronizer itself
exceeding the configured schema or leaking credentials is a vulnerability; report it
privately.
