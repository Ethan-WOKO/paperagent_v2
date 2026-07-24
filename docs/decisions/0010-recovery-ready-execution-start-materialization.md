# ADR 0010: Recovery-Ready Execution-Start Materialization

## Status

Accepted for Wave 3.

## Context

The deterministic fresh-start materializer is intentionally bound to the
latest revision in `PersistedPlanBootstrap`. Recovery inspection can return a
strict `READY` snapshot after one or more valid pre-start Plan revisions have
been appended, so the bootstrap Plan can end at revision 1 while the current
Plan ends at revision 2 or later.

Reusing the fresh-start materializer in that case would produce a permanently
stale checkpoint. Replacing the bootstrap Plan with the current Plan would
also falsify the persisted bootstrap provenance.

## Decision

A dedicated recovery-ready materializer accepts:

- a `PersistedExecutionStartReady` snapshot;
- a retry-stable `ExecutionStartEventDraft`; and
- a caller-supplied checkpoint creation time.

It uses the real bootstrap version-1 checkpoint as the previous checkpoint,
while binding the proposed started checkpoint to the latest revision of the
snapshot's current Plan. The proposed event has Plan-global sequence `1`. The
proposed checkpoint has cursor `1`, Plan state `ACTIVE`, every current-latest
Step in `NOT_STARTED`, no receipt references, and the caller-supplied creation
time.

Before candidate construction, the materializer validates the bootstrap
source against the bootstrap TaskFrame and Plan. It then requires, in order:

1. source cursor `0`;
2. source Plan state `NOT_STARTED`;
3. every bootstrap-latest Step state `NOT_STARTED`;
4. no source receipt references; and
5. no completion facts in the current latest revision.

Contract validation failures propagate unchanged. Snapshot-shape failures not
expressed by Contracts use `NON_CANONICAL_READY_SNAPSHOT`.

The materializer does not call the fresh-start materializer, construct a
synthetic bootstrap, read persistence or a clock, generate identity or time,
acquire a lease, or perform I/O.

## Authority Boundary

The result is a snapshot-derived candidate only. It is not a freshness
decision, execution authorization, or committed fact. The snapshot can become
stale immediately after inspection.

`ExecutionStartRepository.start(...)` remains the only authority for current
revision, live checkpoint, EventId uniqueness, fencing, and atomic commit.
Later Runtime Recovery composition owns reinspection, lease acquisition,
start invocation, and response-loss reconciliation.

## Consequences

- Recovery can propose a start for a valid pre-start revised Plan without
  rewriting bootstrap provenance.
- Equal inputs produce equal candidates across instances and concurrent calls.
- The Runtime recovery-materialization package receives an exact persistence
  allowlist containing only `PersistedExecutionStartReady`.
- This decision does not implement Recovery composition, durable recovery
  intent, fenced Step transition, Step recovery, or the Step Agent Loop.
