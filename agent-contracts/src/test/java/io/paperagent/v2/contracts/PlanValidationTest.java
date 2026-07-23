package io.paperagent.v2.contracts;

import static io.paperagent.v2.contracts.ContractFixtures.STEP_1;
import static io.paperagent.v2.contracts.ContractFixtures.STEP_2;
import static io.paperagent.v2.contracts.ContractFixtures.T0;
import static io.paperagent.v2.contracts.ContractFixtures.TASK_ID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

class PlanValidationTest {
    @Test
    void rejectsDuplicateStepIds() {
        PlanStep duplicate = ContractFixtures.step(STEP_1, Set.of());
        ContractViolationException exception = ContractFixtures.violation(() -> revision(
                1, Optional.empty(), List.of(duplicate, duplicate), Map.of()));
        assertEquals(ViolationCode.DUPLICATE_ID, exception.primaryCode());
    }

    @Test
    void rejectsUnknownDependency() {
        ContractViolationException exception = ContractFixtures.violation(() -> revision(
                1,
                Optional.empty(),
                List.of(ContractFixtures.step(STEP_1, Set.of(new PlanStepId("missing")))),
                Map.of()));
        assertEquals(ViolationCode.UNKNOWN_STEP_DEPENDENCY, exception.primaryCode());
    }

    @Test
    void rejectsSelfDependency() {
        ContractViolationException exception = ContractFixtures.violation(() -> revision(
                1,
                Optional.empty(),
                List.of(ContractFixtures.step(STEP_1, Set.of(STEP_1))),
                Map.of()));
        assertEquals(ViolationCode.SELF_DEPENDENCY, exception.primaryCode());
    }

    @Test
    void rejectsMultiStepCycle() {
        PlanStep first = ContractFixtures.step(STEP_1, Set.of(STEP_2));
        PlanStep second = ContractFixtures.step(STEP_2, Set.of(STEP_1));
        ContractViolationException exception = ContractFixtures.violation(() -> revision(
                1, Optional.empty(), List.of(first, second), Map.of()));
        assertEquals(ViolationCode.STEP_DEPENDENCY_CYCLE, exception.primaryCode());
    }

    @Test
    void rejectsParentMismatch() {
        PlanRevision first = ContractFixtures.revision1();
        PlanRevision second = revision(
                2,
                Optional.of(new PlanRevisionId("wrong-parent")),
                first.steps(),
                Map.of());
        ContractViolationException exception =
                ContractFixtures.violation(() -> ContractFixtures.plan(first, second));
        assertTrue(exception.violations().stream()
                .anyMatch(violation -> violation.code() == ViolationCode.REVISION_PARENT_MISMATCH));
    }

    @Test
    void rejectsNonMonotonicRevisionNumber() {
        PlanRevision first = ContractFixtures.revision1();
        PlanRevision third = revision(
                3,
                Optional.of(first.id()),
                first.steps(),
                Map.of());
        ContractViolationException exception =
                ContractFixtures.violation(() -> ContractFixtures.plan(first, third));
        assertTrue(exception.violations().stream()
                .anyMatch(violation -> violation.code() == ViolationCode.REVISION_NOT_MONOTONIC));
    }

    @Test
    void rejectsTaskFrameMismatch() {
        PlanRevision wrong = new PlanRevision(
                new PlanRevisionId("revision-other"),
                new TaskFrameId("task-other"),
                1,
                Optional.empty(),
                "wrong task",
                T0,
                List.of(ContractFixtures.step(STEP_1, Set.of())),
                Map.of());
        ContractViolationException exception =
                ContractFixtures.violation(() -> new Plan(ContractFixtures.PLAN_ID, TASK_ID, List.of(wrong)));
        assertEquals(ViolationCode.TASK_FRAME_MISMATCH, exception.primaryCode());
    }

