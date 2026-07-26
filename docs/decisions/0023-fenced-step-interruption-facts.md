# ADR 0023: Fenced Step Interruption Facts

## Status

Accepted for Wave 3.

## Decision

Persistence owns one narrow, lease-fenced interruption authority for the one
currently `ACTIVE` Plan Step. Its public surface has exactly three explicit
operations: pause, fail, and cancel. The target outcomes are fixed:

- pause changes the target Step to `PAUSED` and the Plan to `PAUSED`;
- fail changes the target Step to `FAILED` and the Plan to `FAILED`;
- cancel changes the target Step to `CANCELLED` and the Plan to `CANCELLED`.

For a first write, the authority observes adapter-owned lease time once and,
under the same transaction or monitor, validates the canonical bootstrap and
execution-start roots, the latest Plan revision, current checkpoint and event
head, the live lease fence, and the H0-rooted execution-mutation chain. The
chain recognizes only literal `STEP_ACTIVATION`, `STEP_COMPLETION`,
`STEP_PAUSE`, `STEP_FAIL`, and `STEP_CANCEL` marker identities. Every link has
one complete backing marker, preserves global order, strictly advances the
checkpoint version, and has a tip matching the current checkpoint and event
projection.

An interruption source must have an `ACTIVE` Plan, exactly one active target
Step, no completion fact for that Step, and every other Step either
`NOT_STARTED` or `SUCCEEDED` with the existing completion-fact invariant. The
candidate checkpoint preserves its Plan and TaskFrame binding, revision,
receipt list, state-key set, and all non-target states. It changes only the
target to the operation's fixed state and uses the matching fixed Plan state.
Event taxonomy and payload remain opaque to Persistence.

`(PlanId, interruption EventId)` is one permanent identity across all three
operations. The marker stores the original typed request, result, and exact
provenance link. Exact replay resolves that marker before Clock, lease, or
mutable source validation, so it returns the original result after takeover,
later execution progress, or source removal. A changed request or different
interruption kind with the same identity conflicts; malformed, duplicate,
cross-map, orphaned, or mismatched interruption markers fail closed.

A successful first write atomically appends only the interruption event and
global event index, the next versioned checkpoint, one interruption marker,
the matching execution-mutation link, and the new mutation head. It does not
append or rewrite a Plan revision, completion fact, receipt, durable effect
fact, workspace/context fact, lease, ordinary idempotency record, or external
state.

## Consequences

This slice does not resume a paused Step; retry, replan, repair, recovery
implementation, Step-kernel, and Agent-Loop behavior remain separate later
work. It creates no Runtime composition, Provider, Sandbox, Workspace,
filesystem, network, model, API, UI, user action, final synthesis, production
database adapter, V1 migration, or external effect execution.

The in-memory adapter provides process-local linearization only. A durable
adapter must preserve the same replay ordering, validation rules, and atomic
write set in one transaction using adapter-controlled trusted time.

## Dependency and Merge Order

The only valid base is PR #91's GitHub merge commit
`2cf944948d7fe31627fc68a86e19a27765b3efae`. This slice follows fenced Step
completion and append-only revision, and precedes the post-interruption
handoff, fenced replan, atomic Step Recovery inspection, Runtime Step Recovery
composition, the single-turn Step kernel, the bounded Step Agent Loop, and
bounded repair/replan.
