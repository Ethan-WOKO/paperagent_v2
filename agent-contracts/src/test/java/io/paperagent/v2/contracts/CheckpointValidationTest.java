package io.paperagent.v2.contracts;

import static io.paperagent.v2.contracts.ContractFixtures.PLAN_ID;
import static io.paperagent.v2.contracts.ContractFixtures.STEP_1;
import static io.paperagent.v2.contracts.ContractFixtures.STEP_2;
import static io.paperagent.v2.contracts.ContractFixtures.T0;
import static io.paperagent.v2.contracts.ContractFixtures.TASK_ID;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

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

    private static void assertContains(List<ContractViolation> violations, ViolationCode code) {
        assertTrue(violations.stream().anyMatch(violation -> violation.code() == code),
                () -> "Expected " + code + " in " + violations);
    }
}
