# Streaming Compiler Repository Guidance

## Product Boundary

This repository owns the LLM-facing compiler orchestration layer. DEAL and Deal UI remain separate
platform-neutral transpilers and semantic checkers. Streaming-compiler calls their versioned,
stateless inspect/edit/compile APIs; it does not reimplement their parsers, type systems,
AppInterface extraction, source projection or diagnostics.

Streaming-compiler owns:

- provider-neutral model requests and streaming response parsing;
- compact model tool schemas derived from compiler-issued operations and diagnostics;
- semantic greenfield generation, repair and iterative modernization state machines;
- context and dependency-slice selection;
- transport retries, semantic repair budgets and progress detection;
- generation/refinement metrics and reproducible traces.

It must not contain Android, Compose, Studio UI or scenario-specific application components.

## Canonical And Stateless Boundary

`app.deal` and `app.dealui` are the only canonical generated sources. Every compiler call carries
complete source bytes, `baseDigest`, pack/interface snapshots as required and compiler-defined
operations. Semantic graphs are ephemeral. An internal cache may accelerate repeated inspection but
must never affect correctness or become persisted application state.

The rich compiler API is never copied wholesale into a model prompt. For each turn,
streaming-compiler derives a compact, versioned Agent Surface containing short revision-local aliases,
minimum summaries and only the operations relevant to the current step. A model calls
`inspect_change` with aliases and operation kinds; the compiler derives the dependency cone before a
write operation appears. The engine resolves aliases to compiler-issued
`SymbolId` and revision-scoped `NodeId` internally and supplies the compiler-issued target fingerprint
as a transaction precondition. Aliases expire whenever either canonical source changes.
Structural Deal UI work queries a compiler-owned document alias before `addView`; view removal and
subtree edits likewise require their current queried aliases. The model never invents or persists
document, view or node identities.

Model tools never use source comments, text search or offsets as identity. Stale digests, aliases and
fingerprints are rejected before a candidate changes. A semantic transaction is copy, validate,
commit. Transport retry cannot mutate the graph or consume semantic repair budget.

Every provider request records `protocolVersion`, `surfaceVersion`, canonical revision digests,
surface digest and surface byte/token estimates. Provider token usage remains authoritative for
benchmarking. Tool schemas are generated dynamically; do not maintain a static copy of the rich API
or expose source ranges, fingerprints and full opaque ids to the model.

## Generation And Modernization

Greenfield generation and iterative modernization use the same compiler API. The ordinary path is
sequential: DEAL first, exact AppInterface second, Deal UI third. UI-only refinements do not generate
DEAL; private DEAL changes do not regenerate Deal UI; public interface changes update only affected
UI nodes. The final source pair is fully checked and published atomically.

Repair remains scoped to compiler-owned slots and dependency groups. Accepted unrelated slots are
absent from the writable schema and preserve their payload digests. During repair the only model
write is `patch_repair_slot`; its operation and target are immutable. Repeating an unchanged rejected
payload is no progress. Full-program regeneration is a deliberate fallback, not normal repair.

## Generalization

Do not add medication, exam, health, todo, weather, game or benchmark-specific generation branches,
blueprints, repair rules or semantic macros. Acceptance scenarios belong in tests and evaluation
data. Provider-specific code ends at authentication, model configuration and streaming transport;
all providers use the same compiler operations and correctness gates.
