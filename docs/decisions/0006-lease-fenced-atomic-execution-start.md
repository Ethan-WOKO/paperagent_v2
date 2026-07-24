# ADR 0006: Lease-Fenced Atomic Execution Start

Status: `ACCEPTED`

Date: 2026-07-24

## Context

A freshly bootstrapped Plan must not expose an event, checkpoint, or execution
authority independently. Starting execution also has to prove that the caller
holds the current live lease generation. Composing the existing event and
checkpoint repositories would create an observable partial state and would not
bind the transition to a fencing token.

## Decision

- A dedicated persistence port performs only the canonical bootstrap version 1
  to started version 2 transition.
- The adapter checks a permanent execution-start marker before trusted time or
  mutable live state. The marker stores the complete request identity and the
  original result.
- An exact marker replay returns the original result. Any different request for
  that Plan is a conflicting replay. Neither path observes the Clock.
- A first attempt observes adapter-owned monotonic lease time once inside the
  same transaction boundary, then validates the bootstrap root, live lease and
  fence, source checkpoint, canonical target, and event occupancy.
- A missing Plan is `NOT_FOUND` only when all Plan-scoped bootstrap,
  checkpoint, event-stream, event-index, lease, and fencing state is absent.
  Any such orphaned state is an execution-start partial-state failure.
- The bootstrap Plan may have an append-only revision suffix before execution.
  Its latest revision must still be a pre-execution shape with no completion
  facts, and the started checkpoint binds that latest revision.
- The event type and payload remain open contracts. Persistence recognizes the
  start fact from the bootstrap marker and canonical checkpoint transition, not
  from event taxonomy.
- One atomic commit writes Plan-global event sequence 1, its global EventId
  index entry, checkpoint version 2, and the permanent marker.

## Failure and Concurrency Semantics

All lease, event, checkpoint, Plan, marker, trusted-time, and fencing state uses
the shared adapter monitor. A successful operation linearizes at the complete
write set before monitor release. Competing release, takeover, event append,
checkpoint save, and revision append operations therefore observe either the
whole start commit or none of it.

Failed attempts do not write event, checkpoint, or marker state. The trusted
time high-water may advance after a structurally valid first attempt. Marker
replays remain authoritative after lease and downstream state changes.

## Durable Adapter Requirement

A durable implementation must validate the current lease and fencing token and
write all four records in one database transaction. It must use trusted
transaction/database time and persist the monotonic time authority described by
ADR 0005.

## Consequences and Residual Risks

- This port does not renew or consume the lease.
- Recovery and Runtime composition are still required before work can execute.
- Start-event taxonomy remains deliberately unfrozen.
- The reference adapter remains in-memory and does not survive process restart.
