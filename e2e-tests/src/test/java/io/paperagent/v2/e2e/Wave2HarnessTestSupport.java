package io.paperagent.v2.e2e;

import io.paperagent.v2.contracts.BoundedExecutionHints;
import io.paperagent.v2.contracts.Capability;
import io.paperagent.v2.contracts.ContentHash;
import io.paperagent.v2.contracts.ExecutionProfile;
import io.paperagent.v2.contracts.ExecutionTier;
import io.paperagent.v2.contracts.NetworkPolicy;
import io.paperagent.v2.contracts.ObjectValue;
import io.paperagent.v2.contracts.Plan;
import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.PlanRevision;
import io.paperagent.v2.contracts.PlanRevisionId;
import io.paperagent.v2.contracts.PlanStep;
import io.paperagent.v2.contracts.PlanStepId;
import io.paperagent.v2.contracts.ProjectPath;
import io.paperagent.v2.contracts.ProjectVersionRef;
import io.paperagent.v2.contracts.ResourceLimits;
import io.paperagent.v2.contracts.TaskFrame;
import io.paperagent.v2.contracts.TaskFrameId;
import io.paperagent.v2.contracts.TextValue;
import io.paperagent.v2.contracts.ToolCallId;
import io.paperagent.v2.contracts.ToolDescriptor;
import io.paperagent.v2.contracts.ToolId;
import io.paperagent.v2.contracts.WorkspaceId;
import io.paperagent.v2.contracts.WorkspaceRef;
import io.paperagent.v2.persistence.InMemoryPersistence;
import io.paperagent.v2.providers.CorrelationId;
import io.paperagent.v2.providers.FinishReason;
import io.paperagent.v2.providers.GenerationOptions;
import io.paperagent.v2.providers.MessageRole;
import io.paperagent.v2.providers.ModelMessage;
import io.paperagent.v2.providers.ModelRequest;
import io.paperagent.v2.providers.ModelRequestId;
import io.paperagent.v2.providers.ModelResponse;
import io.paperagent.v2.providers.ProposedToolCall;
import io.paperagent.v2.providers.ScriptedModelProvider;
import io.paperagent.v2.providers.UsageMetadata;
import io.paperagent.v2.sandbox.ExecutedCommand;
import io.paperagent.v2.sandbox.SandboxOperationIntent;
import io.paperagent.v2.sandbox.SandboxRequest;
import io.paperagent.v2.sandbox.SandboxRequestId;
import io.paperagent.v2.sandbox.ScriptedSandboxPort;
import io.paperagent.v2.workspace.LocalWorkspaceProvider;
import io.paperagent.v2.workspace.ProjectFileSnapshot;
import io.paperagent.v2.workspace.ProjectVersionSnapshot;
import io.paperagent.v2.workspace.WorkspaceLimits;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;

final class Wave2HarnessTestSupport {
    private static final String SOURCE_TEXT = "Original paper\n";
    private static final String SOURCE_SHA256 =
            "1c5cc2410e724cb198f86f546996881f098a403ff098837141f231fe9d90613f";

    final Instant createdAt = Instant.parse("2026-07-24T08:00:00Z");
    final Instant commandStartedAt = Instant.parse("2026-07-24T08:01:00Z");
    final TaskFrameId taskFrameId = new TaskFrameId("task-wave2-foundation");
    final PlanId planId = new PlanId("plan-wave2-foundation");
    final PlanRevisionId revisionId = new PlanRevisionId("revision-wave2-foundation-1");
    final PlanStepId stepId = new PlanStepId("step-read-paper");
    final ProjectVersionRef projectVersion =
            new ProjectVersionRef("project-wave2", "version-wave2-1");
    final WorkspaceId workspaceId = new WorkspaceId("workspace-wave2-foundation");
    final ProjectPath paperPath = new ProjectPath("paper/paper.txt");
    final ToolDescriptor workspaceReadTool = new ToolDescriptor(
            new ToolId("workspace.read"),
            "Read one file from the isolated workspace",
            Set.of(Capability.READ_PROJECT));
    final ExecutionProfile executionProfile = new ExecutionProfile(
            ExecutionTier.SANDBOX_STANDARD,
            Set.of(
                    Capability.READ_PROJECT,
                    Capability.WRITE_WORKSPACE,
                    Capability.EXECUTE_COMMAND),
            NetworkPolicy.DENY_ALL,
            List.of(),
            new ResourceLimits(
                    Duration.ofMinutes(2),
                    Duration.ofMinutes(1),
                    128L * 1024L * 1024L,
                    64L * 1024L,
                    2),
            Set.of());
    final InMemoryPersistence persistence = new InMemoryPersistence();
    final ProjectFileSnapshot sourceFile;
    final ProjectVersionSnapshot sourceSnapshot;
    final LocalWorkspaceProvider workspaceProvider;
    final Path workspaceRoot;

