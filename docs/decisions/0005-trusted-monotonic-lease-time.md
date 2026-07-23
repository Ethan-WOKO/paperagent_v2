# ADR 0005: Trusted Monotonic Lease Time

Status: `ACCEPTED`

Date: 2026-07-24

## Context

A lease decides which worker may perform authority-bearing work. A caller-supplied
observation time can be stale, delayed, or deliberately rolled back. It therefore
cannot decide whether a lease generation is live.

The in-memory persistence adapter is the current reference implementation. It must
model the transaction boundary that a future durable adapter will provide without
introducing a new time service or a distributed-clock protocol.

## Decision

- Lease operations do not accept a caller-supplied `now`.
- The persistence adapter owns a required `Clock`. The default in-memory adapter
  uses `Clock.systemUTC()` and tests may inject a deterministic Clock.
- Every structurally valid lease operation observes the Clock exactly once while
  holding the shared persistence monitor.
- The adapter maintains one global lease-time high-water. Effective time is the
  maximum of the latest Clock observation and that high-water.
- The high-water advances before business validation can reject an operation.
  Consequently, a rejected but structurally valid call can prevent lease revival
  on every Plan in the adapter.
- Structurally invalid calls do not observe time or advance the high-water.
- `LeaseRecord.acquiredAt` is the effective commit time for a new generation.
  Renewals preserve it, and expiry is inclusive: `effectiveNow >= expiresAt`.
- An expired record remains available for takeover and fence increment. Clock
  rollback cannot make it live again.
- Absolute `expiresAt` remains caller-requested policy, not evidence of current
  time. It preserves exact renewal replay semantics.

## Durable Adapter Requirement

A future durable adapter must obtain trusted database/transaction time, or an
equivalent adapter-controlled source, and persist the monotonic high-water in the
same atomic boundary as lease state. Client time is never lease authority.

## Consequences and Residual Risks

- The in-memory high-water is process-local and is not durable across restarts.
- Absolute expiry requests have no maximum TTL policy.
- Renewal has no separate operation identity or marker. Replacing absolute expiry
  with a duration is deferred until replay can distinguish a response-loss retry
  from a new renewal.
- Heartbeat scheduling, automatic renewal, distributed time, and execution-start
  fencing remain outside this decision.
