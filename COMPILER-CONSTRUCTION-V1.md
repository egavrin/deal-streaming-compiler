# Source-free compiler construction v1

Production Android generation and refinement opt into `useConstructionApi()`. Source-based semantic
transactions remain an internal adapter and a compatibility API for existing tests and compiler clients;
they are not offered as model tools in this mode. Canonical persisted files remain DEAL and Deal UI.

## Wire boundary

A `construct_*` transaction contains one flat `calls` list and `arguments`. Calls invoke upstream
compiler constructors and name temporary results. Transaction arguments refer to these results.
No declaration/body/expression/source argument can contain executable text. Only text literals accept
arbitrary strings; the compiler quotes these as inert data. Names and types are validated identifiers.

Examples of construction calls:

```json
[
  {"id":"s","op":"reference","name":"state"},
  {"id":"n","op":"field","object":"s","name":"count"},
  {"id":"one","op":"integer","value":1},
  {"id":"sum","op":"binary","operator":"+","left":"n","right":"one"}
]
```

This is compiler construction transport, not runtime bytecode. The model never supplies the text
`state.count + 1`; the upstream compiler projects it from calls. Batch ids are local operand aliases,
not persistent semantic identities. Revision targets and accepted repair slots remain compiler-owned.

Core constructors live in DEAL. Framework update markers and Deal UI construction live in Deal UI.
Streaming compiler adapts schemas and forwards batches; it does not emit language syntax.

## Validation and atomicity

- Unknown/backward-unavailable handles, duplicate handles, invalid names, output-kind mismatch and
  resource violations are rejected before semantic transaction mutation.
- Actual type checking, borrowed-state rules and UI bindings run through the existing authoritative
  compilers after projection. Constructor acceptance alone is NOT a semantic correctness guarantee.
- Semantic rejection uses existing repair slots. Slot writes also require constructor batches.
- Canonical sources are published only after final pair validation; no fallback to raw-source tools.

## Current coverage and limits

Covered: integer/boolean/text values, references, fields, core indexing and empty arrays, records,
arithmetic/comparison/boolean expressions, calls, locals, assignments, returns, blocks, if/while,
record/function/update declarations; UI components, actions, When, ForEach, views and UI bodies.
Deal UI constructor tools do not expose core indexing, arrays, assignments or arbitrary calls.

Not yet covered: the complete DEAL language (including async/effect declarations, break/continue,
decimal literals), all view annotation variants, compiler-directed per-constructor type-hole selection,
or repair-scope expansion across artifacts. A repair may still rebuild a whole rejected body as calls.
The existing greenfield batch boundaries also remain; this release changes the model/source boundary,
not all granularity issues. The general repair API does not yet retain accepted construction nodes
inside a rejected slot independently.

Schemas are explicit and larger than source-edit schemas. No claim of fewer tokens or lower latency
is made before measuring live traces. The protocol uses JSON operation transport but not a nested
JSON AST or a newly executable language. Source in inspection results is read-only context.

## Tests

`sourceFreeConstructionCompilesAndRepairs` exercises a complete scripted counter build, UI type
failure and repair. It rejects the old source-writing tool and source text in a handle field, checks
identifier injection and duplicate handles, and checks that the canonical program is emitted by the
compiler. Live device traces are separate evidence and must never be described as these scripted tests.
