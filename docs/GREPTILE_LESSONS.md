# Greptile lessons

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
