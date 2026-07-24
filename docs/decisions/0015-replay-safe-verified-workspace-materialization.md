# ADR 0015: Replay-Safe Verified Workspace Materialization

## Status

Accepted for Wave 3.

## Context

ADR 0013 introduced one retry-stable Workspace materialization intent shared
by Contracts, Workspace, Persistence and Runtime. The existing Workspace
adapter still accepted three independent arguments, kept its own limits type,
published directly into the final directory and forgot an identity after
cleanup. It therefore could not distinguish exact response-loss replay from a
conflicting retry, prove which immutable source manifest was copied, inspect a
previous result without source access, or prevent WorkspaceId reuse.

The old check/create cleanup path also had an ownership race. A provider could
observe an absent final path, lose the subsequent create race, and then
best-effort delete a tree created by another provider.

## Decision

`WorkspacePort.materialize` accepts only `WorkspaceMaterializationSpec` and
returns `VerifiedWorkspaceMaterialization`. The result stores the exact spec
and a Provider-computed source-manifest fingerprint. Its `workspace()` method
derives `WorkspaceRef` from the spec; no duplicate Workspace identity or
source authority exists. `inspectMaterialization(spec)` exposes the stored
fact without loading the source or mutating the filesystem.

The old `agent-workspace.WorkspaceLimits` and three-argument materialize
method are deleted without a compatibility overload. All existing Workspace
file operations retain their public shapes and use the limits stored in the
active spec.

## Canonical Source-Manifest Fingerprint

The fingerprint is SHA-256 over this exact versioned encoding:

1. Every variable byte field is an unsigned UTF-8 byte sequence preceded by a
   four-byte, big-endian length.
2. The first field is the domain
   `paperagent.workspace.source-manifest.v1`.
3. The next fields are `ProjectVersionRef.projectId` and `versionId`.
4. Snapshot metadata is encoded as a four-byte entry count followed by each
   length-prefixed key and value. Entries are sorted by unsigned UTF-8 key
   bytes, then value bytes.
5. The file count is a four-byte integer.
6. Files are sorted by unsigned UTF-8 `ProjectPath.value()` bytes.
7. Each file contributes its length-prefixed path, eight-byte big-endian byte
   size, length-prefixed validated lowercase SHA-256 value, and metadata in
   the same canonical map encoding.

The supplied file hash is verified against the bytes before fingerprinting.
List and map iteration order do not affect the result. WorkspaceId and limits
are deliberately excluded because they already belong to exact spec identity.
An empty manifest still includes the domain, source reference, empty snapshot
metadata count and zero file count.

Within one `LocalWorkspaceProvider` lifetime, the first fully validated
fingerprint for a `ProjectVersionRef` is pinned before any content write. The
pin survives copy or publication failure. A later fresh load of that reference
must match it before a pending or final Workspace path is created.

## Provider State and Replay

The reference provider keeps one synchronized instance cut and three local
registration states:

- `ACTIVE` stores the exact spec, verified result, managed roots and baseline.
  Exact materialize or inspect validates only the registered managed-root
  structure and returns the original result. It does not load or overwrite
  source content, and it does not require normal Step edits to match the
  baseline.
- `CLEANUP_PENDING` records an owned deletion that did not complete. All use,
  materialization and inspection fail as partial; exact cleanup may retry.
- `RETIRED` retains the original identity after successful cleanup. Exact
  cleanup is a no-op, while all materialization and inspection attempts fail
  retired. A mismatched cleanup reference remains a reference mismatch.

Providers sharing the same loaded `agent-workspace` authority also share an
authority-lifetime retired tombstone registry keyed by canonical provider root
and WorkspaceId. The tombstone stores the original exact spec and reference,
so a second provider cannot rematerialize an ID retired by the owner.

An active WorkspaceId with any different source or limit component conflicts
before source or filesystem mutation. Unknown final or deterministic pending
occupancy is never adopted, overwritten or deleted. Links and reparse entries
fail as link escape; other occupancy and registered missing/corrupt roots fail
as partial state.

Local registration state and the source pin last only for one provider
instance. The shared tombstone lasts for the lifetime of the defining
class-loading authority. Both are intentionally in-memory and claim neither
cross-loader nor cross-JVM/process-restart durability.

