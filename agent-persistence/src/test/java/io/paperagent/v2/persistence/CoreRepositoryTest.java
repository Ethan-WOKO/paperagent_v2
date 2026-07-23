package io.paperagent.v2.persistence;

import io.paperagent.v2.contracts.EventEnvelope;
import io.paperagent.v2.contracts.EventId;
import io.paperagent.v2.contracts.ExecutionReceipt;
import io.paperagent.v2.contracts.Plan;
import io.paperagent.v2.contracts.PlanRevision;
import io.paperagent.v2.contracts.PlanRevisionId;
import io.paperagent.v2.contracts.TaskFrame;
import io.paperagent.v2.contracts.TaskFrameId;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoreRepositoryTest {
    @Test
    void taskFrameCreateIsCreateOnceAndExactReplayIsIdempotent() {
        InMemoryPersistence persistence = new InMemoryPersistence();
        TaskFrame original = PersistenceFixtures.taskFrame();

        assertEquals(PersistenceOutcome.APPLIED, persistence.taskFrames().create(original).outcome());
        assertEquals(PersistenceOutcome.REPLAYED, persistence.taskFrames().create(original).outcome());

        TaskFrame conflict = PersistenceFixtures.taskFrame(
                PersistenceFixtures.TASK_ID, "A conflicting objective");
        assertFailure(
                persistence.taskFrames().create(conflict),
                PersistenceErrorCode.CONFLICTING_REPLAY);
        assertEquals(original, persistence.taskFrames().find(original.id()).value().orElseThrow());
    }

    @Test
    void planRequiresStoredTaskAndRevisionAppendUsesCurrentVersion() {
        InMemoryPersistence persistence = new InMemoryPersistence();
        Plan plan = PersistenceFixtures.plan();
        assertFailure(persistence.plans().create(plan), PersistenceErrorCode.NOT_FOUND);
        persistence.taskFrames().create(PersistenceFixtures.taskFrame());
        assertEquals(PersistenceOutcome.APPLIED, persistence.plans().create(plan).outcome());
        assertEquals(PersistenceOutcome.REPLAYED, persistence.plans().create(plan).outcome());
        PlanRevision conflictingInitialRevision = new PlanRevision(
                new PlanRevisionId("revision-conflict"),
                PersistenceFixtures.TASK_ID,
                1,
                Optional.empty(),
                "conflicting initial plan",
                PersistenceFixtures.T0,
                PersistenceFixtures.revision1().steps(),
                Map.of());
        assertFailure(
                persistence.plans().create(new Plan(
                        PersistenceFixtures.PLAN_ID,
                        PersistenceFixtures.TASK_ID,
                        List.of(conflictingInitialRevision))),
                PersistenceErrorCode.CONFLICTING_REPLAY);

        PlanRevision revision2 = PersistenceFixtures.revision2("revision-2", "refined plan");
        PersistenceResult<Plan> appended =
                persistence.plans().appendRevision(plan.id(), 1, revision2);
        assertEquals(PersistenceOutcome.APPLIED, appended.outcome());
        assertEquals(2, appended.value().orElseThrow().latestRevision().number());
        assertEquals(
                PersistenceOutcome.REPLAYED,
                persistence.plans().appendRevision(plan.id(), 1, revision2).outcome());

        PlanRevision revision3Shape =
                PersistenceFixtures.revision2("revision-other", "stale attempt");
        assertFailure(
                persistence.plans().appendRevision(plan.id(), 1, revision3Shape),
                PersistenceErrorCode.STALE_VERSION);
    }

    @Test
    void conflictingRevisionIdentityIsRejected() {
        InMemoryPersistence persistence = PersistenceFixtures.initializedPersistence();
        PlanRevision accepted = PersistenceFixtures.revision2("revision-2", "accepted");
        persistence.plans().appendRevision(PersistenceFixtures.PLAN_ID, 1, accepted);

        PlanRevision conflict = PersistenceFixtures.revision2("revision-2", "different");
        assertFailure(
                persistence.plans().appendRevision(PersistenceFixtures.PLAN_ID, 2, conflict),
                PersistenceErrorCode.CONFLICTING_REPLAY);
    }

    @Test
    void invalidRevisionParentAndTaskBindingAreRejected() {
        InMemoryPersistence persistence = PersistenceFixtures.initializedPersistence();
        PlanRevision wrongParent = new PlanRevision(
                new PlanRevisionId("revision-2"),
                PersistenceFixtures.TASK_ID,
                2,
                Optional.of(new PlanRevisionId("not-the-parent")),
                "wrong parent",
                PersistenceFixtures.T0.plusSeconds(10),
                PersistenceFixtures.revision1().steps(),
                Map.of());
        assertFailure(
                persistence.plans().appendRevision(PersistenceFixtures.PLAN_ID, 1, wrongParent),
                PersistenceErrorCode.PLAN_VALIDATION_FAILED);

        TaskFrameId otherTaskId = new TaskFrameId("task-other");
        PlanRevision wrongTask = new PlanRevision(
                new PlanRevisionId("revision-other"),
                otherTaskId,
                2,
                Optional.of(new PlanRevisionId("revision-1")),
                "wrong task",
                PersistenceFixtures.T0.plusSeconds(10),
                PersistenceFixtures.revision1().steps(),
                Map.of());
        assertFailure(
                persistence.plans().appendRevision(PersistenceFixtures.PLAN_ID, 1, wrongTask),
                PersistenceErrorCode.TASK_FRAME_MISMATCH);
    }

    @Test
    void eventsAreAppendOnlyOrderedAndReplaySafe() {
        InMemoryPersistence persistence = PersistenceFixtures.initializedPersistence();
        EventEnvelope first = PersistenceFixtures.event("event-1", 1);
        EventEnvelope third = PersistenceFixtures.event("event-3", 3);

        assertEquals(PersistenceOutcome.APPLIED, persistence.events().append(first).outcome());
        assertEquals(PersistenceOutcome.APPLIED, persistence.events().append(third).outcome());
        assertEquals(PersistenceOutcome.REPLAYED, persistence.events().append(first).outcome());
        List<EventEnvelope> events = persistence.events()
                .read(PersistenceFixtures.PLAN_ID, "correlation-1")
                .value().orElseThrow();
        assertEquals(List.of(first, third), events);
        assertThrows(UnsupportedOperationException.class, () -> events.add(first));

        assertFailure(
                persistence.events().append(PersistenceFixtures.event("event-2", 2)),
                PersistenceErrorCode.EVENT_SEQUENCE_NOT_MONOTONIC);
    }

    @Test
    void eventDuplicateSequenceAndConflictingIdAreRejected() {
        InMemoryPersistence persistence = PersistenceFixtures.initializedPersistence();
        EventEnvelope event = PersistenceFixtures.event("event-1", 1);
        persistence.events().append(event);

        assertFailure(
                persistence.events().append(PersistenceFixtures.event("event-other", 1)),
                PersistenceErrorCode.EVENT_SEQUENCE_NOT_MONOTONIC);
        EventEnvelope conflictingId = PersistenceFixtures.event("event-1", 2);
        assertFailure(
                persistence.events().append(conflictingId),
                PersistenceErrorCode.CONFLICTING_REPLAY);
        assertEquals(event, persistence.events().find(new EventId("event-1")).value().orElseThrow());
    }

    @Test
    void receiptsAreAppendOnlyAndReplaySafe() {
        InMemoryPersistence persistence = new InMemoryPersistence();
        ExecutionReceipt receipt = PersistenceFixtures.receipt("receipt-1", "call-1");

        assertEquals(PersistenceOutcome.APPLIED, persistence.receipts().append(receipt).outcome());
        assertEquals(PersistenceOutcome.REPLAYED, persistence.receipts().append(receipt).outcome());
        assertFailure(
                persistence.receipts().append(
                        PersistenceFixtures.receipt("receipt-1", "call-other")),
                PersistenceErrorCode.CONFLICTING_REPLAY);
        assertEquals(receipt, persistence.receipts().find(receipt.id()).value().orElseThrow());
    }

    @Test
    void returnedContractValuesAreDeeplyImmutable() {
        InMemoryPersistence persistence = PersistenceFixtures.initializedPersistence();
        TaskFrame taskFrame =
                persistence.taskFrames().find(PersistenceFixtures.TASK_ID).value().orElseThrow();
        Plan plan = persistence.plans().find(PersistenceFixtures.PLAN_ID).value().orElseThrow();

        assertThrows(UnsupportedOperationException.class, () -> taskFrame.targets().add("other"));
        assertThrows(
                UnsupportedOperationException.class,
                () -> plan.revisions().add(PersistenceFixtures.revision1()));
    }

    @Test
    void invalidAndMissingInputsUseStableCodes() {
        InMemoryPersistence persistence = new InMemoryPersistence();

        assertFailure(persistence.taskFrames().create(null), PersistenceErrorCode.INVALID_ARGUMENT);
        assertFailure(persistence.taskFrames().find(null), PersistenceErrorCode.INVALID_ARGUMENT);
        assertFailure(
                persistence.plans().find(PersistenceFixtures.PLAN_ID),
                PersistenceErrorCode.NOT_FOUND);
        assertFailure(
                persistence.events().read(PersistenceFixtures.PLAN_ID, " "),
                PersistenceErrorCode.INVALID_ARGUMENT);
        assertFailure(
                persistence.events().read(PersistenceFixtures.PLAN_ID, "bad correlation"),
                PersistenceErrorCode.INVALID_ARGUMENT);
        assertFailure(persistence.receipts().find(null), PersistenceErrorCode.INVALID_ARGUMENT);
    }

    private static void assertFailure(
            PersistenceResult<?> result,
            PersistenceErrorCode expectedCode) {
        assertEquals(PersistenceOutcome.REJECTED, result.outcome());
        assertTrue(result.value().isEmpty());
        assertEquals(expectedCode, result.failure().orElseThrow().code());
    }
}
