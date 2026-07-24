# ADR 0012: Lease-Fenced Atomic Step Activation

## Status

Accepted for Wave 3.

## Context

Atomic execution start establishes a permanent, token-free start fact, but it
does not authorize a later Step mutation. The public Plan, event, checkpoint,
and lease reads can become stale immediately, and composing their ordinary
write ports would expose partial state and let a stale worker bypass lease
fencing.

The execution-start recovery inspector also needs a durable explanation for a
projection that has advanced beyond the exact start cut. Appearance alone is
not enough: a later checkpoint and event must be connected to the permanent
start root by one continuous chain of fenced atomic commits.

## Decision

Persistence exposes a narrow `StepActivationRepository` for only the
`NOT_STARTED -> ACTIVE` transition. A first attempt executes under one adapter
transaction or shared monitor and:

1. observes adapter-owned trusted monotonic lease time exactly once;
2. validates the permanent bootstrap and execution-start roots;
3. validates one continuous execution-mutation head and marker-backed
   provenance chain;
4. checks the current live lease token and fence;
5. compares the expected latest revision, checkpoint version, and Plan-global
   event head;
6. checks dependency eligibility and the single-`ACTIVE` Step policy;
7. validates the retry-stable activation event and canonical target
   checkpoint; and
8. atomically commits the event, EventId index, checkpoint version `v + 1`,
   permanent activation marker, provenance link, and new mutation head.

Execution start initializes the mutation head `H0` in its existing atomic
write. `H0` is derived from the committed started checkpoint and start event.
It does not change the execution-start public request, result, or replay
surface. An exact start replay never resets an already advanced head.

The activation marker is scoped by `(PlanId, activation EventId)` and stores
the full request identity and original result. Marker lookup occurs before
Clock or mutable live state. Exact replay returns the original fact after
lease release, expiry, takeover, or later legal progress; a different request
with the same marker identity is a conflicting replay.

Each activation link keeps an exact previous head, result head, and
type-neutral backing marker identity. The chain begins at `H0`, has no fork,
gap, duplicate, detached marker, or unbacked link, and ends at the current
head. Activation links preserve the Plan revision while strictly advancing
checkpoint version and event sequence. The live event and checkpoint
projection must exactly equal the corresponding permanent root or chain-tip
fact.

The first Wave 3 implementation allows at most one `ACTIVE` Step per Plan.
Activation does not execute a model or tool, write a receipt, change a Plan
revision, complete a Step, or invoke Workspace or Sandbox behavior.

## Unfenced Mutation Guard

The permanent execution-start marker is the guard authority. Once it exists:

- a new ordinary `PlanRepository.appendRevision` is rejected with
  `EXECUTION_MUTATION_REQUIRES_FENCE / planId`;
- a new ordinary `EventRepository.append` is rejected with
  `EXECUTION_MUTATION_REQUIRES_FENCE / event.planId`; and
- every ordinary `CheckpointRepository.save` is rejected with
  `EXECUTION_MUTATION_REQUIRES_FENCE / checkpoint.planId`.

Plan revision and EventId exact replay or conflicting identity classification
still precedes the guard. Checkpoint has no permanent operation identity and
therefore has no replay exception.

For a post-start checkpoint save, this guard priority narrowly supersedes ADR
0004's stale-CAS priority. Pre-start checkpoint behavior is unchanged.
Ordinary receipt append remains non-Plan-bound object storage and cannot by
itself create Plan, Step, event, checkpoint, or completion authority.

## Recovery Compatibility

Execution-start recovery inspection reads the mutation head, provenance links,
and activation markers in its existing atomic cut:

- `READY` has no execution mutation state;
- `COMMITTED` has `H0`, no successor link, and an exact start projection;
- `ADVANCED` has a complete marker-backed chain from `H0` to the exact current
  projection; and
- missing, orphaned, detached, forked, rewritten, or mismatched authority is
  `PARTIAL`.

Inspection remains read-only and does not read Clock, lease value, token, or
fence. Full marker requests and tokens never enter recovery snapshots or text
surfaces.

## Durable Adapter Requirement

A durable adapter must perform the same validation and complete write set in
one database transaction. Lease time must come from trusted
database/transaction time or an equivalent adapter-controlled source, with
the monotonic high-water persisted in the same authority boundary.

## Consequences and Residual Risks

- A stale or taken-over worker cannot mutate execution state through the
  ordinary Plan, event, or checkpoint ports.
- Response-loss retries converge on the permanent activation fact without
  consulting current lease state.
- The in-memory adapter models linearization but does not survive process
  restart.
- Plan execution-context binding, durable effect intent, effect result and
  progress commits, Step completion, replan, Step Recovery, and the bounded
  Step Agent Loop remain deferred.
