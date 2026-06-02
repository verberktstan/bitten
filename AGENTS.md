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
Before actual implementation is done. Present benefits and trade-offs whenever there is need to choose between different approaches, implementations or concepts.

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
  retracted   BOOLEAN NOT NULL DEFAULT false  -- true = this fact has been logically retracted
);
```

- **Never** `UPDATE` or `DELETE` rows. Retraction is a new row with `retracted = true`.
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
  storage.clj     — IStorage protocol (migrate!, insert-facts!, query-as-of)
  sqlite.clj      — SqliteBackend record + IStorage implementation; all pod/SQL code lives here
  db.clj          — logic layer: query, upsert!, diff->facts, assert-fact; backend-agnostic
  server.clj      — TCP accept loop, dispatch
  protocol.clj    — EDN parse/serialize helpers
  tx.clj          — transaction ID management
test/
  db_test.clj
  protocol_test.clj
  server_test.clj
bb.edn            — Babashka project config, deps, test runner
```

## Backend Architecture

Storage is abstracted behind a `defprotocol` in `storage.clj`:

```clojure
(defprotocol IStorage
  (migrate!      [backend])   ; one-time schema setup
  (insert-facts! [backend facts])  ; append facts, return tx-id
  (query-as-of   [backend opts]))  ; bi-temporal point query, returns seq of datoms
```

`sqlite.clj` provides the only current implementation via `(defrecord SqliteBackend [db-path])` + `extend-type`. All pod loading and SQL lives there — nothing in `db.clj` or above knows about SQLite.

**Adding a new backend:**
1. Create `src/<name>.clj` with a record and an `extend-type` block implementing `IStorage`.
2. The three protocol methods must honour the same contracts as `SqliteBackend`:
   - `migrate!` — idempotent schema setup, return value unused.
   - `insert-facts!` — accepts fact maps with `:entity`, `:attribute`, `:value`, optional `:valid-time` and `:retracted`; returns a monotonically increasing integer tx-id.
   - `query-as-of` — accepts `{:entities :valid-time :tx-time}` (all optional); returns a seq of `{:db/entity :db/attribute :db/value}` datoms, applying the same bi-temporal ranking logic (latest surviving fact per entity+attribute pair).
3. No changes to `db.clj`, `server.clj`, or tests are required.

**Layering rule:** only `storage.clj` (the protocol) and `sqlite.clj` (the implementation) may reference `pod.babashka.go-sqlite3`. `db.clj` and above call `storage/` protocol functions only.

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
- `storage/migrate!` is called for its side effect; its return value is not used. The test fixture creates a `SqliteBackend` record and binds `*db*` to it — `*db*` is a `SqliteBackend`, not a bare path string. Raw pod calls in test helpers (e.g. `insert-raw!`) extract the path via `(:db-path backend)`.

**Babashka / SCI var behaviour**
- `^:private` on a bare `def` is **broken in SCI** — the var appears `SciUnbound` at runtime even within the same namespace. Use plain `def` for namespace-level constants in test files; use `defn-` (which does work) for private helper functions.
- When using `replace_all` on a string literal, the replacement will also hit any `def` that *defines* that literal, creating a self-referential binding. Scope replacements carefully or fix the definition site in a separate edit.

**Pod process / EOF noise**
- When the Babashka JVM exits, the go-sqlite3 pod subprocess sees EOF on its stdin and logs `Unrecoverable error: EOF` to stderr. This is cosmetic — tests are unaffected. `pods/unload-pod` does not cause the pod to exit cleanly (it closes stdin, which *triggers* the log); `ProcessHandle.destroy()` / `.destroyForcibly()` kill the process before it can log, but the pod process may still outlive the JVM exit by a few milliseconds. The simplest accepted state: the error appears on stderr after a test run and can be ignored.

## Performance — Reduction Over Query Results

`query` folds a seq of datoms into a nested map with a plain `reduce`. Three alternatives were evaluated:

