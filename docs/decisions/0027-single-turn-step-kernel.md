# ADR 0027: single-turn Step kernel

## Status

Accepted for Wave 3 Issue #104.

## Decision

`agent-runtime` owns a provider-neutral, single-turn port/kernel between an
already fenced `RecoveredActiveStep` and the existing fenced durable
`EffectIntentRepository`. The kernel derives its input only from the recovered
TaskFrame, current Plan, current versioned checkpoint, and recovery-selected
active Step. It invokes the injected `StepTurnPort` exactly once.

A no-effect decision performs no Persistence write. An effect-intent decision
may make exactly one `EffectIntentRepository.persist` call, using the recovered
lease token/fence and activation event ID. Only matching `APPLIED` or
`REPLAYED` durable intent facts are successful; valid persistence rejection is
typed and every malformed or exceptional collaborator result fails closed with
sanitized throwable details.

## Consequences

The kernel does not acquire, renew, release, or inspect leases; execute tools
or effects; write progress, results, or receipts; mutate Plan/Step/checkpoint
facts; complete a Step; invoke a Provider, Workspace, Sandbox, Clock, or any
external I/O. A later bounded Step Agent Loop owns turn sequencing, and later
slices own execution and completion behavior.
