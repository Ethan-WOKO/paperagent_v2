# ADR 0028: bounded Step Agent Loop

## Status

Accepted for Wave 3 Issue #106.

## Decision

`agent-runtime` owns a provider-neutral bounded sequencer over the existing
single-turn Step kernel. A caller supplies one already fenced
`RecoveredActiveStep` and an upper bound from one through sixteen. For each
turn, the loop creates a fresh `SingleTurnStepKernelRequest` containing that
same recovered authority and invokes the kernel once.

A durable intent outcome is retained in order and advances only while below
the bound. No-effect and typed persistence-rejection outcomes stop immediately.
If the final permitted turn produces a durable intent, the loop returns a typed
turn-limit result. Malformed, mismatched, null, or throwing kernel behavior is
fail-closed and sanitizes collaborator detail.

## Consequences

The loop has no Persistence port and makes no Persistence write. It does not
execute an effect, manage a lease, inspect recovery, retry or resume a turn,
write progress/results/receipts, mutate Plan or Step facts, call a Provider,
Workspace, Sandbox, Clock, filesystem, or network. A later slice may add
bounded repair/replan; this loop only sequences the existing single-turn
contract.
