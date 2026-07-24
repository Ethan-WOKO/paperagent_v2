# ADR 0013: Retry-Stable Workspace Materialization Spec

## Status

Accepted for Wave 3.

## Context

Plan execution-context binding will need one stable description of the
Workspace that an execution owns. The source ProjectVersion, WorkspaceId, and
materialization limits must survive response loss and retry without being
recomputed. Keeping the existing limits type only in the Workspace adapter
would either make Persistence depend on an outer module or create duplicate
primitive authority.

Workspace materialization is also a filesystem operation and cannot be made
atomic with a separate Persistence adapter by assertion. A shared pure value
therefore describes the intended materialization without claiming that the
Workspace exists or that a Plan owns it.

## Decision

`agent-contracts` defines:

```text
WorkspaceMaterializationLimits(maxFileBytes, maxAggregateBytes, maxFiles)
WorkspaceMaterializationSpec(workspaceId, sourceProjectVersion, limits)
```

The caller supplies a retry-stable `WorkspaceId`. The immutable
`ProjectVersionRef` remains the source authority, and all three non-negative
limits are part of the exact retry identity. Zero is legal. File and aggregate
byte limits are independent, so the file limit need not be less than the
aggregate limit.

The records have value semantics and expose only typed components. Their text
surfaces reveal only component presence. Validation is deterministic and
side-effect free: missing spec components use `REQUIRED_VALUE_MISSING`, while
each negative limit uses the unique `INVALID_WORKSPACE_LIMIT` violation and
its exact component path.

The spec is intent, not proof. It does not contain Plan, TaskFrame,
ExecutionProfile, lease, fence, effect, secret, host path, Provider handle,
manifest, or fingerprint state. It does not authorize execution or establish
Workspace ownership.

## Downstream Requirements

Verified materialization must compute a fingerprint from the canonical source
manifest and fail closed if content behind a `ProjectVersionRef` drifts. The
fingerprint is a Provider verification result, never caller-authored spec
input.

Later Persistence work must bind one WorkspaceId to at most one Plan and must
preserve a tombstone or equivalent non-reuse authority after cleanup. Cleanup
does not permit WorkspaceId reuse, because doing so would allow an old
WorkspaceRef to observe a different Workspace through ABA.

Cross-adapter orchestration must expose reserve, inspect, exact replay, and
recovery states instead of pretending Workspace I/O and Persistence commit in
one transaction.

## Consequences and Residual Risks

- Contracts, Persistence, Workspace, and Runtime can share one retry identity
  without reversing module dependencies.
- Actual numeric policies and identity values do not enter ordinary logs.
- Existing `agent-workspace.WorkspaceLimits` and `WorkspacePort` remain
  unchanged until the dedicated replay-safe materialization hard cut.
- Verified materialization, source-manifest fingerprinting, WorkspaceId
  tombstones and owner index, Plan binding, Runtime composition, and
  cross-process durability remain deferred.
