package io.paperagent.v2.runtime.execution.activation.composition;

import io.paperagent.v2.contracts.PlanStepId;
import io.paperagent.v2.persistence.PersistenceErrorCode;
import io.paperagent.v2.persistence.PersistenceOutcome;
import io.paperagent.v2.persistence.StepActivationRequest;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StepActivationCompositionIntegrationTest {
    @Test
    void realPersistenceAppliesThenReplaysExactActivation() {
        StepActivationCompositionTestFixtures.Seeded seeded =
                StepActivationCompositionTestFixtures.seeded("integration-replay", false);
        DefaultStepActivationComposer composer =
                StepActivationCompositionTestFixtures.composer(seeded.persistence());

        StepActivationCommitted applied = assertInstanceOf(
                StepActivationCommitted.class, composer.compose(seeded.request()));
        StepActivationCommitted replayed = assertInstanceOf(
                StepActivationCommitted.class, composer.compose(seeded.request()));
        assertEquals(PersistenceOutcome.APPLIED, applied.activationOutcome());
        assertEquals(PersistenceOutcome.REPLAYED, replayed.activationOutcome());
        assertEquals(applied.persistedActivation(), replayed.persistedActivation());
    }

    @Test
    void staleH0AndSourceBackedMissingContextRemainPersistenceAuthority() {
        StepActivationCompositionTestFixtures.Seeded seeded =
                StepActivationCompositionTestFixtures.seeded("integration-stale", false);
        DefaultStepActivationComposer composer =
                StepActivationCompositionTestFixtures.composer(seeded.persistence());
        assertInstanceOf(StepActivationCommitted.class, composer.compose(seeded.request()));
        StepActivationCompositionOutcome stale = composer.compose(
                new StepActivationCompositionRequest(
                        seeded.committed(),
                        seeded.request().stepId(),
                        new StepActivationAttempt(
                                seeded.request().attempt().leaseOwnerId(),
                                seeded.request().attempt().leaseToken(),
                                seeded.request().attempt().leaseExpiresAt(),
                                StepActivationCompositionTestFixtures.draft("integration-stale-other"),
                                seeded.request().attempt().checkpointCreatedAt())));
        StepActivationPersistenceRejected rejected = assertInstanceOf(
                StepActivationPersistenceRejected.class, stale);
        assertEquals(PersistenceErrorCode.STALE_VERSION, rejected.failure().code());
        assertEquals("request.expectedCheckpointVersion", rejected.failure().path());

        StepActivationCompositionTestFixtures.Seeded source =
                StepActivationCompositionTestFixtures.seeded("integration-source", true);
        StepActivationPersistenceRejected sourceRejected = assertInstanceOf(
                StepActivationPersistenceRejected.class,
                StepActivationCompositionTestFixtures.composer(source.persistence())
                        .compose(source.request()));
        assertEquals(PersistenceErrorCode.STEP_ACTIVATION_NOT_ELIGIBLE,
                sourceRejected.failure().code());
        assertEquals("stepActivation.source", sourceRejected.failure().path());
    }

    @Test
    void concurrentDifferentPlansAreBoundedAndProduceOneResultEach() throws Exception {
        StepActivationCompositionTestFixtures.Seeded first =
                StepActivationCompositionTestFixtures.seeded("integration-concurrent-a", false);
        StepActivationCompositionTestFixtures.Seeded second =
                StepActivationCompositionTestFixtures.seeded("integration-concurrent-b", false);
        var executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            var firstFuture = executor.submit(() -> {
                start.await();
                return StepActivationCompositionTestFixtures.composer(first.persistence())
                        .compose(first.request());
            });
            var secondFuture = executor.submit(() -> {
                start.await();
                return StepActivationCompositionTestFixtures.composer(second.persistence())
                        .compose(second.request());
            });
            start.countDown();
            assertTrue(firstFuture.get(5, TimeUnit.SECONDS)
                    instanceof StepActivationCommitted);
            assertTrue(secondFuture.get(5, TimeUnit.SECONDS)
                    instanceof StepActivationCommitted);
        } finally {
            executor.shutdownNow();
        }
    }
}
