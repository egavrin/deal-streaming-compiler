# Compiler Diagnostics Audit

Date: 2026-09-06
Status: confirmed diagnostic-loss fixes implemented and regression tested; not deployed to Android.

## Scope And Evidence

Reviewed the diagnostic path through DEAL parser/checker carriers, shared compiler workspace,
Deal UI parser/framework checker, canonical cross-artifact validation, staged repair slots,
streaming-compiler Agent Surface, and Android refinement trace handling. This is a pipeline audit,
not a claim that every possible diagnostic has a tested recovery or that all release gates pass.

Starting revisions:

| Repository | Revision |
| --- | --- |
| DEAL | 675d09fca43cbb5db0bfe71d31ad9f92d687d9d0 |
| Deal UI | 93ac664fb661dce0645ae6ac60aaee2ad5964981 |
| Streaming compiler | 0d6bb34f051757b070b54fa244ee9b10019294af |

Local fixes described here are working-tree changes on those revisions. Android still pins its
previous compiler DEX; source tests do not establish behavior of the installed phone build.

The prior frozen-revision stability series contains three rejected DEAL refinements and one
rejected UI refinement. Errors include E1015/E1005/E1041 and UI1009, followed by no-progress
termination. The archives retain summaries but not complete rejected tool arguments. They prove
failure and insufficient replay evidence, not the exact offending syntax. Do not invent that syntax
or label these four failures fixed without a reproducible rerun.

## Confirmed Findings And Changes

| Priority | Finding | Change | Regression evidence |
| --- | --- | --- | --- |
| P1 | Agent compaction deduplicated by code/message, losing separate occurrences at different locations. | Deduplicate by full structured diagnostic identity internally. Preserve separate occurrences in examples. | Seven distinct locations plus one exact duplicate produce seven unique errors. |
| P1 | A repair error referred to candidate-wide coordinates without including candidate source evidence. | Compiler attaches a bounded excerpt from the rejected source, digest, line origin and truncation flag. Scope adaptation preserves this context. | Candidate digest/CRLF tests and an actual rejected repair slot reaching the model request. |
| P1 | UI2050 and UI2051 each described two unrelated failure classes. | Keep UI2050/2051 for borrowed write/escape. Use UI2060 for missing canonical handler contract; UI2061 for missing host-capability implementation. Update capability repair routing. | Existing borrowed checks plus handler/capability workspace tests. |
| P2 | Raw DEAL notes were lost in the shared protocol and when propagated through Deal UI. | Carry immutable notes in StructuredDiagnostic and UiDiagnostic; preserve them when rescoping errors. | Parser expected/found evidence survives workspace inspection and agent compaction. |
| P2 | DUI parser errors lacked expected/actual tokens and full token ranges. | `require` supplies both tokens; parser failure paths preserve token end coordinates. | Invalid adjacent UI arguments report expected `)` and actual `state`, with a noncollapsed token range. |
| P2 | DUI lexer failures used `<source>` instead of the caller's actual path. | Rebind lexer failure to the supplied source path. | Unterminated comment uses the actual `.dealui` file. |
| P2 | Adapter reduced DUI ranges to a point. | Preserve end line/column in canonical and UI workspace responses. | DUI workspace source-evidence test. |
| P2 | The six-example diagnostic limit silently hid additional unique failures. | Expose `uniqueCount` and `omittedCount`. | Seven-error compaction reports one omitted error. |
| P2 | DEAL `expect` supplied only a generic message. | Preserve code/message and add a note with expected token kind, actual kind and bounded lexeme. | E1015 includes actual `false`; synthetic anchor notes remain present. |

No language grammar, mutable DEAL semantics, borrowed-state rules, rollback rule, or acceptance
scenario changed. No fix inserts a scenario-specific repair recipe.

## Rich API Versus Agent Surface

Rich structured diagnostics now optionally contain `DiagnosticContext`:

```text
version = diagnostic-context-v1
sourceDigest
firstLine
excerpt
truncated
```

Evidence covers at most three source lines, at most 512 Unicode code points per line. It comes from
the actual analyzed candidate, not the committed revision. CRLF, CR and LF are split consistently;
truncation does not split a surrogate pair. Synthetic core diagnostics do not gain fabricated source
evidence. Existing Java construction sites remain source-compatible through an overload; consumers
must rebuild for the extended record.

Agent Surface v9 emits code, severity, message, available expected/actual, bounded excerpt and note
messages. It does not emit internal source digests, opaque owners or raw ranges. Full identity is
used internally for deduplication only. Writable scope still comes exclusively from compiler-issued
repair slots and tool schemas. Source evidence is not permission to edit siblings.

The six-example cap remains. This bounds diagnostic count, not total prompt tokens. Excerpts add
input tokens; whether they reduce total repair cost needs measurement, not an assumption.

## Remaining Findings And Risks

### P1: Failed Requests Are Not Fully Replayable

Android `CanonicalGeneratedAppRefiner.kt` records round metrics and tool names before applying the
response. That is insufficient to replay exact rejected candidates. Add an opt-in internal recorder
of issued surface, full tool-call payloads, base source/pack digests, compiler response and resulting
slot state. Exclude authorization headers and keys; treat generated user data as private, bound
retention, and require deliberate artifact export. Replay must work without a provider call.

