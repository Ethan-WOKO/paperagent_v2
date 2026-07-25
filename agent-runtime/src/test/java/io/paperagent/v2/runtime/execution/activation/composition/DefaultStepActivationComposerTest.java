package io.paperagent.v2.runtime.execution.activation.composition;

import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.persistence.LeaseRecord;
import io.paperagent.v2.persistence.LeaseRepository;
import io.paperagent.v2.persistence.PersistenceErrorCode;
import io.paperagent.v2.persistence.PersistenceResult;
import io.paperagent.v2.persistence.PersistedStepActivation;
import io.paperagent.v2.persistence.StepActivationRepository;
import io.paperagent.v2.persistence.StepActivationRequest;
import io.paperagent.v2.runtime.execution.activation.materialization.CommittedStepActivationMaterializer;
import io.paperagent.v2.runtime.execution.activation.materialization.DeterministicCommittedStepActivationMaterializer;
import io.paperagent.v2.runtime.execution.activation.materialization.MaterializedStepActivation;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DefaultStepActivationComposerTest {
    @Test
    void materializesAndAcquiresAndActivatesAtMostOnce() {
        StepActivationCompositionTestFixtures.Seeded seeded =
                StepActivationCompositionTestFixtures.seeded("unit-applied", false);
        AtomicInteger materializeCalls = new AtomicInteger();
        AtomicInteger acquireCalls = new AtomicInteger();
        AtomicInteger activateCalls = new AtomicInteger();
        LeaseRepository leases = countingLeases(seeded.persistence().leases(), acquireCalls);
        StepActivationRepository activations = countingActivations(
                seeded.persistence().stepActivations(), activateCalls);
        CommittedStepActivationMaterializer materializer = request -> {
            materializeCalls.incrementAndGet();
            return new DeterministicCommittedStepActivationMaterializer().materialize(request);
        };

        StepActivationCompositionOutcome outcome =
                new DefaultStepActivationComposer(materializer, leases, activations)
                        .compose(seeded.request());

        StepActivationCommitted committed = assertInstanceOf(
                StepActivationCommitted.class, outcome);
        assertEquals(io.paperagent.v2.persistence.PersistenceOutcome.APPLIED,
                committed.activationOutcome());
        assertEquals(1, materializeCalls.get());
        assertEquals(1, acquireCalls.get());
        assertEquals(1, activateCalls.get());
        assertEquals(StepActivationLeaseDisposition.RETAINED_FOR_RECOVERY,
                committed.leaseDisposition());
    }

    @Test
    void exactRetryIsReplayedWithOneCallPerCollaborator() {
        StepActivationCompositionTestFixtures.Seeded seeded =
                StepActivationCompositionTestFixtures.seeded("unit-replay", false);
        DefaultStepActivationComposer composer =
                StepActivationCompositionTestFixtures.composer(seeded.persistence());

        StepActivationCommitted first = assertInstanceOf(
                StepActivationCommitted.class, composer.compose(seeded.request()));
        StepActivationCommitted second = assertInstanceOf(
                StepActivationCommitted.class, composer.compose(seeded.request()));

        assertEquals(io.paperagent.v2.persistence.PersistenceOutcome.APPLIED,
                first.activationOutcome());
        assertEquals(io.paperagent.v2.persistence.PersistenceOutcome.REPLAYED,
                second.activationOutcome());
        assertEquals(first.persistedActivation(), second.persistedActivation());
    }

    @Test
    void nullCandidateIsProtocolFailureBeforeLease() {
        StepActivationCompositionTestFixtures.Seeded seeded =
                StepActivationCompositionTestFixtures.seeded("unit-null", false);
        AtomicInteger acquireCalls = new AtomicInteger();
        LeaseRepository leases = countingLeases(seeded.persistence().leases(), acquireCalls);
        StepActivationCompositionProtocolException failure = assertThrows(
                StepActivationCompositionProtocolException.class,
                () -> new DefaultStepActivationComposer(
                        request -> null, leases, seeded.persistence().stepActivations())
                        .compose(seeded.request()));
        assertEquals(StepActivationCompositionProtocolCode.NULL_COLLABORATOR_RESULT,
                failure.code());
        assertEquals(StepActivationCompositionStage.MATERIALIZE, failure.stage());
        assertEquals(0, acquireCalls.get());
    }

    @Test
    void rejectedLeaseIsReturnedWithoutActivation() {
        StepActivationCompositionTestFixtures.Seeded seeded =
                StepActivationCompositionTestFixtures.seeded("unit-lease-rejected", false);
        PersistenceResult<LeaseRecord> rejection = PersistenceResult.rejected(
                PersistenceErrorCode.LEASE_HELD, "planId");
        AtomicInteger activationCalls = new AtomicInteger();
        StepActivationCompositionOutcome outcome = new DefaultStepActivationComposer(
                new DeterministicCommittedStepActivationMaterializer(),
                fixedLease(rejection),
                countingActivations(seeded.persistence().stepActivations(), activationCalls))
                .compose(seeded.request());
        StepActivationLeaseRejected rejected = assertInstanceOf(
                StepActivationLeaseRejected.class, outcome);
        assertEquals(PersistenceErrorCode.LEASE_HELD, rejected.failure().code());
        assertEquals(StepActivationLeaseDisposition.NOT_ACQUIRED,
                rejected.leaseDisposition());
        assertEquals(0, activationCalls.get());
    }

    @Test
    void collaboratorExceptionIsSanitized() {
        StepActivationCompositionTestFixtures.Seeded seeded =
                StepActivationCompositionTestFixtures.seeded("unit-throw", false);
        StepActivationCompositionProtocolException failure = assertThrows(
                StepActivationCompositionProtocolException.class,
                () -> new DefaultStepActivationComposer(
                        new DeterministicCommittedStepActivationMaterializer(),
                        throwingLease(),
                        seeded.persistence().stepActivations())
                        .compose(seeded.request()));
        assertEquals(StepActivationCompositionProtocolCode.COLLABORATOR_EXCEPTION,
                failure.code());
        assertTrue(failure.getCause().getMessage().contains(IllegalStateException.class.getName()));
        assertFalse(failure.getCause().getMessage().contains("secret collaborator details"));
        assertEquals(StepActivationLeaseDisposition.ACQUISITION_INDETERMINATE,
                failure.leaseDisposition());
    }

    private static LeaseRepository fixedLease(PersistenceResult<LeaseRecord> result) {
        return new LeaseRepository() {
            public PersistenceResult<LeaseRecord> acquire(PlanId planId, String owner, String token, Instant expiry) {
                return result;
            }
            public PersistenceResult<LeaseRecord> renew(PlanId p, String t, Instant e) { throw new AssertionError(); }
            public PersistenceResult<LeaseRecord> release(PlanId p, String t) { throw new AssertionError(); }
            public PersistenceResult<LeaseRecord> find(PlanId p) { throw new AssertionError(); }
        };
    }

    private static LeaseRepository throwingLease() {
        return new LeaseRepository() {
            public PersistenceResult<LeaseRecord> acquire(PlanId p, String o, String t, Instant e) {
                throw new IllegalStateException("secret collaborator details");
            }
            public PersistenceResult<LeaseRecord> renew(PlanId p, String t, Instant e) { throw new AssertionError(); }
            public PersistenceResult<LeaseRecord> release(PlanId p, String t) { throw new AssertionError(); }
            public PersistenceResult<LeaseRecord> find(PlanId p) { throw new AssertionError(); }
        };
    }

    private static LeaseRepository countingLeases(
            LeaseRepository delegate,
            AtomicInteger calls) {
        return new LeaseRepository() {
            public PersistenceResult<LeaseRecord> acquire(PlanId p, String o, String t, Instant e) {
                calls.incrementAndGet(); return delegate.acquire(p, o, t, e);
            }
            public PersistenceResult<LeaseRecord> renew(PlanId p, String t, Instant e) { return delegate.renew(p, t, e); }
            public PersistenceResult<LeaseRecord> release(PlanId p, String t) { return delegate.release(p, t); }
            public PersistenceResult<LeaseRecord> find(PlanId p) { return delegate.find(p); }
        };
    }

    private static StepActivationRepository countingActivations(
            StepActivationRepository delegate,
            AtomicInteger calls) {
        return request -> { calls.incrementAndGet(); return delegate.activate(request); };
    }
}
