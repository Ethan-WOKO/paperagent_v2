# ADR 0025: Atomic Step Recovery Inspection

## Status

Accepted for Wave 3.

## Decision

Persistence exposes one narrow, read-only recovery inspection:
`StepRecoveryRepository.inspect(PlanId)`. It returns only a
`PersistedStepRecoveryActive` snapshot. It neither obtains nor validates a
lease, reads a lease token, fencing token, used-token state, or Clock, and it
writes no business state.

Under one transaction or monitor cut, the in-memory adapter validates the
canonical bootstrap and execution-start roots, current Plan, checkpoint,
Event stream/index, H0-rooted mutation chain, receipt linkage, activation
marker, and Plan-execution-context projection. Missing Plan-scoped occupancy
is `NOT_FOUND`; any torn, missing, or corrupt required projection is
`STEP_RECOVERY_PARTIAL_STATE` at `stepRecovery`.

A snapshot is eligible only when the authoritative checkpoint is `ACTIVE`,
exactly one Step is `ACTIVE`, every other Step is `NOT_STARTED` or
`SUCCEEDED` with its completion fact, and the mutation tip is the intact
activation marker for that active Step. No active Step, a terminal or
interrupted Plan/Step, or a current activation superseded by completion,
interruption, or replan is `STEP_RECOVERY_NOT_ELIGIBLE` at `stepRecovery`.

Project-backed TaskFrames require exactly one intact confirmed execution
context bound to the current source. Source-less TaskFrames require no
context and expose `Optional.empty()`. The snapshot is a linearized
observation, not a write authority; Runtime must inspect again after any
future lease acquisition, and all later writes retain their own fences.

## Consequences

This decision does not compose Runtime recovery, acquire/renew/release/take
over leases, resume or retry work, replan or repair, execute a Step kernel or
Agent Loop, invoke effects, Provider, Sandbox, Workspace, filesystem,
network, model, or secret access, expose API/UI, add a production database,
or migrate V1.

## Dependency and Merge Order

The only valid base is PR #99's GitHub merge commit
`1b14f825709f94fc72de0e66661a6f5a7ab85096`. This slice follows fenced
append-only Plan replan and precedes Runtime Step Recovery composition, the
single-turn Step kernel, the bounded Step Agent Loop, and bounded
repair/replan.
