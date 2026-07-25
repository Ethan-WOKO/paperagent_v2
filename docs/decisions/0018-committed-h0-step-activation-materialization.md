# ADR 0018: Committed-H0 Step Activation Materialization

## Status

Accepted for Wave 3.

## Context

Atomic Step activation requires a retry-stable event and checkpoint proposal
before Persistence can decide whether the proposal is still eligible to
commit. The Runtime proposal must not acquire current authority, inspect a
Workspace, choose a Step, or mutate Persistence.

`PersistedExecutionStartCommitted` is the input trust boundary for this pure
calculation. It proves the current committed H0 shape used by the
materializer. Its nested bootstrap initial checkpoint is historical data and
is neither inspected nor revalidated by this boundary.

## Decision

The caller supplies one exact committed H0 snapshot, one explicit
`PlanStepId`, a retry-stable event draft, and a retry-stable checkpoint
timestamp. The materializer never selects a first, root, or next Step.

For an eligible explicit Step, Runtime derives:

- an `EventEnvelope` whose draft metadata is unchanged, whose TaskFrame and
  Plan identities come from the committed snapshot, and whose sequence is
  exactly `2`; and
- a `Checkpoint` at cursor `2` that preserves the H0 Plan, revision, receipt
  references, and all other Step states while changing only the selected Step
  from `NOT_STARTED` to `ACTIVE`.

The materializer validates only snapshot Step eligibility before constructing
the event. It then validates the target checkpoint against the committed H0
checkpoint with `CheckpointValidators`. Existing Contract violations from
event and checkpoint construction propagate unchanged.

Validation priority is:

1. direct record-field validation;
2. null method-request validation;
3. explicit snapshot Step eligibility;
4. event construction;
5. target checkpoint construction and validation; and
6. immutable result construction.

The public result intentionally does not repeat the selected Step ID. A later
activation composer retains that input and must validate the exact
single-Step delta before assembling any Persistence request.

## Authority Boundary

`MaterializedStepActivation` is a candidate only. It does not establish
authority, persistence, or successful Step activation.

The pure materializer has no repository, lease, fencing token, context,
Workspace, Clock, random source, I/O, thread helper, or external
collaborator. It does not create `StepActivationRequest`. Persistence remains
the final authority for current revision, checkpoint and event heads,
context admission, lease fencing, event identity, replay, and atomic commit.

## Consequences

- Equal inputs produce equal proposals across instances, call order, and
  concurrency.
- Multiple dependency-free roots remain legal; only the caller-selected Step
  is considered.
- Source-backed and source-less snapshots use the same pure calculation.
- Event type, causation, correlation, and payload remain opaque metadata.
- Runtime adds no event taxonomy, payload schema, scheduling policy, or
  currentness claim.
- Contracts, Persistence, Workspace, Provider, API, UI, E2E, and Maven
  dependency surfaces remain unchanged.