    @Test
    void rejectsRemovalOfCompletedStepFact() {
        PlanRevision first = completedFirstRevision();
        PlanRevision second = revision(
                2,
                Optional.of(first.id()),
                List.of(ContractFixtures.step(STEP_2, Set.of())),
                Map.of());
        ContractViolationException exception =
                ContractFixtures.violation(() -> ContractFixtures.plan(first, second));
        assertEquals(ViolationCode.COMPLETED_FACT_REMOVED, exception.primaryCode());
    }

    @Test
    void rejectsRedefinitionOfCompletedStep() {
        PlanRevision first = completedFirstRevision();
        PlanStep rewritten = new PlanStep(
                STEP_1,
                "rewritten intent",
                "rewritten outcome",
                Set.of(),
                List.of("different"),
                new BoundedExecutionHints(1, Duration.ofSeconds(5)));
        PlanRevision second = revision(
                2,
                Optional.of(first.id()),
                List.of(rewritten, ContractFixtures.step(STEP_2, Set.of(STEP_1))),
                first.completedFacts());
        ContractViolationException exception =
                ContractFixtures.violation(() -> ContractFixtures.plan(first, second));
        assertEquals(ViolationCode.COMPLETED_FACT_REDEFINED, exception.primaryCode());
    }

    @Test
    void completedFactRewriteHasStableCode() {
        PlanRevision first = completedFirstRevision();
        CompletionFact changed = new CompletionFact(STEP_1, "contradictory", T0.plusSeconds(1), List.of());
        PlanRevision second = revision(
                2,
                Optional.of(first.id()),
                first.steps(),
                Map.of(STEP_1, changed));
        List<ContractViolation> violations =
                PlanValidators.validateCompletedFactPreservation(first, second);
        assertEquals(ViolationCode.COMPLETED_FACT_REDEFINED, violations.get(0).code());
    }

    @Test
    void publicStepGraphValidatorReturnsStableViolationForNullElement() {
        List<ContractViolation> violations =
                PlanValidators.validateStepGraph(Arrays.asList((PlanStep) null));
        assertEquals(ViolationCode.NULL_COLLECTION_ELEMENT, violations.get(0).code());
    }

    @Test
    void publicCompletedFactValidatorReturnsStableViolationForMissingRevision() {
        assertEquals(
                ViolationCode.REQUIRED_VALUE_MISSING,
                PlanValidators.validateCompletedFactPreservation(null, ContractFixtures.revision1())
                        .get(0).code());
        assertEquals(
                ViolationCode.REQUIRED_VALUE_MISSING,
                PlanValidators.validateCompletedFactPreservation(ContractFixtures.revision1(), null)
                        .get(0).code());
    }

    @Test
    void publicValidatorsDoNotThrowForOtherExpectedNullInputs() {
        assertEquals(
                ViolationCode.REQUIRED_VALUE_MISSING,
                PlanValidators.validateHistory(null, null).get(0).code());
        assertEquals(
                ViolationCode.REQUIRED_VALUE_MISSING,
                CheckpointValidators.validate(null, null, null, null).get(0).code());
        assertEquals(
                ViolationCode.REQUIRED_VALUE_MISSING,
                EventValidators.validateNext(null, null).get(0).code());
    }

    @Test
    void publicHistoryValidatorReturnsStableViolationForNullRevisionElement() {
        assertEquals(
                ViolationCode.NULL_COLLECTION_ELEMENT,
                PlanValidators.validateHistory(
                        TASK_ID, Arrays.asList((PlanRevision) null)).get(0).code());
    }

    private static PlanRevision completedFirstRevision() {
        return revision(
                1,
                Optional.empty(),
                List.of(ContractFixtures.step(STEP_1, Set.of()), ContractFixtures.step(STEP_2, Set.of(STEP_1))),
                Map.of(STEP_1, new CompletionFact(STEP_1, "outcome-v1", T0, List.of())));
    }

    private static PlanRevision revision(
            long number,
            Optional<PlanRevisionId> parent,
            List<PlanStep> steps,
            Map<PlanStepId, CompletionFact> facts) {
        return new PlanRevision(
                new PlanRevisionId("revision-" + number),
                TASK_ID,
                number,
                parent,
                "revision reason",
                T0.plusSeconds(number),
                steps,
                facts);
    }
}
