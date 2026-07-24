# ADR 0008: Fresh Runtime execution-start composition

## Status

Accepted for Wave 3.

## Context

Fresh execution may begin only from the authoritative `APPLIED` result of the
atomic Plan bootstrap. The Runtime already has four independently frozen
ports: the fresh-execution gate, the deterministic execution-start
materializer, lease persistence, and atomic execution-start persistence.

Composing those ports must preserve each authority boundary. In particular,
the bootstrap result decides whether this is a fresh path, the materializer
only proposes values, the lease repository owns the fencing authority, and
the execution-start repository owns the atomic current-state decision.

## Decision

The Runtime evaluates the bootstrap result before reading the optional
execution-start attempt. `REPLAYED` is handed to Recovery and `REJECTED`
returns the original failure without calling downstream collaborators.

For an eligible `APPLIED` bootstrap, the Runtime:

1. materializes and validates the complete event/checkpoint proposal;
2. acquires a lease exactly once;
3. validates the returned lease authority;
4. builds the atomic-start request only from that returned Plan, token, and
   fencing token;
5. starts execution exactly once and validates the persisted fact.

Null results, unexpected outcomes, collaborator exceptions, and authority
mismatches are protocol failures with an explicit stage and lease
disposition. Typed persistence rejection remains a value and preserves the
original `PersistenceFailure` instance.

The composition never reads current state or a clock, never generates
identity or time values, never calls lease find/renew/release, and never
retries. Once lease acquisition may have committed, the lease is retained for
Recovery. This prevents a losing concurrent caller from releasing a shared
lease still required by a successful caller.

Attempt token output is always redacted. Validation and protocol messages use
only fixed codes and paths and never include collaborator values. Original
collaborator throwables never enter the returned exception chain; a non-null
cause is replaced by a fixed-format surrogate that retains only the original
exception type name and has no nested or suppressed exceptions. Stage
outcomes keep their authoritative accessors but render
Plan, failure, and persisted values as opaque placeholders in `toString()`.

## Consequences

- Fresh start has deterministic, testable ordering and fail-closed authority
  checks.
- Bootstrap replay and all post-acquire uncertainty require a later Recovery
  composition.
- Durability remains limited to the persistence adapter; this ADR does not
  introduce crash-durable orchestration or the Step Agent Loop.
- The `runtime.execution.start` package receives a dedicated exact
  persistence allowlist without widening the parent execution package.
