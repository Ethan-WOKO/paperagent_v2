# ADR 0024: Fenced Append-Only Plan Replan

## Status

Accepted for Wave 3.

## Decision

Persistence owns one narrow, lease-fenced authority that appends exactly one
Plan revision at a safe active execution boundary. Its only public surface is
`PlanReplanRepository.replan(PlanReplanRequest)`. Replan is a between-Steps
operation: the source Plan is `ACTIVE`, each Step is either `NOT_STARTED` or
`SUCCEEDED` with the existing completion-fact invariant, and no `ACTIVE`,
`PAUSED`, `FAILED`, or `CANCELLED` Step exists. The target remains `ACTIVE`.

For a first write, the authority observes adapter-owned lease time once and,
under the same transaction or monitor, validates canonical bootstrap and
execution-start roots, the current Plan, checkpoint, Event stream/index, live
lease fence, and H0-rooted mutation chain. The chain recognizes only literal
`STEP_ACTIVATION`, `STEP_COMPLETION`, `STEP_PAUSE`, `STEP_FAIL`,
`STEP_CANCEL`, and `PLAN_REPLAN` marker identities. Every link has exactly one
backing marker, preserves Event order, strictly advances the checkpoint
version, and ends at the current checkpoint and Event projection.

The proposed revision must be a direct next revision for the same TaskFrame,
with nondecreasing time and a whole-history-valid Plan. It preserves every
existing completion fact and its referenced Step definition exactly; it adds
no completion fact. Incomplete Steps may be added, removed, or redefined
subject to the normal graph validators. The candidate checkpoint binds the new
revision and Event cursor, preserves receipts and monotonic time, has exactly
the new revision's Step IDs, marks completed-fact Steps `SUCCEEDED`, and marks
all other Steps `NOT_STARTED`.

`(PlanId, replan EventId)` is a permanent identity. A marker stores the exact
request, result, and mutation link. Exact replay rebuilds durable provenance
before Clock, mutable Plan/checkpoint projections, source inspection, or lease
validation. Changed same-identity requests conflict. Duplicate, orphaned,
cross-map, mismatched, or torn marker/link/history states fail closed as
`PLAN_REPLAN_PARTIAL_STATE`.

The first-write transaction atomically writes only the appended Plan revision,
Event stream entry and global EventId index, next versioned checkpoint, replan
marker, mutation link, and mutation head. It never writes completion facts,
receipts, effects, workspace/context, lease, ordinary idempotency state,
external effects, Provider/Sandbox state, user result, or ProjectVersion.

## Consequences

This slice does not resume paused work or implement retry, repair, Step
Recovery, Runtime composition, a Step kernel, Agent Loop, external effect
execution, Provider/Sandbox/Workspace invocation, filesystem/network/model or
secret access, API/UI, Final Synthesis, a production database adapter, or V1
migration. The in-memory adapter offers process-local linearization only; a
durable adapter must preserve replay ordering, trusted-time behavior, and the
atomic write set in one transaction.

## Dependency and Merge Order

The only valid base is PR #95's GitHub merge commit
`c96adc31ef07e0011aa5c5907ef39f5558bece35`. This slice follows fenced
active-Step pause/fail/cancel facts and precedes atomic Step Recovery
inspection, Runtime Step Recovery composition, the single-turn Step kernel,
the bounded Step Agent Loop, and bounded repair/replan.
