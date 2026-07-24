# ADR 0007: Runtime Execution-Start Materialization

## Status

Accepted for Wave 3.

## Context

Atomic execution start needs a deterministic event and checkpoint candidate
before persistence can decide whether that candidate is still eligible to
commit. The public `PersistedPlanBootstrap` value is a snapshot carrier. It
does not prove that its snapshot remains current and it is not, by itself,
execution admission.

The Runtime boundary must therefore distinguish pure candidate construction
from repository authority. It must also fail closed when a publicly
constructed bootstrap contains a checkpoint that is not the canonical
not-started source shape.

## Decision

`ExecutionStartMaterializer` is a pure calculation. It accepts:

- a `PersistedPlanBootstrap` snapshot;
- a retry-stable event draft whose TaskFrame, Plan and sequence cannot be
  overridden by the caller; and
- a caller-supplied checkpoint creation time.

It produces:

- an `EventEnvelope` bound to the snapshot TaskFrame and Plan with Plan-global
  sequence `1`; and
- a `Checkpoint` bound to the snapshot latest Plan revision with cursor `1`,
  Plan state `ACTIVE`, every latest-revision Step `NOT_STARTED`, no receipt
  references and the supplied creation time.

Before constructing either candidate, the materializer validates the source
checkpoint against the snapshot TaskFrame and Plan using
`CheckpointValidators.requireValid(source, taskFrame, plan, null)`. Contract
violations propagate unchanged. It then requires, in order:

1. source cursor `0`;
2. source Plan state `NOT_STARTED`;
3. every snapshot-latest Step state `NOT_STARTED`; and
4. no receipt references.

Only remaining canonical-shape failures that were not expressed by the
earlier checkpoint validation use `NON_CANONICAL_BOOTSTRAP`. Existing
`ContractViolationException` codes and paths always take priority and
propagate unchanged. The remaining Step-state check is aggregate so Map
iteration cannot select the primary error.

Validation priority is:

1. record required-value and identifier validation;
2. null method request validation;
3. source contract validation;
4. source canonical-shape validation;
5. `EventEnvelope` construction;
6. target `Checkpoint` construction;
7. target checkpoint validation against the source; and
8. immutable result construction.

The materializer does not read a clock, environment, repository, current Plan
or live checkpoint, and it does not allocate identifiers or perform I/O.

## Authority Boundary

The result is a snapshot-derived candidate. It is not a fresh-admission
decision, execution authorization or committed fact. A revision appended
after the snapshot can make the candidate stale. Event ID occupancy and all
other commit eligibility can also change concurrently.

The atomic execution-start repository remains the final authority for current
revision, live checkpoint, event uniqueness, fencing and commit. It must
reject stale or conflicting candidates; Recovery owns the resulting
reconciliation.

## Consequences

- Repeating the same request yields equal candidates across instances and
  concurrent calls.
- Contract validation codes and paths remain authoritative for source,
  event and target structural failures.
- Runtime-specific validation is limited to the four canonical source-shape
  invariants.
- This decision introduces no start event taxonomy, lease behavior, Step
  activation, repository composition or V1 migration.