Acceptance: every rejected soak step can be reproduced offline with identical diagnostic codes,
owners, slot statuses and unchanged committed sources.

### P1: Combined UI Failure Ownership Can Fall Back To The First Operation

`UiCompilerWorkspace.workspace` checks isolated operations; when all pass independently but the
combined candidate fails, it marks operation zero rejected. `scopeDiagnostic` can associate the
failure with every operation. These are code-confirmed fallbacks, not proven causes of the four
archived model failures. A cross-operation conflict needs compiler-derived related nodes/dependency
group ownership, not an arbitrary editable slot. Add a minimized interacting-operation fixture
before replacing this fallback.

Acceptance: permuting independent operation order does not change the implicated semantic group;
accepted unrelated operations cannot become the only repair target.

### P2: Coordinate Convention Is Not Uniform

Core diagnostic tests use Unicode scalar positions and source/synthetic origins. DUI lexer advances
columns with Java `char` and predominantly handles LF; astral characters and lone CR can therefore
disagree with core conventions. Its source-edit line-offset helper also needs a coordinated review.
Changing just rendered column numbers would risk node/source-edit misalignment.

Acceptance: shared Unicode/CRLF/CR fixtures across parser diagnostics, compiler-owned nodes and
source replacement; identical error-token selection before and after a preceding astral character.

### P2: Rich Source Provenance Is Still Incomplete

Shared `SourceRange` does not retain all raw scalar offsets and source/synthetic origin fields.
Candidate evidence improves repair context but is not an operation-local source map. A long line
may be truncated before the offending column. Add compiler-owned bounded evidence queries and
origin-aware ranges; never infer candidate-to-slot offsets in Android or the model.

### P2: Cascades And Hidden Diagnostics Need A Retrieval Contract

DEAL parser recovery can emit cascaded errors without a primary/secondary cause relation. DUI
exception-based checking is often fail-fast. Six examples now declare omissions but the repair
surface has no diagnostic paging tool. Add ordered primary diagnostics and bounded continuation
queries. Do not suppress later errors solely because they share a code.

### P2: Structured Semantic Facts Are Uneven

Many expected/actual values remain embedded in messages; core workspace has an E3010-specific
expected description rather than a uniform producer-level fact representation. Parser notes are an
incremental improvement, not a complete typed diagnostic schema. Streaming repair descriptions
still have code-specific hints. Move reusable type/symbol/binding facts upstream; do not parse prose
to reconstruct them in Studio.

### P2: Diagnostic Code Governance Needs A Deal UI Registry

DEAL has a registered DiagnosticCode catalog. DUI codes are string literals across producers, which
allowed the confirmed collisions. Add a versioned registry with code meaning, phase and producer
family, and a test rejecting unregistered or conflicting definitions. The newly assigned codes are
documented above, but a general registry was not implemented in this patch.

### P2: Check Every Integration Path Before Deployment

Android still contains graph-compiler paths converting exceptions to `failure.message` in
`CanonicalDealProgramGraphCompiler.kt` and `CanonicalDealUiGraphCompiler.kt`. The repair-session
improvements do not automatically upgrade those string-only boundaries. Trace their actual caller
coverage and either migrate them to the structured API or explicitly mark them unsupported; do not
claim generation and refinement have identical diagnostic fidelity merely because both compile.

## Verification

| Test | Result |
| --- | --- |
| DEAL DiagnosticClassificationTest | 504 passed; 114/114 codes documented, 21/21 triggered classification fixtures |
| DEAL DiagnosticRangeTest | 1175 passed |
| DEAL CompilerWorkspaceTest | Passed, including rejected source evidence and Unicode truncation |
| Deal UI framework suite | 129 passed |
| Deal UI runtime invariants | 429 passed |
| Deal UI workspace suite | Passed, including parser evidence and code separation |
| Streaming canonical/portable refinement integration | Rebuilds compiler sources; includes repair-slot evidence and compaction tests |

The raw range test previously assumed exactly one synthetic-anchor note; it now checks the anchor
is retained while allowing additional expected/found notes. No range correctness assertion was
removed. These checks are not a full repository CI run, Android instrumentation run or provider
benchmark. Live model repair success, latency and token improvement remain unmeasured for this patch.

## Delivery Gates

1. Finish review of these changes, commit/pin all three upstream revisions and rebuild the DEX.
2. Add replay capture and minimized fixtures for the remaining P1 findings.
3. Reproduce fixed diagnostic classes through the real app bridge, including both generation and refinement.
4. Repeat frozen-base requests with the same provider/model/settings and retain complete evidence.
5. Report first repair success, final success, repeated candidate count, input/output tokens and
   runnable latency. Compare against the frozen baseline; do not substitute compiler test success
   for model repair success.

Conclusion: rejecting an invalid candidate was working in the observed runs. Supplying enough precise
evidence to repair it was not consistently working. This patch fixes verified information loss but
does not certify the entire diagnostic and repair system as production-ready.
