# Repair workspace v2

Status: implementation in progress; production cutover and live-model gates are not passed.

Checkpoints in DEAL, Deal UI, streaming-compiler and Studio:
`pre-repair-workspace-v2-2026-09-07`.

The transpilers own diagnostic causes, dependency obligations, repair groups and grants. The
engine only translates issued grants into source-free constructor tools. Application behavior
relative to a natural-language request is outside compiler correctness.

## Protocol

`inspectRepair` classifies a workspace as LOCAL, DEPENDENCY_GROUP, EXPANSION_REQUIRED or
UNSUPPORTED. It returns structured obligations, minimal context, writable slot ids and expansion
choices. A missing symbol has a kind/name/namespace, not a fabricated semantic identity.

`expandRepairScope` selects only compiler-issued expansion choices, verifies the workspace digest,
and returns a revision-bound grant. This does not mutate candidate sources. Related previously
checked slots may become writable; independent slots remain sealed.

`applyRepairTransaction` verifies the grant against the unchanged workspace, applies slot patches
and authorized new dependencies atomically, and recompiles. Old sources remain authoritative until
the whole candidate is accepted. New dependencies must satisfy their exact issued obligation.

Transport failures, argument failures, semantic failures and scope expansion have distinct counters.
Expansion never resets budgets. No response may request repair while exposing neither a legal tool
nor an explicit unsupported outcome. Legacy local-patch APIs remain compatibility entrypoints.

## Gates

1. Diagnostic inventory and deterministic agent tests precede production adoption.
2. Tests use emitted tools, not direct internal repair methods, for end-to-end acceptance.
3. Cover missing dependencies, dependent groups, stale grants, invalid patches, sealed siblings,
   cycles/no-progress, rollback and all existing language restrictions.
4. Property-based cases record seeds and minimized fixtures; real provider traces are sanitized.
5. Only after deterministic gates pass: pinned DEX/APK, 30 Flash/non-reasoning runs, Oppo bridge QA.
6. Compilation and runtime admission are not evidence of business behavior correctness.

## Implementation ledger (2026-09-07)

Implemented, with generation-engine v2 selected through explicit capability negotiation:

- Structured missing-type and direct missing-callee facts survive diagnostic scoping.
- Java inspect/expansion/transaction APIs issue digest-bound grants for exact dependencies.
- Dependency additions validate the declaration kind, name, namespace and known call arity.
- Unsupported preconditions do not receive a fictitious local patch grant.
- Legacy local patch operations delegate to the shared transaction implementation.
- Deal UI exposes a DEAL transaction wrapper that retains its framework validation.
- Core regression tests exercise dependency addition, tampered/stale grants, rollback and
  preservation of an unrelated slot. They are registered in the core gate manifest.
- Slot dependency discovery uses parsed declarations, types and scoped references instead of
  lexical identifier occurrence. Existing function/block edits are projected into their enclosing
  declaration before collecting references, preserving parameter and local scope. This is a
  declaration-level dependency cone, not an expression-level minimal cone.
- Core and UI diagnostic inventories include language, protocol, constructor, framework and
  generated-runtime codes. The portable test scans production diagnostic literals and fails on
  unregistered codes. Registration is distinct from having a proven repair for every code.
- Constructor failures carry structured missing-handle and expected/actual-kind facts. UI type
  mismatch diagnostics carry expected/actual types. Cross-artifact diagnostic locations do not
  determine repair ownership: unreachable-action diagnostics explicitly request UI repair.
- UI transactions support the same digest-bound group grants. Shared state paths, action bindings
  and parent/child relations contribute to UI groups. UI workspaces bind DEAL and component-pack
  bytes so changed validation context is rejected before applying a patch.
- `useConstructionApi().withRepairProtocol("repair-workspace-v2")` negotiates the capability.
  Android's portable adapter negotiates v2 in both generation and refinement factories by default
  following the user's September 7 instruction. Unsupported capabilities fail closed.
- The source-free engine exposes scope expansion, fixed-slot group patches and granted dependency
  additions. Repair context is limited to the group/dependencies, with bounded read-only queries.
- A deterministic agent test drives emitted tools from a missing DEAL type through dependency
  insertion, UI type-error repair, and canonical-pair publication. Legacy tests remain separate.
- A core test reopens a SEALED dependent provider after a failed repair while preserving an
  independent slot fingerprint.
- UI reachability/capability diagnostics retain compiler-issued insertion permissions when scoped
  to rejected edits. Candidate node IDs are not reused: the checker reissues a permission on the
  nearest untouched base container. Replacing a whole container still requires repairing that
  container; overlapping insertions are not offered.
- `inspectUiRepairInsertions` and `expandUiRepairInsertion` allocate an empty compiler-owned child
  slot, without applying source or consuming a semantic repair round. Empty child slots require
  repair. Pending placeholders cannot be repeatedly allocated at the same parent. Old grants expire.
- The source-free deterministic agent follows `construct_replace_deal_ui_subtree` ->
  `expand_ui_repair_scope` -> `construct_apply_repair_transaction`. The final transaction preserves
  the original candidate edit and exact DEAL bytes. Invalid children preserve the canonical UI.

Still required for the complete repair-v2 release gates (the Studio default is internal):

- Successful tool-driven tests for every advertised repair family, not only registered codes.
- Compiler-issued transactions that expand UI repair into DEAL schema/handler changes. Current UI
  group transactions never grant arbitrary DEAL writes; unsupported cases stop. New child slots
  currently cover compiler-issued action-reachability and host-capability insertion obligations;
  they do not claim all structural repair families.
- Standalone stateless JSON decoding for workspace/grant DTOs (session bridge JSON is supported).
- Broader deterministic-agent, blocked-only, mutation/property and sanitized real-trace coverage.
- Full provider-level transport/argument/semantic retry accounting and progress-cycle acceptance.
- Toolchain pinning, live-model evaluation and device validation.

The new core tests and existing CompilerWorkspaceTest pass. DiagnosticRangeTest passes 1175
checks and the existing DiagnosticClassificationTest passes 504 checks (this is the core code
registry, not the new all-frontends repair-coverage gate). The existing portable refinement
session test also passes. The full core runner cannot compile on this host with its current
hard-coded `/usr/share/java` paths because JUnit/Hamcrest are absent there. This is not a passed
full-suite gate. No APK or model benchmark has been produced for v2.

Additional targeted UI checks: UiRuntimeInvariantTest passes 429 checks; UiCompilerWorkspaceTest
passes including stale DEAL/pack-context grant rejection. UiFrameworkTest was attempted through
the portable classpath but needs the separate built DEAL distribution; it is not counted as passed.
