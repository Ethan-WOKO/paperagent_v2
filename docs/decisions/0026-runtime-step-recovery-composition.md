# ADR 0026: Runtime Step Recovery composition

## Status

Accepted.

## Context

Persistence owns the authoritative inspection of an eligible active Step. Runtime
needs a narrow, fenced observation handoff before a later Step kernel can decide
whether and how to act. That handoff must not turn an inspected snapshot into
execution or mutation authority.

## Decision

`DefaultStepRecoverer` composes exactly two `StepRecoveryRepository.inspect`
observations around one `LeaseRepository.acquire` request supplied by the caller.

1. It first accepts only a matching `PersistedStepRecoveryActive`, or returns a
   typed not-found, partial-state, or not-eligible rejection before acquiring a
   lease.
2. It accepts only an applied or replayed lease whose plan, owner, token, and
   expiry exactly match the request. A rejected acquisition is typed; malformed
   collaborator results fail closed.
3. It re-inspects after that accepted lease and returns only that post-lease
   matching snapshot together with the matching lease. A typed post-lease
   rejection retains the lease disposition for recovery without returning an
   active-Step handoff.

The public outcome is deliberately observation-only. It has no activation,
completion, interruption, Plan, event, checkpoint, receipt, effect, workspace,
provider, sandbox, clock, or adapter behavior. The acquisition is the sole
intended write; any later Step write must pass its own persistence fence.

## Consequences

The Runtime cut depends only on `StepRecoveryRepository` and `LeaseRepository`.
It does not know an in-memory adapter or lease lifecycle beyond acquire. The
single-turn Step kernel remains responsible for defining execution permission and
revalidating its own write authority.
