package io.paperagent.v2.persistence;

import io.paperagent.v2.contracts.BoundedExecutionHints;
import io.paperagent.v2.contracts.Capability;
import io.paperagent.v2.contracts.Checkpoint;
import io.paperagent.v2.contracts.EventEnvelope;
import io.paperagent.v2.contracts.EventId;
import io.paperagent.v2.contracts.EventType;
import io.paperagent.v2.contracts.ExecutionProfile;
import io.paperagent.v2.contracts.ExecutionReceipt;
import io.paperagent.v2.contracts.ExecutionTier;
import io.paperagent.v2.contracts.InlineEventPayload;
import io.paperagent.v2.contracts.NetworkPolicy;
import io.paperagent.v2.contracts.ObjectValue;
import io.paperagent.v2.contracts.OutputCapture;
import io.paperagent.v2.contracts.Plan;
import io.paperagent.v2.contracts.PlanExecutionState;
import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.PlanRevision;
import io.paperagent.v2.contracts.PlanRevisionId;
import io.paperagent.v2.contracts.PlanStep;
import io.paperagent.v2.contracts.PlanStepId;
import io.paperagent.v2.contracts.ProjectVersionRef;
import io.paperagent.v2.contracts.ReceiptId;
import io.paperagent.v2.contracts.ReceiptStatus;
import io.paperagent.v2.contracts.ResourceLimits;
import io.paperagent.v2.contracts.StepExecutionState;
import io.paperagent.v2.contracts.TaskFrame;
import io.paperagent.v2.contracts.TaskFrameId;
import io.paperagent.v2.contracts.TextValue;
import io.paperagent.v2.contracts.ToolCallId;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

final class PersistenceFixtures {
    static final Instant T0 = Instant.parse("2026-07-24T00:00:00Z");
    static final TaskFrameId TASK_ID = new TaskFrameId("task-1");
    static final PlanId PLAN_ID = new PlanId("plan-1");
    static final PlanStepId STEP_1 = new PlanStepId("step-1");
    static final PlanStepId STEP_2 = new PlanStepId("step-2");

    private PersistenceFixtures() {
    }

    static TaskFrame taskFrame() {
        return taskFrame(TASK_ID, "Prepare a verified paper update");
    }

    static TaskFrame taskFrame(TaskFrameId id, String objective) {
        return new TaskFrame(
                id,
                objective,
                List.of("paper"),
                List.of("workspace diff"),
                List.of("preserve citations"),
                Optional.of(new ProjectVersionRef("project-1", "version-1")),
                new ExecutionProfile(
                        ExecutionTier.SANDBOX_STANDARD,
                        Set.of(Capability.READ_PROJECT, Capability.WRITE_WORKSPACE),
                        NetworkPolicy.DENY_ALL,
                        List.of(),
                        new ResourceLimits(
                                Duration.ofMinutes(10),
                                Duration.ofMinutes(5),
                                512 * 1024 * 1024L,
                                1024 * 1024L,
                                8),
                        Set.of()),
                T0);
    }

    static PlanStep step(PlanStepId id, Set<PlanStepId> dependencies) {
        return new PlanStep(
                id,
                "Perform " + id.value(),
                "Verify " + id.value(),
                dependencies,
                List.of("result is verified"),
                new BoundedExecutionHints(3, Duration.ofMinutes(2)));
    }

    static PlanRevision revision1() {
        return new PlanRevision(
                new PlanRevisionId("revision-1"),
                TASK_ID,
                1,
                Optional.empty(),
                "initial plan",
                T0,
                List.of(step(STEP_1, Set.of()), step(STEP_2, Set.of(STEP_1))),
                Map.of());
    }

    static PlanRevision revision2(String id, String reason) {
        return new PlanRevision(
                new PlanRevisionId(id),
                TASK_ID,
                2,
                Optional.of(new PlanRevisionId("revision-1")),
                reason,
                T0.plusSeconds(10),
                List.of(step(STEP_1, Set.of()), step(STEP_2, Set.of(STEP_1))),
                Map.of());
    }

    static Plan plan() {
        return new Plan(PLAN_ID, TASK_ID, List.of(revision1()));
    }

    static EventEnvelope event(String id, long sequence) {
        return new EventEnvelope(
                new EventId(id),
                TASK_ID,
                PLAN_ID,
                sequence,
                T0.plusSeconds(sequence),
                new EventType("step-progress"),
                Optional.empty(),
                "correlation-1",
                new InlineEventPayload(
                        new ObjectValue(Map.of("message", new TextValue("event-" + sequence)))));
    }

    static ExecutionReceipt receipt(String id, String toolCallId) {
        return new ExecutionReceipt(
                new ReceiptId(id),
                new ToolCallId(toolCallId),
                ReceiptStatus.SUCCESS,
                T0,
                T0.plusSeconds(1),
                Optional.of(0),
                Optional.empty(),
                OutputCapture.inline("ok", false),
                OutputCapture.empty(),
                List.of(),
                Optional.empty(),
                List.of());
    }

    static Checkpoint checkpoint(
            long eventSequence,
            Instant createdAt,
            List<ReceiptId> receipts) {
        return new Checkpoint(
                TASK_ID,
                PLAN_ID,
                new PlanRevisionId("revision-1"),
                1,
                eventSequence,
                PlanExecutionState.ACTIVE,
                Map.of(
                        STEP_1, StepExecutionState.ACTIVE,
                        STEP_2, StepExecutionState.NOT_STARTED),
                receipts,
                createdAt);
    }

    static InMemoryPersistence initializedPersistence() {
        InMemoryPersistence persistence = new InMemoryPersistence();
        requireApplied(persistence.taskFrames().create(taskFrame()));
        requireApplied(persistence.plans().create(plan()));
        return persistence;
    }

    private static void requireApplied(PersistenceResult<?> result) {
        if (result.outcome() != PersistenceOutcome.APPLIED) {
            throw new AssertionError("fixture setup failed: " + result);
        }
    }
}
