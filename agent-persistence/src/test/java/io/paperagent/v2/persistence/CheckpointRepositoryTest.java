package io.paperagent.v2.persistence;

import io.paperagent.v2.contracts.Checkpoint;
import io.paperagent.v2.contracts.PlanExecutionState;
import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.PlanRevisionId;
import io.paperagent.v2.contracts.ReceiptId;
import io.paperagent.v2.contracts.StepExecutionState;
import io.paperagent.v2.contracts.TaskFrameId;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CheckpointRepositoryTest {
    @Test
    void initialSaveAndCasUpdateProduceMonotonicVersions() {
        InMemoryPersistence persistence = PersistenceFixtures.initializedPersistence();
        Checkpoint first = PersistenceFixtures.checkpoint(0, PersistenceFixtures.T0, List.of());
        PersistenceResult<VersionedCheckpoint> created =
                persistence.checkpoints().save(0, first);

        assertEquals(PersistenceOutcome.APPLIED, created.outcome());
        assertEquals(1, created.value().orElseThrow().version());
        assertEquals(PersistenceOutcome.REPLAYED, persistence.checkpoints().save(0, first).outcome());

        Checkpoint second =
                PersistenceFixtures.checkpoint(1, PersistenceFixtures.T0.plusSeconds(1), List.of());
        PersistenceResult<VersionedCheckpoint> updated =
                persistence.checkpoints().save(1, second);
        assertEquals(PersistenceOutcome.APPLIED, updated.outcome());
        assertEquals(2, updated.value().orElseThrow().version());
        assertEquals(second, persistence.checkpoints()
                .find(PersistenceFixtures.PLAN_ID).value().orElseThrow().checkpoint());
    }

    @Test
    void staleCasIsRejectedWithoutChangingStoredCheckpoint() {
        InMemoryPersistence persistence = PersistenceFixtures.initializedPersistence();
        Checkpoint first = PersistenceFixtures.checkpoint(0, PersistenceFixtures.T0, List.of());
        persistence.checkpoints().save(0, first);
        Checkpoint stale =
                PersistenceFixtures.checkpoint(2, PersistenceFixtures.T0.plusSeconds(2), List.of());

        assertFailure(
                persistence.checkpoints().save(0, stale),
                PersistenceErrorCode.STALE_VERSION);
        assertEquals(first, persistence.checkpoints()
                .find(PersistenceFixtures.PLAN_ID).value().orElseThrow().checkpoint());
    }

    @Test
    void checkpointMustReferenceStoredTaskPlanAndExactRevision() {
        InMemoryPersistence persistence = PersistenceFixtures.initializedPersistence();
        Checkpoint unknownPlan = checkpoint(
                PersistenceFixtures.TASK_ID,
                new PlanId("unknown-plan"),
                new PlanRevisionId("revision-1"),
                1,
                0,
                PersistenceFixtures.T0,
                List.of());
        assertFailure(
                persistence.checkpoints().save(0, unknownPlan),
                PersistenceErrorCode.NOT_FOUND);

        Checkpoint unknownRevision = checkpoint(
                PersistenceFixtures.TASK_ID,
                PersistenceFixtures.PLAN_ID,
                new PlanRevisionId("revision-other"),
                1,
                0,
                PersistenceFixtures.T0,
                List.of());
        assertFailure(
                persistence.checkpoints().save(0, unknownRevision),
                PersistenceErrorCode.CHECKPOINT_VALIDATION_FAILED);

        TaskFrameId otherTaskId = new TaskFrameId("task-other");
        persistence.taskFrames().create(
                PersistenceFixtures.taskFrame(otherTaskId, "Another task"));
        Checkpoint wrongTask = checkpoint(
                otherTaskId,
                PersistenceFixtures.PLAN_ID,
                new PlanRevisionId("revision-1"),
                1,
                0,
                PersistenceFixtures.T0,
                List.of());
        assertFailure(
                persistence.checkpoints().save(0, wrongTask),
                PersistenceErrorCode.CHECKPOINT_VALIDATION_FAILED);
    }

    @Test
    void invalidCheckpointHistoryCannotRegressEventsOrRemoveReceipts() {
        InMemoryPersistence persistence = PersistenceFixtures.initializedPersistence();
        ReceiptId receiptId = new ReceiptId("receipt-1");
        Checkpoint first =
                PersistenceFixtures.checkpoint(2, PersistenceFixtures.T0, List.of(receiptId));
        persistence.checkpoints().save(0, first);

        Checkpoint eventRegression =
                PersistenceFixtures.checkpoint(1, PersistenceFixtures.T0.plusSeconds(1), List.of(receiptId));
        assertFailure(
                persistence.checkpoints().save(1, eventRegression),
                PersistenceErrorCode.CHECKPOINT_VALIDATION_FAILED);

        Checkpoint receiptRemoval =
                PersistenceFixtures.checkpoint(3, PersistenceFixtures.T0.plusSeconds(1), List.of());
        assertFailure(
                persistence.checkpoints().save(1, receiptRemoval),
                PersistenceErrorCode.CHECKPOINT_VALIDATION_FAILED);
    }

    @Test
    void exactlyOneConcurrentCasWriterWins() throws Exception {
        InMemoryPersistence persistence = PersistenceFixtures.initializedPersistence();
        persistence.checkpoints().save(
                0, PersistenceFixtures.checkpoint(0, PersistenceFixtures.T0, List.of()));
        Checkpoint candidateA =
                PersistenceFixtures.checkpoint(1, PersistenceFixtures.T0.plusSeconds(1), List.of());
        Checkpoint candidateB =
                PersistenceFixtures.checkpoint(2, PersistenceFixtures.T0.plusSeconds(2), List.of());
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<PersistenceResult<VersionedCheckpoint>> first =
                    executor.submit(() -> saveAfterBarrier(persistence, barrier, candidateA));
            Future<PersistenceResult<VersionedCheckpoint>> second =
                    executor.submit(() -> saveAfterBarrier(persistence, barrier, candidateB));

            List<PersistenceResult<VersionedCheckpoint>> results = List.of(
                    first.get(5, TimeUnit.SECONDS),
                    second.get(5, TimeUnit.SECONDS));
            assertEquals(
                    1,
                    results.stream()
                            .filter(result -> result.outcome() == PersistenceOutcome.APPLIED)
                            .count());
            assertEquals(
                    1,
                    results.stream()
                            .filter(result -> result.failure()
                                    .map(failure -> failure.code() == PersistenceErrorCode.STALE_VERSION)
                                    .orElse(false))
                            .count());
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void nullAndInvalidCasInputsUseStableCodes() {
        InMemoryPersistence persistence = PersistenceFixtures.initializedPersistence();
        assertFailure(
                persistence.checkpoints().save(-1, PersistenceFixtures.checkpoint(
                        0, PersistenceFixtures.T0, List.of())),
                PersistenceErrorCode.INVALID_ARGUMENT);
        assertFailure(
                persistence.checkpoints().save(0, null),
                PersistenceErrorCode.INVALID_ARGUMENT);
        assertFailure(
                persistence.checkpoints().find(null),
                PersistenceErrorCode.INVALID_ARGUMENT);
    }

    private static PersistenceResult<VersionedCheckpoint> saveAfterBarrier(
            InMemoryPersistence persistence,
            CyclicBarrier barrier,
            Checkpoint checkpoint) throws Exception {
        barrier.await(5, TimeUnit.SECONDS);
        return persistence.checkpoints().save(1, checkpoint);
    }

    private static Checkpoint checkpoint(
            TaskFrameId taskFrameId,
            PlanId planId,
            PlanRevisionId revisionId,
            long revisionNumber,
            long eventSequence,
            Instant createdAt,
            List<ReceiptId> receiptIds) {
        return new Checkpoint(
                taskFrameId,
                planId,
                revisionId,
                revisionNumber,
                eventSequence,
                PlanExecutionState.ACTIVE,
                Map.of(
                        PersistenceFixtures.STEP_1, StepExecutionState.ACTIVE,
                        PersistenceFixtures.STEP_2, StepExecutionState.NOT_STARTED),
                receiptIds,
                createdAt);
    }

    private static void assertFailure(
            PersistenceResult<?> result,
            PersistenceErrorCode expectedCode) {
        assertEquals(PersistenceOutcome.REJECTED, result.outcome());
        assertEquals(expectedCode, result.failure().orElseThrow().code());
    }
}
