package io.paperagent.v2.api.synthesis;

import io.paperagent.v2.contracts.DiffId;
import io.paperagent.v2.contracts.FinalSynthesis;
import io.paperagent.v2.contracts.FinalSynthesisId;
import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.PlanRevisionId;
import io.paperagent.v2.contracts.ProjectVersionRef;
import io.paperagent.v2.contracts.ReceiptId;
import io.paperagent.v2.contracts.TaskFrameId;
import io.paperagent.v2.contracts.WorkspaceId;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable API-layer summary of a final synthesis without exposing its evidence content.
 */
public final class FinalSynthesisReadModel {
    private final FinalSynthesisId synthesisId;
    private final TaskFrameId taskFrameId;
    private final PlanId planId;
    private final PlanRevisionId planRevisionId;
    private final Optional<ProjectVersionRef> sourceProjectVersion;
    private final DiffId workspaceDiffId;
    private final WorkspaceId workspaceId;
    private final List<ReceiptId> receiptIds;
    private final String narrative;
    private final Instant observedAt;

    private FinalSynthesisReadModel(
            FinalSynthesisId synthesisId,
            TaskFrameId taskFrameId,
            PlanId planId,
            PlanRevisionId planRevisionId,
            Optional<ProjectVersionRef> sourceProjectVersion,
            DiffId workspaceDiffId,
            WorkspaceId workspaceId,
            List<ReceiptId> receiptIds,
            String narrative,
            Instant observedAt) {
        this.synthesisId = new FinalSynthesisId(synthesisId.value());
        this.taskFrameId = new TaskFrameId(taskFrameId.value());
        this.planId = new PlanId(planId.value());
        this.planRevisionId = new PlanRevisionId(planRevisionId.value());
        this.sourceProjectVersion = sourceProjectVersion
                .map(value -> new ProjectVersionRef(value.projectId(), value.versionId()));
        this.workspaceDiffId = new DiffId(workspaceDiffId.value());
        this.workspaceId = new WorkspaceId(workspaceId.value());
        this.receiptIds = receiptIds.stream()
                .map(receiptId -> new ReceiptId(receiptId.value()))
                .toList();
        this.narrative = narrative;
        this.observedAt = observedAt;
    }

    public static FinalSynthesisReadModel from(FinalSynthesis synthesis) {
        Objects.requireNonNull(synthesis, "synthesis");
        return new FinalSynthesisReadModel(
                synthesis.id(),
                synthesis.taskFrameId(),
                synthesis.planId(),
                synthesis.planRevisionId(),
                synthesis.sourceProjectVersion(),
                synthesis.workspaceDiff().id(),
                synthesis.workspaceDiff().workspace().id(),
                synthesis.receiptIds(),
                synthesis.narrative(),
                synthesis.observedAt());
    }

    public FinalSynthesisId synthesisId() {
        return synthesisId;
    }

    public TaskFrameId taskFrameId() {
        return taskFrameId;
    }

    public PlanId planId() {
        return planId;
    }

    public PlanRevisionId planRevisionId() {
        return planRevisionId;
    }

    public Optional<ProjectVersionRef> sourceProjectVersion() {
        return sourceProjectVersion;
    }

    public DiffId workspaceDiffId() {
        return workspaceDiffId;
    }

    public WorkspaceId workspaceId() {
        return workspaceId;
    }

    public List<ReceiptId> receiptIds() {
        return receiptIds;
    }

    public String narrative() {
        return narrative;
    }

    public Instant observedAt() {
        return observedAt;
    }

    @Override
    public String toString() {
        return "FinalSynthesisReadModel["
                + "synthesisId=<provided>, "
                + "taskFrameId=<provided>, "
                + "planId=<provided>, "
                + "planRevisionId=<provided>, "
                + "sourceProjectVersion=<provided>, "
                + "workspaceDiffId=<provided>, "
                + "workspaceId=<provided>, "
                + "receiptIds=<provided>, "
                + "narrative=<provided>, "
                + "observedAt=<provided>]";
    }
}
