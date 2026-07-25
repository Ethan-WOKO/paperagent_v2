# ADR 0019: Runtime Step Activation Composition

## Status

Accepted for Wave 3.

## Decision

Runtime exposes one stateless `StepActivationComposer` that composes an exact
`PersistedExecutionStartCommitted` H0 proof, an explicit Step, and retry-stable
event/time values into one fenced atomic activation attempt. It invokes the
existing `CommittedStepActivationMaterializer` once, acquires one lease
generation with the caller's owner/token/expiry, validates the returned
authority, and invokes `StepActivationRepository.activate` once. The returned
fencing token is the only fence used in the request.

The composer does not inspect Plan, TaskFrame, Workspace, execution context,
Clock, current repository state, or any lease lifecycle operation. Persistence
remains the authority for current revision/checkpoint/event heads, source-backed
context admission, replay identity, stale rejection, and the atomic write.
Acquired leases are retained for the subsequent effect/recovery flow.

## Result classification

`APPLIED` and `REPLAYED` activation results are committed only after exact
Plan, Step, lease owner/fence, event, and version-3 checkpoint validation.
Lease `REJECTED` is returned as `NOT_ACQUIRED`; atomic Persistence `REJECTED`
is returned unchanged with `RETAINED_FOR_RECOVERY`. Null, unexpected, malformed,
inconsistent, or throwing collaborators become sanitized protocol failures.

## Scope

This slice does not execute effects, write Receipts, complete or revise a Plan,
pause/fail/cancel, replan, recover an unavailable lease, renew/release/take over
a lease, create durable intent, or run an Agent Loop. It does not change
Contracts, Persistence, Workspace, the materializer, APIs, UI, providers,
Sandbox, or V1 code.

## Dependency and merge order

The only valid base is PR #75's merge commit
`644b3009feb59b3c5664aee7adc24d842d6707e0`. This slice follows committed-H0
materialization and precedes provider-neutral effect identity/durable intent,
effect result/progress, completion, pause/fail/cancel, replan, recovery,
single-turn kernel, bounded Agent Loop, and bounded repair/replan.
