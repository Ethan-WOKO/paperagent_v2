# ADR 0016: Lease-Fenced Plan Execution Context

## Status

Accepted for Wave 3.

## Context

Execution start establishes the permanent H0 root, and Workspace
materialization produces a replay-safe Provider fact. Neither fact binds a
Workspace to a Plan or authorizes a project-backed Step mutation. Runtime-only
inspection would be bypassable and would create a time-of-check/time-of-use
gap.

Workspace I/O and Persistence cannot be one transaction. Persistence therefore
needs an append-only handoff that survives response loss without claiming that
it performs or verifies filesystem work.

## Decision

Persistence exposes `PlanExecutionContextRepository` with three operations:

- `reserve` permanently binds one source-backed Plan to one exact
  `WorkspaceMaterializationSpec` at committed execution-start H0;
- `confirm` appends the exact Provider manifest fingerprint under a current
  lease and fence; and
- `inspect` reads the coherent reserved or confirmed fact without consulting
  Clock, lease state, Workspace, or another repository.

Reservation and confirmation are independent setup facts. They do not write a
Plan event, checkpoint, receipt, revision, execution-mutation head, or
provenance link. H0 remains unchanged.

The in-memory adapter stores the full original reservation and confirmation
requests and results, the frozen H0, and a permanent Workspace owner record.
Every Plan owns at most one Workspace identity, and every Workspace identity
belongs to at most one Plan. Markers and owners are never overwritten,
released, rebound, or removed. The owner record is the non-reuse authority for
the lifetime of `InMemoryPersistence`; future cleanup cannot authorize
WorkspaceId reuse.

Persistence reuses `WorkspaceMaterializationSpec` and `ContentHash` from
`agent-contracts`. It does not depend on or accept
`VerifiedWorkspaceMaterialization`.

## Eligibility and Replay

The first reservation requires a canonical source-backed Plan, committed
execution-start H0, checkpoint version 2, event head 1, zero successor
mutations, exact caller CAS, and a current live lease/token/fence. The spec
source must exactly equal the stored TaskFrame source.

The first confirmation requires a coherent reservation, current authority
still equal to its frozen H0, no successor mutation, the exact reserved spec,
and a current live lease/token/fence. A takeover worker may confirm an older
reservation without rewriting the reservation's historical owner and fence.

Before Clock or current lease inspection, each operation validates only its
local context marker/owner closure. A coherent exact permanent request replays
the original result. A changed permanent request conflicts. Local context
corruption is partial before replay, conflict, or not-found classification.
For a structurally valid first mutation attempt, the Persistence operation
observes trusted time once before classifying the Plan root/execution and
validating source,
eligibility, CAS, and current lease authority. A corrupt root may therefore
advance the trusted-time high-water mark, but cannot write context state. Exact
replay remains available after lease release, expiry, takeover, confirmation,
and legal later Step activation.

## Source-Less Plans

Only the stored `TaskFrame.sourceProjectVersion()` determines whether a Plan is
project-backed. A valid source-less Plan with no context occupancy cannot
reserve or confirm a context, and context inspection returns not found. Its
existing Step activation remains legal.

Any reservation, confirmation, or owner reference for a source-less Plan is
partial state. Capabilities do not synthesize a ProjectVersion or temporary
Workspace.

## Step Activation and Recovery

The first project-backed Step activation must observe a coherent confirmed
context inside the same persistence monitor before its existing lease, CAS,
eligibility, event, target, and six-write commit. No context or a coherent
reservation is not eligible; corrupt context is partial. Source-less
activation with no context follows the existing behavior.

An existing self-consistent activation marker still resolves exact replay or
conflict before Clock and before the context gate. Later context corruption
does not rewrite an already committed activation fact.

Execution-start recovery keeps its public READY, COMMITTED, and ADVANCED
surfaces. READY with context occupancy is partial. At H0, no context,
reservation, and confirmation are all compatible with COMMITTED. A successor
chain is coherent only for a source-less Plan with no context or a
project-backed Plan with confirmed context. Reserved-plus-successor,
source-backed successor without confirmation, source-less context, and all
orphan or mismatched marker/owner states are partial.

## Consequences and Residual Risks

- Response-loss retries converge without reading mutable lease state.
- The activation authorization check is not bypassable by Runtime call order.
- Workspace identity is not reused during one in-memory adapter lifetime.
- The reference markers are not durable across process restart.
- Persistence stores but does not verify the Provider fingerprint.
- A confirmed Workspace can later change or disappear.
- Runtime cross-adapter materialization/recovery, effect facts, Receipt
  ownership, Step completion/recovery, and the bounded Agent Loop remain
  deferred.
