package io.paperagent.v2.contracts;

import static io.paperagent.v2.contracts.ContractFixtures.PLAN_ID;
import static io.paperagent.v2.contracts.ContractFixtures.STEP_1;
import static io.paperagent.v2.contracts.ContractFixtures.STEP_2;
import static io.paperagent.v2.contracts.ContractFixtures.T0;
import static io.paperagent.v2.contracts.ContractFixtures.TASK_ID;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

class CheckpointValidationTest {
    @Test
    void rejectsUnknownStep() {
        PlanRevision revision = ContractFixtures.revision1();
        Plan plan = ContractFixtures.plan(revision);
        Checkpoint checkpoint = checkpoint(
                PLAN_ID,
                revision,
                5,
                PlanExecutionState.ACTIVE,
                Map.of(new PlanStepId("unknown"), StepExecutionState.ACTIVE));

        assertContains(
                CheckpointValidators.validate(checkpoint, ContractFixtures.taskFrame(), plan, null),
                ViolationCode.CHECKPOINT_UNKNOWN_STEP);
    }

    @Test
    void rejectsWrongPlanAndRevision() {
        PlanRevision revision = ContractFixtures.revision1();
        Plan plan = ContractFixtures.plan(revision);
        Checkpoint checkpoint = new Checkpoint(
                TASK_ID,
                new PlanId("wrong-plan"),
                new PlanRevisionId("unknown-revision"),
                9,
                5,
                PlanExecutionState.ACTIVE,
                Map.of(),
                List.of(),
                T0);

        List<ContractViolation> violations =
                CheckpointValidators.validate(checkpoint, ContractFixtures.taskFrame(), plan, null);
        assertContains(violations, ViolationCode.CHECKPOINT_PLAN_MISMATCH);
        assertContains(violations, ViolationCode.CHECKPOINT_REVISION_MISMATCH);
    }

    @Test
    void rejectsRegressedEventSequence() {
        PlanRevision revision = ContractFixtures.revision1();
        Plan plan = ContractFixtures.plan(revision);
        Checkpoint previous = ContractFixtures.checkpoint(revision, 10);
        Checkpoint current = ContractFixtures.checkpoint(revision, 9);

        assertContains(
                CheckpointValidators.validate(current, ContractFixtures.taskFrame(), plan, previous),
                ViolationCode.EVENT_SEQUENCE_REGRESSION);
    }

    @Test
    void rejectsInconsistentTerminalState() {
        PlanRevision revision = ContractFixtures.revision1();
        Plan plan = ContractFixtures.plan(revision);
        Checkpoint checkpoint = checkpoint(
                PLAN_ID,
                revision,
                5,
                PlanExecutionState.SUCCEEDED,
                Map.of(STEP_1, StepExecutionState.SUCCEEDED, STEP_2, StepExecutionState.FAILED));

        assertContains(
                CheckpointValidators.validate(checkpoint, ContractFixtures.taskFrame(), plan, null),
                ViolationCode.CHECKPOINT_STATE_INCONSISTENT);
    }

    @Test
    void rejectsSucceededStepWithoutCompletionFact() {
        PlanRevision revision = ContractFixtures.revision1();
        Plan plan = ContractFixtures.plan(revision);
        Checkpoint checkpoint = checkpoint(
                PLAN_ID,
                revision,
                5,
                PlanExecutionState.SUCCEEDED,
                succeededStates());

        assertContains(
                CheckpointValidators.validate(checkpoint, ContractFixtures.taskFrame(), plan, null),
                ViolationCode.CHECKPOINT_COMPLETION_FACT_MISSING);
    }

    @Test
    void acceptsSucceededPlanOnlyWhenEveryStepHasCompletionFact() {
        PlanRevision revision = completedRevision();
        Plan plan = ContractFixtures.plan(revision);
        Checkpoint checkpoint = checkpoint(
                PLAN_ID,
                revision,
                5,
                PlanExecutionState.SUCCEEDED,
                succeededStates());

        assertDoesNotThrow(() -> CheckpointValidators.requireValid(
                checkpoint, ContractFixtures.taskFrame(), plan, null));
    }