## Owned Pending, Verification and Publication

Fresh materialization acquires the deterministic `pending-<id-hash>` directory
with `CREATE_NEW` semantics. Before doing so, it acquires a process-local
transient claim, shared within one defining class-loading domain and keyed by
canonical provider root and WorkspaceId. The initial unknown occupancy and
claim check still precedes source loading. The claim is acquired only after
complete source validation and fingerprint pinning, followed by a second
fail-closed final/pending/link check.

The claim is held across pending creation, copy, verification, publication,
active registration and attempt-owned failure cleanup. An attempt records
pending ownership only after directory creation succeeds, clears that
ownership immediately after successful publication, and releases only its own
claim token as the final action. Failure cleanup uses the configured observable
tree deleter and may therefore delete only the pending tree created by that
attempt. A deletion failure never replaces the authoritative materialization
failure. If the owned pending tree cannot be proven absent, its exact
WorkspaceId, spec, reference and pending-root state are registered as
`CLEANUP_PENDING`, and the attempt's claim token is transferred to that
registration rather than released. It never deletes or registers a final,
unknown, pre-existing or competing path.

Files are copied only into pending. Each write is immediately checked with
NOFOLLOW semantics for exact size and hash. Before publication, a stateless
verifier checks the exact file and parent-directory set, file count, sizes and
hashes, an empty staging directory, and the absence of links/reparse entries.
Truncation, mutation, omission, extra content or writer link replacement
cannot register an active Workspace.

Publication is one same-volume `ATOMIC_MOVE` of the pending directory to the
final `ws-<id-hash>` path, without a non-atomic fallback. Java's
`ATOMIC_MOVE` option alone does not specify whether an existing target is
replaced. The no-replace invariant instead comes from the transient claim,
the private-root requirement, and a final target/link recheck while the claim
is held: every conforming `LocalWorkspaceProvider` that shares the same loaded
`agent-workspace` authority is excluded from creating or moving the same
WorkspaceId target until registration and cleanup complete. Final publication
occurs before active registration. A crash in that interval leaves an orphan
final which a later provider reports as partial rather than adopting.

## Cleanup and Security Boundary

Cleanup operates only on a registered owned final or failed-materialization
pending tree. Active final cleanup rejects links before deletion and enters
`CLEANUP_PENDING` before the deleting call. Both paths hold the shared
provider-root/WorkspaceId claim before any deletion. A failed deletion retains
that exact token across cleanup calls, so another provider cannot exploit an
already-absent container as a fresh identity. Failed-pending retry removes
symbolic-link and reparse entries without following them, then retries the
configured tree deleter. Both paths retire the identity only after their exact
container is proven absent. Successful retirement first installs or validates
the shared authority-lifetime tombstone, then records local `RETIRED`, and only
then releases the claim. Partial deletion is retryable and never authorizes
WorkspaceId reuse, preventing stale-reference ABA. A mismatched reference is
rejected before either cleanup path.

The provider root is an operationally private root. JDK NOFOLLOW, portable
path, collision and reparse checks do not defend against an untrusted process
with arbitrary root write or hard-link privileges. One provider root may be
used only by `LocalWorkspaceProvider` instances that share the same defining
class-loading domain and therefore the same loaded `agent-workspace`
authority. Isolated or child-first ClassLoaders and separate ModuleLayers must
use different provider roots; sharing one would require stopping and adding a
future coordinator rather than claiming safety. The transient claim does not
coordinate a second process/JVM or an external actor. Cross-process,
cross-authority publication and hostile-root races remain outside this
reference adapter's frozen threat model. The current
`ProjectVersionSource` may allocate a complete snapshot before Workspace
limits are applied.

## Consequences and Deferred Work

- Exact response-loss replay and read-only inspection no longer depend on
  source availability.
- Source drift is detected independently of Workspace identity and limits.
- Persistence still owns durable Plan-to-Workspace uniqueness, durable
  non-reuse and cross-adapter reserve/confirm/recovery.
- Runtime composition, effect facts, Step recovery, the bounded Agent Loop,
  product API and UI remain deferred.
