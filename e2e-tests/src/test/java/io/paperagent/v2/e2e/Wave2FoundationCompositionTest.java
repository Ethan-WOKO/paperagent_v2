package io.paperagent.v2.e2e;

import io.paperagent.v2.contracts.EventEnvelope;
import io.paperagent.v2.contracts.ExecutionReceipt;
import io.paperagent.v2.contracts.ReceiptId;
import io.paperagent.v2.contracts.ToolCall;
import io.paperagent.v2.contracts.WorkspaceRef;
import io.paperagent.v2.persistence.PersistenceErrorCode;
import io.paperagent.v2.persistence.PersistenceOutcome;
import io.paperagent.v2.providers.ModelResponse;
import io.paperagent.v2.providers.ProposedToolCall;
import io.paperagent.v2.sandbox.ExecutedCommand;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Wave2FoundationCompositionTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void composesFrozenFoundationBoundariesWithoutInventingRuntimeFacts() throws Exception {
        Wave2HarnessTestSupport harness =
                new Wave2HarnessTestSupport(temporaryDirectory.resolve("workspace-root"));

        assertEquals(
                PersistenceOutcome.APPLIED,
                harness.persistence.taskFrames().create(harness.taskFrame()).outcome());
        var storedTaskFrame = harness.persistence.taskFrames()
                .find(harness.taskFrameId)
                .value()
                .orElseThrow();
        assertEquals(
                PersistenceOutcome.APPLIED,
                harness.persistence.plans().create(harness.plan()).outcome());
        var storedPlan = harness.persistence.plans()
                .find(harness.planId)
                .value()
                .orElseThrow();
        assertThrows(
                UnsupportedOperationException.class,
                () -> storedTaskFrame.targets().add("other"));
        assertThrows(
                UnsupportedOperationException.class,
                () -> storedPlan.revisions().clear());
        assertThrows(
                UnsupportedOperationException.class,
                () -> storedPlan.latestRevision().steps().clear());

        WorkspaceRef workspace = harness.materialize();
        try {
            assertArrayEquals(
                    harness.sourceBytes(),
                    harness.workspaceProvider.read(workspace, harness.paperPath));
            byte[] mutableRead = harness.workspaceProvider.read(workspace, harness.paperPath);
            mutableRead[0] = 'X';
            assertArrayEquals(
                    harness.sourceBytes(),
                    harness.workspaceProvider.read(workspace, harness.paperPath));

            var modelRequest = harness.modelRequest(storedTaskFrame, storedPlan);
            ModelResponse scriptedResponse = harness.modelResponse();
            var modelProvider = harness.modelProvider(modelRequest, scriptedResponse);
            assertEquals(java.util.Optional.of(storedTaskFrame.id()), modelRequest.taskFrameId());
            assertEquals(java.util.Optional.of(storedPlan.id()), modelRequest.planId());
            assertEquals(
                    java.util.Optional.of(storedPlan.latestRevision().id()),
                    modelRequest.planRevisionId());
            assertEquals(java.util.Optional.of(harness.stepId), modelRequest.stepId());
            assertEquals(List.of(harness.workspaceReadTool), modelRequest.availableTools());
            assertTrue(harness.executionProfile.capabilities()
                    .containsAll(harness.workspaceReadTool.requiredCapabilities()));
            ModelResponse modelResult = assertInstanceOf(
                    ModelResponse.class,
                    modelProvider.complete(modelRequest));
            ProposedToolCall proposal = modelResult.proposedToolCalls().get(0);
            assertEquals(harness.workspaceReadTool.id(), proposal.toolId());
            assertEquals(harness.paperPath.value(),
                    proposal.arguments().values().get("path")
                            instanceof io.paperagent.v2.contracts.TextValue text
                            ? text.value()
                            : null);

            var sandboxRequest = harness.sandboxRequest(workspace);
            assertEquals(workspace, sandboxRequest.workspace());
            assertEquals(harness.executionProfile, sandboxRequest.executionProfile());
            assertFalse(sandboxRequest.toolCallId().value().equals(proposal.providerCallId()));
            ExecutedCommand scriptedCommand = harness.sandboxSuccess();
            var sandboxPort = harness.sandboxPort(sandboxRequest, scriptedCommand);
            ExecutedCommand sandboxResult = assertInstanceOf(
                    ExecutedCommand.class,
                    sandboxPort.execute(sandboxRequest));
            assertEquals(0, sandboxResult.exitCode());

            assertFalse(ToolCall.class.isInstance(proposal));
            assertFalse(ExecutionReceipt.class.isInstance(sandboxResult));
            assertFalse(EventEnvelope.class.isInstance(sandboxResult));
            assertFalse(modelResult.proposedToolCalls().stream().anyMatch(ToolCall.class::isInstance));
            assertEquals(
                    PersistenceErrorCode.NOT_FOUND,
                    harness.persistence.receipts()
                            .find(new ReceiptId("receipt-never-created"))
                            .failure()
                            .orElseThrow()
                            .code());
            assertEquals(
                    PersistenceErrorCode.NOT_FOUND,
                    harness.persistence.checkpoints()
                            .find(harness.planId)
                            .failure()
                            .orElseThrow()
                            .code());
            List<EventEnvelope> events = harness.persistence.events()
                    .readAfter(harness.planId, 0)
                    .value()
                    .orElseThrow();
            assertTrue(events.isEmpty());

            modelProvider.assertFullyConsumed();
            sandboxPort.assertFullyConsumed();
            assertEquals(1, modelProvider.consumedCount());
            assertEquals(1, sandboxPort.consumedCount());
            assertArrayEquals(
                    harness.sourceBytes(),
                    harness.workspaceProvider.read(workspace, harness.paperPath));
            assertArrayEquals(harness.sourceBytes(), harness.sourceFile.content());
            assertEquals(
                    java.util.Map.of("fixture", "wave2-foundation"),
                    harness.sourceSnapshot.metadata());
            assertEquals(
                    java.util.Map.of("mediaType", "text/plain"),
                    harness.sourceFile.metadata());
            assertThrows(
                    UnsupportedOperationException.class,
                    () -> harness.sourceSnapshot.files().clear());
            assertThrows(
                    UnsupportedOperationException.class,
                    () -> harness.sourceSnapshot.metadata().put("other", "value"));
            byte[] sourceView = harness.sourceFile.content();
            sourceView[0] = 'X';
            assertArrayEquals(harness.sourceBytes(), harness.sourceFile.content());
            assertThrows(
                    UnsupportedOperationException.class,
                    () -> harness.sourceFile.metadata().put("other", "value"));
            assertThrows(
                    UnsupportedOperationException.class,
                    () -> harness.workspaceProvider.list(workspace).clear());
            assertThrows(
                    UnsupportedOperationException.class,
                    () -> modelResult.proposedToolCalls().clear());
            assertThrows(
                    UnsupportedOperationException.class,
                    () -> modelResult.metadata().put("other", "value"));
            assertThrows(
                    UnsupportedOperationException.class,
                    () -> modelResult.usage().additionalCounts().put("other", 1L));
            assertThrows(
                    UnsupportedOperationException.class,
                    () -> proposal.arguments().values().put(
                            "other",
                            new io.paperagent.v2.contracts.TextValue("value")));
            assertThrows(
                    UnsupportedOperationException.class,
                    () -> modelRequest.messages().clear());
            assertThrows(
                    UnsupportedOperationException.class,
                    () -> modelRequest.availableTools().clear());
            assertThrows(
                    UnsupportedOperationException.class,
                    () -> modelProvider.observations().clear());
            assertThrows(
                    UnsupportedOperationException.class,
                    () -> sandboxPort.observations().clear());
            assertThrows(
                    UnsupportedOperationException.class,
                    () -> sandboxRequest.argv().clear());
            assertThrows(
                    UnsupportedOperationException.class,
                    () -> sandboxRequest.environment().put("OTHER", "value"));
            byte[] stdoutView = sandboxResult.stdout();
            stdoutView[0] = 'X';
            assertArrayEquals("verified\n".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    sandboxResult.stdout());
            assertThrows(
                    UnsupportedOperationException.class,
                    () -> sandboxResult.metadata().put("other", "value"));
            assertThrows(UnsupportedOperationException.class, events::clear);
        } finally {
            harness.cleanup(workspace);
        }
        harness.assertWorkspaceRootEmpty();
    }
}