| Option | Available in Babashka? | Verdict |
|---|---|---|
| `transduce` | Yes | No gain as a drop-in: `query-as-of` already returns a realised lazy seq, so there is nothing to fuse. Would require restructuring `query-as-of` to thread a transducer all the way from raw pod rows to the reducing step. |
| `clojure.core.reducers` / `r/fold` | **No** — not on Babashka's SCI classpath | Ruled out immediately. Even on full JVM Clojure it requires a foldable vector input and fork/join overhead that only pays off at very large collection sizes. |
| `volatile!` mutable accumulator | Yes | Marginally faster in tight loops (avoids per-step closure allocation); the underlying persistent map operations are unchanged. Trades purity for a micro-optimisation that is invisible against the SQLite roundtrip latency. |

**Decision: keep plain `reduce` with immutable `{}`.** The bottleneck is the SQL pod call, not the Clojure fold. Revisit only if profiling shows the reduction itself as a hotspot for result sets in the hundreds of thousands.

## `retract!` — Design and Implementation

### What it does

`retract!` takes a backend and a sequence of entity ID strings. For each entity that currently has live (non-retracted) facts, it inserts one retraction row per `(attribute, value)` pair, making the whole entity invisible to subsequent queries. Entities that do not exist or are already fully retracted are silently skipped.

### Algorithm

```
1. (query backend {:entities (set entity-ids)}) → live flat maps (already excludes retracted)
2. For each record, for each [attr val] in (dissoc record :db/entity):
     {:entity e :attribute attr :value val :retracted true}
3. (when (seq all-facts) (storage/insert-facts! backend all-facts))
   → tx-id or nil for no-op
```

### Edge cases

| Situation | Behaviour |
|---|---|
| Entity not in DB | `query` returns nothing → nil |
| Already fully retracted | `query` returns nothing → nil |
| Multiple entities | all handled in one `insert-facts!` call |

## `upsert!` — Design and Implementation

### Data shapes

Input — a sequence of flat maps where `:db/entity` identifies the record:

```clojure
[{:db/entity "user/alice" :user/name "Alice" :user/email "a@example.com"}
 {:db/entity "user/bob"   :user/name "Bob"}]
```

`query` returns the same flat-map shape (`:db/entity` plus attribute keywords as keys), making the roundtrip symmetrical: `query` output can be modified and fed directly back into `upsert!`.

### Algorithm

```
1. Collect all :db/entity values → set of entities
2. (query backend {:entities entities}) → current state as flat maps
3. index-by-entity → {"user/alice" {:user/name "Alicia" …}, …}
4. For each incoming record:
   a. incoming  = (dissoc record :db/entity)
   b. existing  = (get current-idx entity {})
   c. diff      = (zipmap [:from-db :from-record]
                           (clojure.data/diff existing incoming))
                  then assoc :entity and :missing-keys into the map
   d. diff->facts → {:changed [...] :retracted [...]}
5. Flatten all per-record facts with (into [] cat …)
6. (when (seq all-facts) (storage/insert-facts! backend all-facts))
   → returns tx-id or nil for a pure no-op
```

### `diff->facts`

`clojure.data/diff` partitions the diff into three maps: `[only-in-a only-in-b same]`. With `a = existing` and `b = incoming`:

- **`:from-record`** (`only-in-b`) — attributes that are new or have a changed value → become assertion facts
- **`:from-db`** (`only-in-a`) — attributes that existed but are changed or gone. Changed values appear in *both* `from-db` and `from-record`; `(apply dissoc from-db (keys from-record))` subtracts the updated keys, leaving only attributes that vanished entirely → become retraction facts when `:missing-keys :retract`

`zipmap [:from-db :from-record]` over the diff output drops the `same` partition and names the two partitions, producing a map that is then passed directly to `diff->facts`.

### `:missing-keys` option

| Value | Behaviour |
|---|---|
| `:ignore` (default) | Absent attributes are left untouched in the db |
| `:retract` | Attributes present in the db but absent from the incoming record are retracted (new row with `retracted = true`) |

### Retractions in `insert-facts!`

`insert-facts!` accepts an optional `:retracted` key (default `false`) in each fact map. When `true`, the row is inserted with `retracted = true`, making that attribute invisible to subsequent queries for that (entity, attribute) pair at or after the retraction's `tx_time`.

## What to Avoid

- No speculative features — build only what the current task requires.
- No defensive nil-handling for code paths that cannot produce nil.
- No comments that restate what the code already says; only document *why* when non-obvious.
- Do not use `:memory:` as a SQLite path in tests — it loses state between pod calls. Use temp files instead.
