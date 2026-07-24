# ADR 0009: Atomic Execution-Start Recovery Inspection

## Status

Accepted for Wave 3.

## Context

The public Plan, checkpoint, event, and lease reads cannot reconstruct the
permanent bootstrap and execution-start marker authority. Combining those
reads can also observe a tuple that never existed at one instant.

Recovery therefore needs a single read-only persistence cut before Runtime
composition can decide whether a Plan is still ready to start, has committed
its atomic start, has advanced beyond that point, or is structurally partial.

## Decision

A dedicated persistence port inspects the bootstrap marker, execution-start
marker, stored root, current Plan, current checkpoint, Plan-global event stream,
EventId index, referenced receipt presence, and Plan-key occupancy under one
adapter transaction or shared monitor.

The classifier is fail-closed:

1. no Plan-scoped occupancy is `NOT_FOUND`;
2. an invalid or missing bootstrap root is `PARTIAL`;
3. a markerless exact bootstrap cut is `READY`, while any other markerless
   occupancy is `PARTIAL`;
4. a valid permanent start fact and its exact current start projection is
   `COMMITTED`;
5. a valid permanent start fact with a recognizable monotonic successor is
   `ADVANCED`;
6. all marker, stream, index, cursor, revision, or receipt-linkage
   contradictions are `PARTIAL`.

Event sequence gaps remain legal because the Plan-global sequence is strictly
increasing rather than contiguous. Checkpoint and current completion-fact
receipt references are checked only for ID presence. Receipt content and
receipt event references are not recovery authority.

Lease and fencing maps contribute only PlanId key presence to occupancy.
Inspection never reads their values, the used-token set, or the Clock; it does
not call a lease port or write any state. A lease release, expiry, renewal, or
takeover therefore cannot change a valid `READY`, `COMMITTED`, or `ADVANCED`
classification.

Returned snapshots expose the permanent token-free persisted start fact, never
the marker request. Their string representations keep all business objects
opaque.

## Linearization and durability

The in-memory adapter classifies inside one `state.monitor` critical section.
A durable adapter must use a consistent transaction or snapshot that observes
markers and projections from the same commit cut. The result proves only its
linearization point and can become stale immediately after return.

The Runtime must inspect again after a later lease action. Future fenced Step
writes must still validate lease generation, expected Plan revision,
checkpoint version, and event head inside their own write transaction.

## Consequences

- Recovery composition receives an atomic read authority without acquiring a
  lease or authorizing a write.
- Strict `READY` and `COMMITTED` remain narrow, while valid later progress is
  distinguished from corruption.
- The port does not provide a general Step recovery snapshot, durable recovery
  intent journal, Runtime recovery composition, or fenced Step transition.
