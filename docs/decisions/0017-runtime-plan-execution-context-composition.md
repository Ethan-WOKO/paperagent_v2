# ADR 0017: Recovery-First Runtime Plan Execution-Context Composition

## Status

Accepted for Wave 3. The public surface and authority rules are frozen by
Issue #68 and its authoritative v1, v2, and v3 clarifications.

## Context

Execution start commits H0, Persistence reserves and confirms one permanent
Plan-to-Workspace binding, and Workspace independently publishes and verifies
the isolated filesystem. These adapters cannot share a transaction. A fresh
call can therefore lose a response after reservation, publication, or
confirmation while leaving a legal state for the next call to recover.

Fresh composition and recovery are the same state machine. Runtime must begin
from stored execution-start and context authority instead of trusting a
caller-selected mode, caller-supplied source flag, historical fence, Workspace
fingerprint, or adapter-internal state.

## Decision

Runtime exposes one recovery-first `PlanExecutionContextComposer`. Its only
collaborators are the public `ExecutionStartRecoveryRepository`,
`PlanExecutionContextRepository`, `LeaseRepository`, and `WorkspacePort`
interfaces. `agent-runtime` therefore adds a production dependency on
`agent-workspace`; Contracts, Persistence, and Workspace production surfaces
remain unchanged.

The bounded protocol is:

```text
inspect execution start
inspect context
acquire current lease only for NONE or RESERVED
re-inspect execution start and context
reserve exact H0/spec only for NONE
inspect Workspace
materialize only for RESERVED plus canonical WORKSPACE_NOT_FOUND
confirm the exact Provider fingerprint
re-inspect context
```

After every LeaseRepository.acquire invocation, including APPLIED, REPLAYED, REJECTED, FOUND, null, thrown, and malformed results, both execution-start and context re-inspections are called exactly once and both observations are captured before either observation or the acquire result is classified. A malformed non-null collaborator result is a protocol failure at its own stage and can never be washed out by a later authoritative state.

Every mutation is called at most once in one invocation and is followed by its
mandatory authoritative observation. There is no loop, sleep, backoff,
`find`, renew, release, cleanup, compensation, adoption, rebind, replacement,
or WorkspaceId reuse.

The request never accepts a fencing token. Only an exact APPLIED or REPLAYED
`LeaseRecord` returned by the current acquire call supplies write authority.
Execution-start and reservation fences remain historical facts.

Only the stored TaskFrame source chooses routing. Source-less committed
execution requires exact context absence and returns `NOT_REQUIRED` without
lease, Workspace, or context mutation calls. A source-backed Plan uses either
the proposed exact spec for NONE or the persisted exact spec for
RESERVED/CONFIRMED.

For RESERVED, a verified Workspace supplies the only fingerprint candidate for
confirmation. For CONFIRMED, Runtime requires exact persisted spec and
fingerprint equality. A missing or inconsistent CONFIRMED Workspace fails
closed and is never re-materialized.

Expected adapter failures are exposed only after exact code, path, operation,
and ProjectPath binding is validated. Malformed results, unexpected outcomes,
wrong authority, and non-canonical failures become sanitized protocol
exceptions. Public text and throwable trees do not expand identifiers,
specifications, fingerprints, lease data, source data, host paths, Project
paths, or collaborator messages.

Private or package-private observations and classifiers are transient Runtime
state. They never become an alternate authority surface.

## Consequences

- Fresh and response-loss paths converge on one confirmed Persistence fact and
  one exact Provider-verified Workspace fact.
- Concurrency converges on one physical Workspace publication and one ACTIVE
  Workspace fact. Multiple exact, replay-safe `materialize` calls may occur
  across concurrent invocations and are permitted by the protocol.
- No Runtime call order can replace Persistence or Workspace authority.
- A current lease may expire immediately after return and is deliberately
  retained for downstream recovery or activation.
- In-memory facts are not durable across process restart or cross-JVM
  composition.
- Workspace inspection proves the registered structure and original verified
  fact, not current-pristine contents.
- Context readiness does not authorize Step activation.
- Step activation, effects, Receipts, progress, completion, Step Recovery, and
  the bounded Agent Loop remain deferred.
