# ADR 0014: Package-Private Execution-Mutation Authority

## Status

Accepted for Wave 3.

## Context

ADR 0012 introduced the permanent execution-start root, current
execution-mutation head, provenance links, and marker-backed Step activation
facts. Their validation initially lived in
`InMemoryStepActivationRepository`, even though execution start needs the H0
derivation and execution-start recovery needs the complete authoritative
source. This made unrelated callers depend on a class named for one specific
mutation.

Copying the validation for later execution-context work would create multiple
interpretations of the same authority. Generalizing it into a public writer,
registry, strategy, or future operation model would instead freeze semantics
that do not yet exist.

## Decision

The in-memory adapter extracts the existing read-only validation closure into
the package-private `InMemoryExecutionMutationAuthority`. It owns only:

- validation of the permanent bootstrap and execution-start roots;
- exact H0 derivation from a committed execution start;
- Plan-global event stream and EventId-index projection validation;
- the ordered, marker-backed execution-mutation chain and current projection;
- checkpoint, Step/fact, and receipt-presence coherence;
- exact Step-activation marker self-consistency; and
- Plan-scoped occupancy used to classify missing versus partial state.

Its `AuthoritativeSource` keeps the existing eight internal components and an
opaque text surface. The class, record, and all methods remain non-public. It
does not appear in the `InMemoryPersistence` facade or any public fact or
recovery snapshot.

The authority is a pure reader. It assumes the caller already holds the
adapter's existing shared monitor or transaction cut. It does not acquire or
release locks, read Clock or lease state, cache a cross-cut snapshot, mutate
state, call repositories, or perform I/O.

`InMemoryStepActivationRepository` retains the complete activation control
flow: exact marker lookup before Clock and source validation, trusted-time and
live-lease checks, revision/checkpoint/event-head CAS, eligibility and target
validation, failure mapping, and the existing six writes in their existing
order. `InMemoryExecutionStartRepository` retains its intentionally different
missing-Plan occupancy classification and only redirects H0 derivation.
Execution-start recovery retains its READY, COMMITTED, ADVANCED, and PARTIAL
classification logic while reading the shared authority.

## Semantic Freeze

This extraction does not add or change a public contract, error, state map,
write set, operation type, or test outcome. Sequence gaps remain legal in the
event stream but every post-start event must have exactly one existing
`STEP_ACTIVATION` marker-backed link. Links remain validated in stored order,
checkpoint versions advance by exactly one, and receipt references remain
presence-only checks.

Corrupt collections are still failed closed through the existing typed
partial-state paths. No collection is copied, sorted, rebuilt, or eagerly
dereferenced before validation.

## Consequences and Residual Risks

- Execution start and recovery no longer depend on the Step-activation
  implementation class.
- Later execution-context work can read one neutral current authority without
  duplicating its validation.
- The resolver still recognizes only the case-sensitive
  `STEP_ACTIVATION` operation type; no future marker type is implied.
- The in-memory adapter remains non-durable across process restart.
- Verified Workspace materialization, Plan execution context, effect intent,
  Step recovery, and the bounded Step Agent Loop remain deferred.
