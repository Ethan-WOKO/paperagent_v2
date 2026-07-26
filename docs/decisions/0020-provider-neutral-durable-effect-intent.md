# ADR 0020: Provider-Neutral Durable Effect Intent

## Status

Accepted for Wave 3.

## Decision

Before an external effect can be attempted, Persistence records one immutable
provider-neutral `EffectIntent` keyed by `ToolCallId`. The fact binds one Plan,
one active Step, a nonblank contract kind, typed immutable arguments, the
committed activation Event ID, and the lease owner/fence that admitted it.
`ToolCallId` is the stable identity a later executor can use as its external
idempotency key.

The in-memory adapter keeps these markers in an independent effect-intent
store. It does not reuse the ordinary idempotency repository. On `persist`, it
checks an existing marker first. An exact request returns its original durable
fact as `REPLAYED` without observing the Clock, current lease, or mutable
execution state. Any changed Plan, Step, kind, arguments, activation Event ID,
lease token, or fence is `CONFLICTING_REPLAY` at the relevant request path.

For a first persist, the adapter validates one intact authoritative execution
source, the current committed activation marker, the exact active Step, and the
live lease token/fence under one persistence monitor. It then writes only the
effect-intent marker. `find` reads only that marker and requires no lease.

## Consequences

This slice creates no Provider, Sandbox, Workspace, network, filesystem, or
model call. It does not execute an effect or write a result, progress record,
Receipt, event, checkpoint, Plan revision, completion fact, or recovery state.
It leaves the execution-mutation head and ordinary idempotency store unchanged.
Future fenced effect-result/progress and Receipt ownership work must consume
the durable identity rather than create a second identity.

## Dependency and merge order

The only valid base is PR #79's merge commit
`907a1284772b731362d09c716c3dcb881a07abf7`. This slice follows Runtime Step
activation composition and precedes fenced effect result/progress and Receipt
ownership, completion/revision, pause/fail/cancel, replan, recovery, the
single-turn Step kernel, the bounded Step Agent Loop, and bounded repair/replan.
