package io.paperagent.v2.e2e;

import io.paperagent.v2.contracts.TaskFrameId;
import io.paperagent.v2.persistence.PersistenceErrorCode;
import io.paperagent.v2.persistence.PersistenceOutcome;
import io.paperagent.v2.providers.ModelResponse;
import io.paperagent.v2.sandbox.ExecutedCommand;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class Wave2HarnessIsolationTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void identicalLogicalInputsRemainIsolatedAcrossHarnessInstances() throws Exception {
        Wave2HarnessTestSupport first =
                new Wave2HarnessTestSupport(temporaryDirectory.resolve("first-root"));
        Wave2HarnessTestSupport second =
                new Wave2HarnessTestSupport(temporaryDirectory.resolve("second-root"));

        var firstTask = storeFoundation(first);
        var secondTask = storeFoundation(second);
        var firstPlan = first.persistence.plans().find(first.planId).value().orElseThrow();
        var secondPlan = second.persistence.plans().find(second.planId).value().orElseThrow();
        var firstWorkspace = first.materialize();
        try {
            var secondWorkspace = second.materialize();
            try {
                assertEquals(first.taskFrameId, second.taskFrameId);
                assertEquals(first.planId, second.planId);
                assertEquals(first.workspaceId, second.workspaceId);
                assertEquals(first.paperPath, second.paperPath);
                first.workspaceProvider.replace(
                        firstWorkspace,
                        first.paperPath,
                        "First workspace\n".getBytes(StandardCharsets.UTF_8));
                assertArrayEquals(
                        "First workspace\n".getBytes(StandardCharsets.UTF_8),
                        first.workspaceProvider.read(firstWorkspace, first.paperPath));
                assertArrayEquals(
                        second.sourceBytes(),
                        second.workspaceProvider.read(secondWorkspace, second.paperPath));
                assertArrayEquals(first.sourceBytes(), first.sourceFile.content());
                assertArrayEquals(second.sourceBytes(), second.sourceFile.content());

                TaskFrameId firstOnlyTaskId = new TaskFrameId("task-first-only");
                assertEquals(
                        PersistenceOutcome.APPLIED,
                        first.persistence.taskFrames()
                                .create(first.taskFrame(firstOnlyTaskId))
                                .outcome());
                assertEquals(
                        PersistenceErrorCode.NOT_FOUND,
                        second.persistence.taskFrames()
                                .find(firstOnlyTaskId)
                                .failure()
                                .orElseThrow()
                                .code());

                var firstModelRequest = first.modelRequest(firstTask, firstPlan);
                var secondModelRequest = second.modelRequest(secondTask, secondPlan);
                assertEquals(firstModelRequest, secondModelRequest);
                var firstModelProvider =
                        first.modelProvider(firstModelRequest, first.modelResponse());
                var secondModelProvider =
                        second.modelProvider(secondModelRequest, second.modelResponse());
                assertInstanceOf(
                        ModelResponse.class,
                        firstModelProvider.complete(firstModelRequest));
                assertEquals(1, firstModelProvider.observations().size());
                assertEquals(0, secondModelProvider.observations().size());
                assertInstanceOf(
                        ModelResponse.class,
                        secondModelProvider.complete(secondModelRequest));
                assertEquals(1, firstModelProvider.observations().size());
                assertEquals(1, secondModelProvider.observations().size());

                var firstSandboxRequest = first.sandboxRequest(firstWorkspace);
                var secondSandboxRequest = second.sandboxRequest(secondWorkspace);
                assertEquals(firstSandboxRequest, secondSandboxRequest);
                var firstSandboxPort =
                        first.sandboxPort(firstSandboxRequest, first.sandboxSuccess());
                var secondSandboxPort =
                        second.sandboxPort(secondSandboxRequest, second.sandboxSuccess());
                assertInstanceOf(
                        ExecutedCommand.class,
                        firstSandboxPort.execute(firstSandboxRequest));
                assertEquals(1, firstSandboxPort.observations().size());
                assertEquals(0, secondSandboxPort.observations().size());
                assertInstanceOf(
                        ExecutedCommand.class,
                        secondSandboxPort.execute(secondSandboxRequest));
                assertEquals(1, firstSandboxPort.observations().size());
                assertEquals(1, secondSandboxPort.observations().size());

                firstModelProvider.assertFullyConsumed();
                secondModelProvider.assertFullyConsumed();
                firstSandboxPort.assertFullyConsumed();
                secondSandboxPort.assertFullyConsumed();
            } finally {
                second.cleanup(secondWorkspace);
            }
        } finally {
            first.cleanup(firstWorkspace);
        }
        first.assertWorkspaceRootEmpty();
        second.assertWorkspaceRootEmpty();
    }

    private static io.paperagent.v2.contracts.TaskFrame storeFoundation(
            Wave2HarnessTestSupport harness) {
        var taskFrame = harness.taskFrame();
        assertEquals(
                PersistenceOutcome.APPLIED,
                harness.persistence.taskFrames().create(taskFrame).outcome());
        assertEquals(
                PersistenceOutcome.APPLIED,
                harness.persistence.plans().create(harness.plan()).outcome());
        return harness.persistence.taskFrames()
                .find(harness.taskFrameId)
                .value()
                .orElseThrow();
    }
}
