# ADR 0021: Fenced Effect Outcomes and Receipt Ownership

## Status

Accepted for Wave 3.

## Decision

Persistence owns provider-neutral progress and the one final
`ExecutionReceipt` for a previously persisted `EffectIntent`. Progress is
ordered from sequence 1 within its durable `ToolCallId`; its opaque progress ID
is immutable and cannot be reused with changed data. A final receipt is also
keyed by that durable tool-call identity, and only the outcome authority may
write it for an effect intent.

For an existing progress or result marker, the adapter validates the marker's
stored facts and returns an exact request as `REPLAYED` before observing the
Clock, live lease, or mutable execution source. Result integrity also requires
the exact matching receipt in the receipt store and the durable intent marker.
Changed replay input is rejected as `CONFLICTING_REPLAY` at its request field.
Read ports return immutable stored facts and require no lease.

For a first progress or result, the adapter validates the intact durable intent,
its committed active Step activation, and the current lease token/fence under
one persistence monitor. A first progress must be exactly the next sequence;
new progress after a final result is rejected. A first result writes its marker
and receipt under the same monitor. Ordinary receipt append rejects every tool
call that has a durable effect intent, so an unowned pre-existing receipt cannot
be adopted as an owned result.

## Consequences

This slice does not execute an effect and creates no Runtime, Provider,
Sandbox, Workspace, filesystem, network, model, API, or UI behavior. It does
not append events; alter checkpoints, Plan revisions, Plan or Step states,
workspace/context facts, completion facts, recovery state, or ordinary
idempotency. `ToolResult` remains an unowned generic contract.

## Dependency and merge order

The only valid base is PR #83's merge commit
`dd773766640e7ebe241f6b43cfcaab9ac11fc3b0`. This slice follows durable effect
intent and precedes completion/revision, pause/fail/cancel, fenced replan, Step
Recovery, the single-turn Step kernel, the bounded Step Agent Loop, and bounded
repair/replan.
