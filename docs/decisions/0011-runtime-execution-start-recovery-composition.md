# ADR 0011: Runtime Execution-Start Recovery Composition

## Status

Accepted for Wave 3.

## Context

Fresh execution can lose an acquire or atomic-start response, and a bootstrap
replay deliberately hands control to Recovery. Public Plan, checkpoint, event,
and lease reads cannot reconstruct a single authoritative execution-start cut.
The atomic recovery inspector, recovery-ready materializer, lease port, and
atomic start port provide the required independent authorities, but composing
them must remain bounded and fail closed under races.

This capability recovers only the boundary before any Step has executed.
A permanent committed start fact does not prove current Step lease authority,
and an advanced Plan belongs to future Step Recovery.

## Decision

The Runtime exposes one execution-start recoverer. Its caller supplies a
PlanId and an optional retry-stable `FreshExecutionStartAttempt`; the existing
attempt is deliberately reused even though its validation paths retain the
`freshExecutionStartAttempt` prefix. Recovery does not create a second
sensitive carrier.

The maximum call trace is:

1. inspect the atomic execution-start cut;
2. for READY only, materialize and validate a pre-lease candidate, then
   discard it;
3. acquire the requested lease generation once;
4. inspect again unconditionally after the acquire invocation;
5. only for a confirmed lease and a still-READY cut, materialize and validate
   a fresh candidate from that exact second snapshot;
6. invoke atomic start once with the confirmed repository token and fence; and
7. inspect a third time unconditionally after the start invocation.

The first candidate is only deterministic preflight. It can never authorize
the write. The second candidate is always rebuilt, even when both READY
snapshots are equal. The atomic start repository remains the sole commit
authority.

Acquire and start responses are captured before their mandatory follow-up
inspection. A malformed follow-up inspection takes priority. A legal
follow-up state can reconcile typed rejection or response loss, but it cannot
hide a broken adapter outcome such as FOUND or a success value with mismatched
authority. No path calls lease find, renew, or release, and no path retries
automatically.

COMMITTED returns a permanent token-free fact. ADVANCED returns a typed
unsupported outcome for future Step Recovery. PARTIAL and NOT_FOUND remain
typed inspection failures. If start returns null or throws and the final cut
is still READY, Recovery returns a retry-required value while retaining the
lease for later recovery.

## Failure and security boundary

All persistence outcomes are classified explicitly. Typed acquire and start
rejections preserve the exact `PersistenceFailure` instance. Nulls,
unexpected outcomes, authority mismatches, and collaborator exceptions use
stable stage, code, path, and lease-disposition metadata.

No collaborator `RuntimeException` crosses the composition boundary,
including validation exceptions from the deterministic materializer and
publicly constructible contract violations. The protocol exception replaces
the original throwable with a fixed surrogate that retains only its type
name, with no original message, cause, suppressed values, or stack.

Request and outcome strings are opaque. Outcomes do not store attempts,
tokens, lease records, READY snapshots, candidates, or start requests.
`PersistedExecutionStart` and `PersistenceFailure` remain explicit
authoritative business facts, but their containing outcome strings never
expand them. Lease tokens never enter a public fact.

The recoverer reads no clock, environment, random source, file, network,
process, or thread state. Repository snapshots and materialized values are
ephemeral inputs, not cached authority.

## Consequences

- Response loss converges through authoritative reinspection or an explicit
  bounded retry-required value.
- Revision races and competitor winners cannot be advanced by an old snapshot
  or preflight candidate.
- A shared-token loser never releases a lease still needed by the winner.
- Leases can remain until expiry because this composition deliberately does
  not release or renew them.
- The inspector snapshot can become stale immediately after return; every
  authority-bearing write still validates inside its repository transaction.
- Durable recovery intent, fenced Step writes, Step Recovery, Step Agent Loop,
  API, UI, and process-restart durability remain deferred.