    @Test
    void rejectsTerminalPlanReopening() {
        PlanRevision revision = completedRevision();
        Plan plan = ContractFixtures.plan(revision);
        Checkpoint previous = checkpointAt(
                revision, 5, T0.plusSeconds(5), PlanExecutionState.SUCCEEDED, succeededStates(), List.of());
        Checkpoint current = checkpointAt(
                revision, 6, T0.plusSeconds(6), PlanExecutionState.ACTIVE, succeededStates(), List.of());

        assertContains(
                CheckpointValidators.validate(current, ContractFixtures.taskFrame(), plan, previous),
                ViolationCode.CHECKPOINT_STATE_REGRESSION);
    }

    @Test
    void rejectsTerminalStepReopeningOrChangingTerminalKind() {
        PlanRevision revision = ContractFixtures.revision1();
        Plan plan = ContractFixtures.plan(revision);
        Checkpoint previous = checkpointAt(
                revision,
                5,
                T0.plusSeconds(5),
                PlanExecutionState.ACTIVE,
                Map.of(STEP_1, StepExecutionState.FAILED, STEP_2, StepExecutionState.NOT_STARTED),
                List.of());
        Checkpoint reopened = checkpointAt(
                revision,
                6,
                T0.plusSeconds(6),
                PlanExecutionState.ACTIVE,
                Map.of(STEP_1, StepExecutionState.ACTIVE, STEP_2, StepExecutionState.NOT_STARTED),
                List.of());
        Checkpoint changedTerminal = checkpointAt(
                revision,
                6,
                T0.plusSeconds(6),
                PlanExecutionState.ACTIVE,
                Map.of(STEP_1, StepExecutionState.CANCELLED, STEP_2, StepExecutionState.NOT_STARTED),
                List.of());

        assertContains(
                CheckpointValidators.validate(reopened, ContractFixtures.taskFrame(), plan, previous),
                ViolationCode.CHECKPOINT_STATE_REGRESSION);
        assertContains(
                CheckpointValidators.validate(changedTerminal, ContractFixtures.taskFrame(), plan, previous),
                ViolationCode.CHECKPOINT_STATE_REGRESSION);
    }

    @Test
    void rejectsSucceededStepReopening() {
        PlanRevision revision = completedRevision();
        Plan plan = ContractFixtures.plan(revision);
        Checkpoint previous = checkpointAt(
                revision, 5, T0.plusSeconds(5), PlanExecutionState.ACTIVE,
                succeededStates(), List.of());
        Checkpoint current = checkpointAt(
                revision, 6, T0.plusSeconds(6), PlanExecutionState.ACTIVE,
                Map.of(STEP_1, StepExecutionState.ACTIVE, STEP_2, StepExecutionState.SUCCEEDED),
                List.of());

        assertContains(
                CheckpointValidators.validate(current, ContractFixtures.taskFrame(), plan, previous),
                ViolationCode.CHECKPOINT_STATE_REGRESSION);
    }

    @Test
    void rejectsRevisionRegression() {
        PlanRevision first = ContractFixtures.revision1();
        PlanRevision second = new PlanRevision(
                new PlanRevisionId("revision-2"),
                TASK_ID,
                2,
                Optional.of(first.id()),
                "continue",
                T0.plusSeconds(2),
                first.steps(),
                Map.of());
        Plan plan = ContractFixtures.plan(first, second);
        Checkpoint previous = checkpointAt(
                second, 5, T0.plusSeconds(5), PlanExecutionState.ACTIVE, notStartedStates(), List.of());
        Checkpoint current = checkpointAt(
                first, 6, T0.plusSeconds(6), PlanExecutionState.ACTIVE, notStartedStates(), List.of());

        assertContains(
                CheckpointValidators.validate(current, ContractFixtures.taskFrame(), plan, previous),
                ViolationCode.CHECKPOINT_REVISION_REGRESSION);
    }

    @Test
    void rejectsCheckpointCreationTimeRegression() {
        PlanRevision revision = ContractFixtures.revision1();
        Plan plan = ContractFixtures.plan(revision);
        Checkpoint previous = checkpointAt(
                revision, 5, T0.plusSeconds(10), PlanExecutionState.ACTIVE, notStartedStates(), List.of());
        Checkpoint current = checkpointAt(
                revision, 6, T0.plusSeconds(9), PlanExecutionState.ACTIVE, notStartedStates(), List.of());

        assertContains(
                CheckpointValidators.validate(current, ContractFixtures.taskFrame(), plan, previous),
                ViolationCode.CHECKPOINT_TIME_REGRESSION);
    }