    Wave2HarnessTestSupport(Path workspaceRoot) {
        this.workspaceRoot = workspaceRoot.toAbsolutePath().normalize();
        sourceFile = new ProjectFileSnapshot(
                paperPath,
                sourceBytes(),
                new ContentHash("sha256", SOURCE_SHA256),
                Map.of("mediaType", "text/plain"));
        sourceSnapshot = new ProjectVersionSnapshot(
                projectVersion,
                List.of(sourceFile),
                Map.of("fixture", "wave2-foundation"));
        workspaceProvider = new LocalWorkspaceProvider(this.workspaceRoot, requestedVersion -> {
            if (!projectVersion.equals(requestedVersion)) {
                throw new IllegalArgumentException("unexpected synthetic project version");
            }
            return sourceSnapshot;
        });
    }

    TaskFrame taskFrame() {
        return taskFrame(taskFrameId);
    }

    TaskFrame taskFrame(TaskFrameId id) {
        return new TaskFrame(
                id,
                "Inspect the paper in an isolated workspace",
                List.of("paper/paper.txt"),
                List.of("a proposed workspace read and an independent sandbox result"),
                List.of("do not create Runtime execution facts"),
                Optional.of(projectVersion),
                executionProfile,
                createdAt);
    }

    Plan plan() {
        PlanStep step = new PlanStep(
                stepId,
                "Inspect the paper",
                "The paper contents are available for later reasoning",
                Set.of(),
                List.of("paper contents were read"),
                new BoundedExecutionHints(1, Duration.ofMinutes(1)));
        PlanRevision revision = new PlanRevision(
                revisionId,
                taskFrameId,
                1,
                Optional.empty(),
                "fixed foundation composition",
                createdAt.plusSeconds(1),
                List.of(step),
                Map.of());
        return new Plan(planId, taskFrameId, List.of(revision));
    }

    WorkspaceRef materialize() {
        return workspaceProvider.materialize(
                workspaceId,
                projectVersion,
                new WorkspaceLimits(16 * 1024L, 64 * 1024L, 8));
    }

    ModelRequest modelRequest(TaskFrame storedTaskFrame, Plan storedPlan) {
        return modelRequest(
                "model-request-foundation",
                storedTaskFrame,
                storedPlan,
                false);
    }

    ModelRequest modelRequest(
            String requestId,
            TaskFrame storedTaskFrame,
            Plan storedPlan,
            boolean cancellationRequested) {
        return new ModelRequest(
                new ModelRequestId(requestId),
                new CorrelationId("correlation-wave2-foundation"),
                List.of(
                        new ModelMessage(
                                MessageRole.SYSTEM,
                                "Use only the available isolated-workspace tools."),
                        new ModelMessage(
                                MessageRole.USER,
                                "Read paper/paper.txt before answering.")),
                List.of(workspaceReadTool),
                new GenerationOptions(
                        256,
                        1,
                        0.0d,
                        OptionalLong.of(1601L),
                        Map.of("mode", "deterministic")),
                Optional.of(storedTaskFrame.id()),
                Optional.of(storedPlan.id()),
                Optional.of(storedPlan.latestRevision().id()),
                Optional.of(storedPlan.latestRevision().steps().get(0).id()),
                cancellationRequested);
    }

    ModelResponse modelResponse() {
        return new ModelResponse(
                Optional.empty(),
                List.of(new ProposedToolCall(
                        "provider-proposal-1",
                        workspaceReadTool.id(),
                        new ObjectValue(Map.of("path", new TextValue(paperPath.value()))))),
                FinishReason.TOOL_CALLS,
                new UsageMetadata(18, 6, 0, Map.of()),
                Map.of("provider", "scripted"));
    }

    ScriptedModelProvider modelProvider(ModelRequest request, ModelResponse response) {
        return new ScriptedModelProvider(List.of(
                new ScriptedModelProvider.ScriptStep(request, response)));
    }

    SandboxRequest sandboxRequest(WorkspaceRef workspace) {
        return sandboxRequest("sandbox-request-foundation", workspace, false);
    }

    SandboxRequest sandboxRequest(
            String requestId,
            WorkspaceRef workspace,
            boolean cancellationRequested) {
        return new SandboxRequest(
                new SandboxRequestId(requestId),
                new ToolCallId("independent-" + requestId),
                workspace,
                Optional.of(new ProjectPath("paper")),
                List.of("verify-paper", "paper.txt"),
                Map.of("MODE", "deterministic"),
                Map.of(),
                SandboxOperationIntent.COMMAND,
                executionProfile,
                cancellationRequested);
    }

    ExecutedCommand sandboxSuccess() {
        return new ExecutedCommand(
                0,
                commandStartedAt,
                commandStartedAt.plusSeconds(1),
                "verified\n".getBytes(StandardCharsets.UTF_8),
                new byte[0],
                false,
                false,
                Map.of("backend", "scripted"));
    }

    ScriptedSandboxPort sandboxPort(SandboxRequest request, ExecutedCommand result) {
        return new ScriptedSandboxPort(List.of(
                new ScriptedSandboxPort.ScriptStep(request, result)));
    }

    byte[] sourceBytes() {
        return SOURCE_TEXT.getBytes(StandardCharsets.UTF_8);
    }

    void cleanup(WorkspaceRef workspace) {
        workspaceProvider.cleanup(workspace);
        workspaceProvider.cleanup(workspace);
    }

    void assertWorkspaceRootEmpty() throws IOException {
        try (var children = Files.list(workspaceRoot)) {
            if (children.findAny().isPresent()) {
                throw new AssertionError("workspace root was not empty after cleanup");
            }
        }
    }
}
