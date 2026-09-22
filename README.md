# thinkai-shared

Reusable libraries for ThinkAI Spring / Postgres apps (JSI, Nestlogue backend, MarginPulse, Marketworks, …).

## Modules

| Module | Artifact | Purpose |
|--------|----------|---------|
| **schema-applier** | `com.thinkai:schema-applier` | Apply non-destructive schema diffs from `schema-definition.json` on startup |

## schema-applier contract

**Auto-apply:** create table, add column, create index, SET/DROP DEFAULT, safe type widenings, `NOT NULL` → nullable.

**Pending manual (never auto):** drop table/column/index, type narrowing, unsafe `NULL` → `NOT NULL` without a clear backfill path.

## Install locally

```bash
cd thinkai-shared
mvn clean install
```

Consuming app (e.g. getjsi):

```xml
<dependency>
  <groupId>com.thinkai</groupId>
  <artifactId>schema-applier</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
```

## Portfolio

Ops infrastructure — not a product. Apps stay separate; this jar is the bridge.