    @Test
    void rejectsRevisionIdentityChangeAtSameNumber() {
        PlanRevision revision = ContractFixtures.revision1();
        Plan plan = ContractFixtures.plan(revision);
        Checkpoint previous = new Checkpoint(
                TASK_ID,
                PLAN_ID,
                new PlanRevisionId("revision-other"),
                revision.number(),
                5,
                PlanExecutionState.ACTIVE,
                notStartedStates(),
                List.of(),
                T0.plusSeconds(5));
        Checkpoint current = checkpointAt(
                revision, 6, T0.plusSeconds(6), PlanExecutionState.ACTIVE, notStartedStates(), List.of());

        assertContains(
                CheckpointValidators.validate(current, ContractFixtures.taskFrame(), plan, previous),
                ViolationCode.CHECKPOINT_REVISION_REGRESSION);
    }

    @Test
    void acceptsSameTerminalStateWithLaterEventAndAdditionalReceipt() {
        PlanRevision revision = completedRevision();
        Plan plan = ContractFixtures.plan(revision);
        Checkpoint previous = checkpointAt(
                revision, 5, T0.plusSeconds(5), PlanExecutionState.SUCCEEDED,
                succeededStates(), List.of(new ReceiptId("receipt-1")));
        Checkpoint current = checkpointAt(
                revision, 6, T0.plusSeconds(6), PlanExecutionState.SUCCEEDED,
                succeededStates(), List.of(new ReceiptId("receipt-1"), new ReceiptId("receipt-2")));

        assertDoesNotThrow(() -> CheckpointValidators.requireValid(
                current, ContractFixtures.taskFrame(), plan, previous));
    }

    private static Checkpoint checkpoint(
            PlanId planId,
            PlanRevision revision,
            long sequence,
            PlanExecutionState state,
            Map<PlanStepId, StepExecutionState> stepStates) {
        return new Checkpoint(
                TASK_ID,
                planId,
                revision.id(),
                revision.number(),
                sequence,
                state,
                stepStates,
                List.of(),
                T0.plusSeconds(sequence));
    }

    private static Checkpoint checkpointAt(
            PlanRevision revision,
            long sequence,
            java.time.Instant createdAt,
            PlanExecutionState state,
            Map<PlanStepId, StepExecutionState> stepStates,
            List<ReceiptId> receiptReferences) {
        return new Checkpoint(
                TASK_ID,
                PLAN_ID,
                revision.id(),
                revision.number(),
                sequence,
                state,
                stepStates,
                receiptReferences,
                createdAt);
    }

    private static PlanRevision completedRevision() {
        CompletionFact first = new CompletionFact(
                STEP_1, "outcome-1", T0.plusSeconds(1), List.of(new ReceiptId("receipt-1")));
        CompletionFact second = new CompletionFact(
                STEP_2, "outcome-2", T0.plusSeconds(2), List.of(new ReceiptId("receipt-2")));
        return new PlanRevision(
                new PlanRevisionId("revision-complete"),
                TASK_ID,
                1,
                Optional.empty(),
                "completed plan",
                T0,
                ContractFixtures.revision1().steps(),
                Map.of(STEP_1, first, STEP_2, second));
    }

    private static Map<PlanStepId, StepExecutionState> succeededStates() {
        return Map.of(STEP_1, StepExecutionState.SUCCEEDED, STEP_2, StepExecutionState.SUCCEEDED);
    }

    private static Map<PlanStepId, StepExecutionState> notStartedStates() {
        return Map.of(STEP_1, StepExecutionState.NOT_STARTED, STEP_2, StepExecutionState.NOT_STARTED);
    }

    private static void assertContains(List<ContractViolation> violations, ViolationCode code) {
        assertTrue(violations.stream().anyMatch(violation -> violation.code() == code),
                () -> "Expected " + code + " in " + violations);
    }
}
