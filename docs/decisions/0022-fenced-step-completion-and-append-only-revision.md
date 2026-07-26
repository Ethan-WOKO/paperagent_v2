# ADR 0022: Fenced Step Completion and Append-Only Revision

## Status

Accepted for Wave 3.

## Decision

Persistence owns one narrow, lease-fenced completion authority for the one
currently `ACTIVE` Plan Step. It does not execute an effect or decide whether
the Step's business objective was met. It only admits a supplied
`CompletionFact` after the committed execution source and durable effect
evidence are intact.

For a first write, the authority observes adapter-owned lease time once and,
under the same transaction or monitor, validates the canonical bootstrap and
execution-start roots, the continuous H0-rooted execution-mutation chain, the
live lease token and fence, and the caller's revision, checkpoint, and event
head expectations. The chain has only `STEP_ACTIVATION` and
`STEP_COMPLETION` marker types. Every link has one backing permanent marker,
strictly advances the checkpoint version, and its tip must equal the current
event and checkpoint projection.

A completion may change only the target Step from `ACTIVE` to `SUCCEEDED`.
It appends one Plan revision that preserves all prior Step definitions and
completed facts and adds exactly the target fact. Its checkpoint preserves all
prior Step states and receipt references, appends the new fact's receipt list,
and makes the Plan `SUCCEEDED` exactly when every Step is succeeded.

Completion evidence is derived only from durable effect facts for the target
Plan and Step. Each such intent must be bound to the current activation head,
have one intact owned final receipt, and contribute its receipt ID in canonical
`ToolCallId` order. A Step with no durable effects has no completion receipts.
Missing structurally sound evidence is ineligible; torn intent, result,
receipt, ownership, marker, root, or provenance state fails closed as partial
state.

`(PlanId, completion EventId)` is a permanent identity. The marker stores the
full request, original result, and exact provenance link. Replay resolves that
marker before Clock, lease, mutable source, or effect-outcome inspection. An
exact replay therefore returns the original result after takeover or later
execution progress; a changed request with the same identity is a conflict.

A successful first completion atomically writes only the appended Plan,
completion event and global index, next `VersionedCheckpoint`, completion
marker, provenance link, and execution-mutation head. It does not write a
receipt, effect intent/result/progress, lease, ordinary idempotency record,
workspace/context fact, or external state.

## Consequences

This slice creates no Runtime composer, Step kernel, Agent Loop, effect
selection or execution, Provider, Sandbox, Workspace, filesystem, network,
model, API, UI, user-visible completion, pause/fail/cancel, replan, or
recovery behavior. Durable adapters must perform the same validation and write
set in one database transaction using adapter-controlled trusted time.

The in-memory adapter supplies only process-local linearization and does not
survive restart. Completion facts remain authority records, not user-facing
final synthesis or acceptance.

## Dependency and Merge Order

The only valid base is PR #87's GitHub merge commit
`083b3ef8c0e9e4c10b303a3551053eb55faecb37`. This slice follows durable effect
intent and receipt ownership, and precedes the post-completion handoff,
pause/fail/cancel, fenced replan, recovery, the Step kernel, and the bounded
Agent Loop.
