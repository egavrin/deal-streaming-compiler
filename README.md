# DEAL Streaming Compiler

DEAL Streaming Compiler is a provider-neutral generation engine for building and modifying checked
DEAL applications with language models. It exposes a compact, revision-scoped Agent Surface derived
from the richer DEAL and Deal UI compiler APIs.

The model does not receive the complete compiler API and does not submit raw application source on
the canonical construction path. It calls narrowly authorized compiler operations. The engine
lowers those calls, validates every candidate with the production compilers, and publishes only a
checked `app.deal` plus `app.dealui` pair.

> **Status:** active research prototype. The compiler protocol, construction surface and repair
> workspace are implemented and covered by deterministic tests. Product-level success and latency
> gates across held-out applications are still in progress.

## Architecture

```text
user request
  -> generation engine
  -> compact Agent Surface for the current revision
  -> LLM compiler-tool call
  -> DEAL compiler transaction
  -> checked DEAL + AppInterface
  -> compact Deal UI Agent Surface
  -> LLM compiler-tool call
  -> Deal UI compiler transaction
  -> checked app.deal + app.dealui
```

At runtime the LLM is no longer involved:

```text
app.deal -> DEAL runtime -> AppState
app.dealui -> checked UI IR -> platform renderer
renderer -- action --> DEAL runtime -- new state --> renderer
```

The responsibilities are deliberately separated:

- **DEAL** owns language syntax, semantic analysis, source construction, atomic edits and DEAL
  diagnostics.
- **Deal UI** owns the declarative UI language, AppInterface bindings, component-pack contracts,
  UI construction, subtree edits and UI diagnostics.
- **DEAL Streaming Compiler** owns the LLM loop, Agent Surface projection, transport-neutral tool
  contracts, generation stages, repair policy and metrics.
- **Hosts such as DEAL Studio** own provider transport, persistence, runtime integration and native
  rendering.

DEAL and Deal UI are transpiler/compiler projects. This repository must not duplicate their parsers,
type systems or semantic checks.

## Agent Surface

The rich compiler API remains internal. For each round the model receives only:

- the user instruction;
- a source digest and short revision-local aliases;
- the minimum dependency cone needed for the current change;
- one currently authorized tool with a strict schema;
- structured diagnostics for a rejected unit, when repair is required.

Greenfield generation uses source-free constructor transactions such as
`construct_apply_deal_batch` and `construct_apply_deal_ui_changes`. Constructor calls use short local
handles and typed operands. They are lowered to canonical source by the compiler and cannot inject
raw source text.

During repair, valid independent operations remain staged. The model receives only the rejected
slot and its dependency group through `construct_patch_repair_slot`; accepted siblings are not
regenerated. Malformed provider tool calls are transport failures and do not mutate the workspace or
consume semantic-repair budget.

Natural-language modernization uses the same protocol. DEAL changes are applied first. Deal UI is
revisited only when presentation changes or the AppInterface fingerprint changes. The final source
pair is committed atomically.

## Repository Layout

```text
java/streaming/compiler/
  CanonicalRefinementSession.java   generation and modernization state machine
  ConstructionSurface.java         compact model-facing construction tools
bridge/
  CanonicalCompilerBridge.java      portable adapter to DEAL and Deal UI compiler APIs
  CanonicalRefinementSessionTest.java
src/                                original benchmark harness and provider adapters
test/                               Node.js integration and benchmark tests
tasks/                              deterministic benchmark tasks
reports/                            recorded benchmark results
COMPILER-CONSTRUCTION-V1.md         source-free construction protocol
DIAGNOSTICS-AUDIT-2026-09-06.md     compiler diagnostic audit
AGENTS.md                           repository invariants
```

The `src/` direct-vs-semantic benchmark predates the canonical application protocol. It remains a
useful experimental harness, but it is not the product architecture.

## Requirements

- Node.js 20 or newer for the benchmark and Java test harness;
- JDK 25 for the current compiler bridge tests;
- local checkouts of the DEAL and Deal UI repositories for canonical integration tests.

Use explicit paths so test runs do not depend on one developer's directory layout:

```bash
export DEAL_REPO=/path/to/deal
export DEAL_UI_REPO=/path/to/deal-ui
npm test
```

Quick deterministic benchmark smoke:

```bash
npm run benchmark:replay
```

The replay adapter validates the harness without evaluating model quality. Live benchmarks require
a configured provider adapter and must record the model, provider route, prompt/protocol versions,
token usage and latency.

## Compiler Construction Tests

The canonical Java suite covers:

- source-free DEAL and Deal UI construction;
- strict issued-tool grants and stale-digest rejection;
- atomic transaction rollback;
- staged repair slots and dependency groups;
- scoped context expansion without sibling write access;
- UI subtree replacement;
- AppInterface changes and cross-artifact validation;
- transport failure separation from semantic repair;
- multi-revision application development.

The portable bridge tests are exercised by `npm test`; the focused Java entry point is
`bridge/CanonicalRefinementSessionTest.java`.

## Legacy Benchmark Harness

The original benchmark compares direct DEAL source generation with typed semantic completion using
the same production compiler/runtime oracle. It includes replay, OpenRouter and local Ollama model
adapters, 40 generated tasks, token accounting and stored reports.

Examples:

```bash
npm run benchmark:large
npm run benchmark:openrouter
npm run benchmark:local-small
```

Recorded results live under [`reports/`](reports/). Treat them as protocol experiments, not as
current product acceptance results.

## Current Limitations

- The canonical Agent Surface is still being optimized for fewer input tokens and model round trips.
- Complex held-out applications do not yet meet a published 95% final-success gate.
- Provider tool-call implementations differ; schema and transport failures require dedicated
  compatibility testing.
- The Android host currently embeds the compiler stack through a pinned DEX bridge. A standalone
  process/service boundary remains future work.
- Functional request fidelity and visual quality require separate evaluation in addition to compiler
  acceptance.

See [COMPILER-CONSTRUCTION-V1.md](COMPILER-CONSTRUCTION-V1.md) for the constructor wire protocol and
[AGENTS.md](AGENTS.md) for non-negotiable architecture boundaries.
