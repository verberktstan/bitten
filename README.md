# Bitten

**Bitten** is a portmanteau of **bi-temporal** in the past tense. It is a small, append-only database server that tracks two independent time axes for every fact it stores.

## What it does

Some databases record what is true *right now*. Bitten records:

- **Valid time** — when a fact was true in the real world (e.g. a contract started on 1 January, even if you didn't enter it until March).
- **Transaction time** — when the fact was written into the database.

This means you can ask questions like *"what did we know about user Alice on 1 June, as of the snapshot we had in September?"* — and get a deterministic answer even after retroactive corrections have been applied.

Facts are never updated or deleted. Every change is a new row; retractions are explicit. The log is the truth.

## Rationale

Bitten makes audit trails and temporal queries structural: the schema enforces append-only writes, and every query is implicitly bi-temporal. There is no separate audit table to maintain or forget to update.

The implementation is intentionally small; a single SQLite table. The core model stays legible and the storage backend can be swapped without touching application logic.
To be extended with: a TCP server & EDN over the wire

## Stack

- **[Babashka](https://babashka.org/)** — GraalVM-native Clojure scripting; fast startup, no JVM warm-up.
- **SQLite** via the `org.babashka/go-sqlite3` pod — embedded, zero-infrastructure persistence.

Not yet implemented;
- **EDN over TCP** — simple line-delimited protocol; any EDN-capable client can talk to the server.

## Developing

TODO

Storage is abstracted behind an `IStorage` protocol (`src/storage.clj`). The SQLite implementation lives in `src/sqlite.clj`. To add a new backend, implement the three-method protocol in a new file — no changes to `src/db.clj` or the server are needed.

## Testing

```bash
bb test
```

Tests use real SQLite temp files (one per test, cleaned up in a `finally` block). There is no mocking of the storage layer.

## License

Copyright © 2026 Stan Verberkt

Distributed under the EPL License. See LICENSE.
