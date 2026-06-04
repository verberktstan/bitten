# Bitten

**Bitten** is a portmanteau of **bi-temporal** in the past tense. It is a small, append-only database server that tracks two independent time axes for every fact it stores.

## What it does

Some databases record what is true *right now*. Bitten records:

- **Valid time** - when a fact was true in the real world (e.g. a contract started on 1 January, even if you didn't enter it until March).
- **Transaction time** - when the fact was written into the database.

This means you can ask questions like *"what did we know about user Alice on 1 June, as of the snapshot we had in September?"*, and get a deterministic answer even after retroactive corrections have been applied.

Facts are never updated or deleted. Every change is a new row; retractions are explicit. The log is the truth.

## Rationale

Bitten makes audit trails and temporal queries structural: the schema enforces append-only writes, and every query is implicitly bi-temporal. There is no separate audit table to maintain or forget to update.

The implementation is intentionally small; a single SQLite table. The core model stays legible and the storage backend can be swapped without touching application logic.

## Stack

- **[Babashka](https://babashka.org/)** - GraalVM-native Clojure scripting; fast startup, no JVM warm-up.
- **SQLite** via the `org.babashka/go-sqlite3` pod - embedded, zero-infrastructure persistence.
- **EDN over TCP** - newline-delimited protocol; any EDN-capable client can talk to the server.

## Running

```bash
bb start                   # default: port 5432, db file bitten.db
PORT=6432 bb start         # custom port
DB_PATH=/data/my.db bb start   # custom db path
```

The server prints a ready line and blocks until interrupted (`Ctrl-C`). The database file is created and migrated automatically on first start.

### Wire protocol

One EDN map per line in, one EDN map per line out.

**Transact** - upsert one or more records. Each record is a flat map with `:db/entity` plus attribute keys. Only changed attributes are written.

```edn
{:op :transact :records [{:db/entity "user/1" :user/name "Alice" :user/email "a@example.com"}]}
;; => {:status :ok :data 1}   ; data is the tx-id, nil for a no-op
```

**Query** - return live facts for an entity as a flat map.

```edn
{:op :query :e "user/1"}
{:op :query :e "user/1" :as-of-valid "2024-06-01"}
;; => {:status :ok :data ({:db/entity "user/1" :user/name "Alice" ...})}
```

**Ping**

```edn
{:op :ping}
;; => {:status :ok :data :pong}
```

Errors return `{:status :error :message "..."}`.

### Logic layer (`src/db.clj`)

| Function | Description |
|---|---|
| `query` | Returns live facts as a seq of flat maps, each with `:db/entity`. |
| `upsert!` | Writes only the attributes that changed; optionally retracts attributes absent from the incoming record (`:missing-keys :retract`). |
| `retract!` | Retracts all live facts for a seq of entity IDs. Inserts one retraction row per `(entity, attribute, value)` triple. Entities that are already retracted or do not exist are silently skipped. Returns the tx-id, or `nil` for a no-op. |

Storage is abstracted behind an `IStorage` protocol (`src/storage.clj`). The SQLite implementation lives in `src/sqlite.clj`. To add a new backend, implement the three-method protocol in a new file. No changes to `src/db.clj` or the server are needed.

## Testing

```bash
bb test
```

Tests use real SQLite temp files (one per test, cleaned up in a `finally` block). There is no mocking of the storage layer.

## Development

For Emacs/CIDER, use `M-x cider-jack-in-universal` to start a Babashka REPL for development purposes. It handles launching and connecting in one step.

To start a nREPL server directly:

```bash
bb nrepl-server 1667
```

Or use the project task, which additionally writes a `.nrepl-port` file so CIDER can auto-detect the port:

```bash
bb nrepl
```

## License

Copyright © 2026 Stan Verberkt

Distributed under the EPL License. See LICENSE.
