# Greptile lessons

## 2026-09-23 — PR #7 — a release workflow must reach its promised terminal state

- **Bug:** The tag workflow ran `mvn deploy`, but the Central plugin stopped at
  `uploaded`; nobody or nothing published the deployment.
- **Missed because:** Packaging and upload were verified, while the workflow's
  advertised outcome—publication—was not traced through the Central plugin state
  machine.
- **Prevention:** Release automation must wait for `published`, and maintainer
  documentation must describe the same terminal state as the automated path.
