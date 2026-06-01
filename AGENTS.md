# Agent Instructions — Bitten

## Project Overview

Bitten is a bi-temporal, log-style database server written in **Babashka** (a scripting variant of Clojure). It communicates over TCP and persists data in SQLite. The schema is append-only: every fact is recorded as a row with at minimum `ENTITY`, `ATTRIBUTE`, and `VALUE` columns, plus temporal metadata (transaction time and valid time).

## Technology Stack

- **Runtime**: [Babashka](https://babashka.org/) — GraalVM-native Clojure scripting. Use `bb` as the interpreter.
- **Persistence**: SQLite via `org.babashka/go-sqlite3` pod (version `0.3.13`). See the Babashka / SCI Gotchas section for the correct pod API.
- **Networking**: TCP server using `babashka.nrepl` patterns or raw `java.net.ServerSocket`.
- **Testing**: `babashka.test` or `clojure.test` (works under Babashka).

## Workflow: Plan → Test → Implement → Verify

Every feature follows this mandatory sequence:

1. **Plan** — write a brief design note (inline comment or doc) describing:
   - What the feature does
   - What the data shape looks like before and after
   - Edge cases and invariants
2. **Test** — write failing tests first (`clojure.test/deftest`). Tests must cover the happy path and at least one failure/edge case.
3. **Implement** — write the minimum code to make tests pass.
4. **Verify** — run `bb test` (or the project's test runner) and confirm all tests are green. For TCP/server changes, also do a manual smoke-test with `nc` or equivalent.

Never skip or reorder these steps.

## Idiomatic Clojure

Follow the guidance at **https://bsless.github.io/code-smells/** to keep code idiomatic. Key rules:

- Prefer `->` / `->>` threading over deeply nested calls.
- Use `let` bindings to name intermediate values; avoid anonymous `do` blocks.
- Prefer `map`, `filter`, `reduce`, `into` over manual recursion.
- Avoid mutable state; use atoms only when shared mutable state is genuinely needed.
- Name predicate functions with a trailing `?` (`valid-tx?`, `entity-exists?`).
- Name effectful functions with a trailing `!` (`insert-fact!`, `start-server!`).
- Keep functions small and single-purpose. If a function needs a docstring longer than two sentences, consider splitting it.
- Do not use `def` inside `defn`; use `let`.
- Prefer `ex-info` over bare `Exception.` for errors, so callers can inspect `:data`.
- Use namespaced keywords for domain concepts (`:db/entity`, `:db/attribute`, `:db/value`).

## Database Schema

Single SQLite table (append-only, never update or delete rows):

```sql
CREATE TABLE IF NOT EXISTS facts (
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  entity      TEXT    NOT NULL,
  attribute   TEXT    NOT NULL,
  value       TEXT    NOT NULL,
  valid_time  TEXT    NOT NULL,  -- ISO-8601, the "business" time this fact is true
  tx_time     TEXT    NOT NULL,  -- ISO-8601, when this row was written (wall clock)
  tx_id       INTEGER NOT NULL,  -- monotonically increasing transaction counter
  retracted   INTEGER NOT NULL DEFAULT 0  -- 1 = this fact has been logically retracted
);
```

- **Never** `UPDATE` or `DELETE` rows. Retraction is a new row with `retracted = 1`.
- `tx_id` groups facts written in the same transaction.
- Queries must be able to filter by `valid_time` range and `tx_time` range independently (bi-temporal).

## TCP Protocol

The server listens on a configurable port (default `5432`). Each connection receives newline-delimited EDN messages:

- **Request**: `{:op :transact :facts [{:e "user/1" :a :name :v "Alice" :valid-time "2024-01-01"}]}`
- **Request**: `{:op :query :e "user/1" :as-of-tx 42 :as-of-valid "2024-06-01"}`
- **Response**: `{:status :ok :data [...]}` or `{:status :error :message "..."}`

All values are EDN. The server reads one EDN form per line, processes it, and writes one EDN response line back.

## File Layout (target structure)

```
src/
  server.clj      — TCP accept loop, dispatch
  db.clj          — SQLite connection, schema migration, fact insert/query
  protocol.clj    — EDN parse/serialize helpers
  tx.clj          — transaction ID management
test/
  db_test.clj
  protocol_test.clj
  server_test.clj
bb.edn            — Babashka project config, deps, test runner
```

## Running the Project

```bash
bb server.clj          # start the server
bb test                # run all tests
echo '{:op :ping}' | nc localhost 5432   # smoke-test
```

## Communicating Intent

Prefer names that communicate *why* over names that are merely short. Brevity that obscures purpose is a bug, not a virtue.

- **Test fixtures and constants**: give test data and time values descriptive names (`before-alice-renamed`, `after-all-events`) rather than leaving raw literals inline. A reader should understand what a value represents without cross-referencing the dataset comment.
- **Intermediate bindings**: name `let` bindings after the concept they represent, not after their type or shape (`valid-tx-id`, not `id`).
- This principle applies everywhere — function names, var names, parameter names, SQL aliases.

## Babashka / SCI Gotchas

Hard-won constraints to apply from the start, not rediscover mid-session.

**Pod API — `org.babashka/go-sqlite3` (use version `0.3.13`+)**
- The pod API is `(sqlite/execute! db [sql & params])` and `(sqlite/query db [sql & params])` — SQL and params are a **single vector**, not separate arguments. Passing `db sql params` as three separate args silently passes zero parameters to the query engine.
- `:memory:` databases do not persist across pod calls; each `execute!` / `query` opens a fresh connection. Use a unique temp file per test (e.g. `(str "/tmp/bitten-test-" (System/nanoTime) ".db")`) and delete it in a `finally` block.
- `BEGIN` / `COMMIT` transactions require a **persistent connection**. Use `(sqlite/get-connection path)` → run statements → `(sqlite/close-connection conn)`. Path-based calls use a new connection per call and will throw "no transaction is active" on `COMMIT`.
- `migrate!` and other functions that take a db handle should accept both a plain path string and a map containing `:db-path` (or similar). Use a private `db->path` helper to normalise the input before passing it to the pod.

**Babashka / SCI var behaviour**
- `^:private` on a bare `def` is **broken in SCI** — the var appears `SciUnbound` at runtime even within the same namespace. Use plain `def` for namespace-level constants in test files; use `defn-` (which does work) for private helper functions.
- When using `replace_all` on a string literal, the replacement will also hit any `def` that *defines* that literal, creating a self-referential binding. Scope replacements carefully or fix the definition site in a separate edit.

## What to Avoid

- No speculative features — build only what the current task requires.
- No defensive nil-handling for code paths that cannot produce nil.
- No comments that restate what the code already says; only document *why* when non-obvious.
- Do not use `:memory:` as a SQLite path in tests — it loses state between pod calls. Use temp files instead.
