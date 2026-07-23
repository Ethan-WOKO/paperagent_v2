package io.paperagent.v2.e2e;

import io.paperagent.v2.contracts.EventEnvelope;
import io.paperagent.v2.contracts.ReceiptId;
import io.paperagent.v2.persistence.PersistenceErrorCode;
import io.paperagent.v2.persistence.PersistenceOutcome;
import io.paperagent.v2.providers.ModelResponse;
import io.paperagent.v2.providers.ProviderFailure;
import io.paperagent.v2.providers.ProviderFailureCode;
import io.paperagent.v2.sandbox.ExecutedCommand;
import io.paperagent.v2.sandbox.SandboxFailure;
import io.paperagent.v2.sandbox.SandboxFailureCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Wave2FailureIsolationTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void stableProviderAndSandboxFailuresDoNotConsumeOrContaminateState() throws Exception {
        Wave2HarnessTestSupport harness =
                new Wave2HarnessTestSupport(temporaryDirectory.resolve("workspace-root"));
        var taskFrame = harness.taskFrame();
        var plan = harness.plan();
        assertEquals(
                PersistenceOutcome.APPLIED,
                harness.persistence.taskFrames().create(taskFrame).outcome());
        assertEquals(
                PersistenceOutcome.APPLIED,
                harness.persistence.plans().create(plan).outcome());
        var storedTaskFrame = harness.persistence.taskFrames()
                .find(harness.taskFrameId)
                .value()
                .orElseThrow();
        var storedPlan = harness.persistence.plans()
                .find(harness.planId)
                .value()
                .orElseThrow();
        var workspace = harness.materialize();
        try {
            byte[] workspaceBefore = harness.workspaceProvider.read(workspace, harness.paperPath);

            var expectedModelRequest = harness.modelRequest(storedTaskFrame, storedPlan);
            ModelResponse expectedModelResponse = harness.modelResponse();
            var modelProvider =
                    harness.modelProvider(expectedModelRequest, expectedModelResponse);
            var unexpectedModelRequest = harness.modelRequest(
                    "model-request-unexpected",
                    storedTaskFrame,
                    storedPlan,
                    false);
            ProviderFailure providerFailure = assertInstanceOf(
                    ProviderFailure.class,
                    modelProvider.complete(unexpectedModelRequest));
            assertEquals(ProviderFailureCode.SCRIPTED_MISMATCH, providerFailure.code());
            assertEquals(0, modelProvider.consumedCount());
            assertEquals(1, modelProvider.remainingCount());
            assertEquals(
                    List.of(ProviderFailureCode.SCRIPTED_MISMATCH),
                    modelProvider.observations().stream()
                            .map(observation -> observation.failureCode().orElseThrow())
                            .toList());
            assertTrue(modelProvider.observations().stream()
                    .noneMatch(io.paperagent.v2.providers.ScriptObservation::consumed));

            var expectedSandboxRequest = harness.sandboxRequest(workspace);
            ExecutedCommand expectedSandboxResult = harness.sandboxSuccess();
            var sandboxPort =
                    harness.sandboxPort(expectedSandboxRequest, expectedSandboxResult);
            var cancelledSandboxRequest = harness.sandboxRequest(
                    "sandbox-request-foundation",
                    workspace,
                    true);
            SandboxFailure cancelled = assertInstanceOf(
                    SandboxFailure.class,
                    sandboxPort.execute(cancelledSandboxRequest));
            assertEquals(SandboxFailureCode.CANCELLED, cancelled.code());
            var unexpectedSandboxRequest = harness.sandboxRequest(
                    "sandbox-request-unexpected",
                    workspace,
                    false);
            SandboxFailure mismatch = assertInstanceOf(
                    SandboxFailure.class,
                    sandboxPort.execute(unexpectedSandboxRequest));
            assertEquals(SandboxFailureCode.SCRIPTED_MISMATCH, mismatch.code());
            assertEquals(0, sandboxPort.consumedCount());
            assertEquals(1, sandboxPort.remainingCount());
            assertEquals(
                    List.of(SandboxFailureCode.CANCELLED, SandboxFailureCode.SCRIPTED_MISMATCH),
                    sandboxPort.observations().stream()
                            .map(observation -> observation.failureCode().orElseThrow())
                            .toList());
            assertTrue(sandboxPort.observations().stream()
                    .noneMatch(io.paperagent.v2.sandbox.ScriptObservation::consumed));

            assertEquals(
                    storedTaskFrame,
                    harness.persistence.taskFrames()
                            .find(harness.taskFrameId)
                            .value()
                            .orElseThrow());
            assertEquals(
                    storedPlan,
                    harness.persistence.plans()
                            .find(harness.planId)
                            .value()
                            .orElseThrow());
            assertEquals(
                    PersistenceErrorCode.NOT_FOUND,
                    harness.persistence.checkpoints()
                            .find(harness.planId)
                            .failure()
                            .orElseThrow()
                            .code());
            assertEquals(
                    PersistenceErrorCode.NOT_FOUND,
                    harness.persistence.receipts()
                            .find(new ReceiptId("receipt-never-created"))
                            .failure()
                            .orElseThrow()
                            .code());
            List<EventEnvelope> events = harness.persistence.events()
                    .read(harness.planId, expectedModelRequest.correlationId().value())
                    .value()
                    .orElseThrow();
            assertTrue(events.isEmpty());
            assertArrayEquals(
                    workspaceBefore,
                    harness.workspaceProvider.read(workspace, harness.paperPath));
            assertArrayEquals(harness.sourceBytes(), harness.sourceFile.content());
            assertEquals(
                    java.util.Map.of("fixture", "wave2-foundation"),
                    harness.sourceSnapshot.metadata());

            assertInstanceOf(ModelResponse.class, modelProvider.complete(expectedModelRequest));
            assertInstanceOf(ExecutedCommand.class, sandboxPort.execute(expectedSandboxRequest));
            modelProvider.assertFullyConsumed();
            sandboxPort.assertFullyConsumed();
        } finally {
            harness.cleanup(workspace);
        }
        harness.assertWorkspaceRootEmpty();
    }
}
