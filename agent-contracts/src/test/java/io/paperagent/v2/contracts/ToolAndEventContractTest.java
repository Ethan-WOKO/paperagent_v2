package io.paperagent.v2.contracts;

import static io.paperagent.v2.contracts.ContractFixtures.PLAN_ID;
import static io.paperagent.v2.contracts.ContractFixtures.STEP_1;
import static io.paperagent.v2.contracts.ContractFixtures.T0;
import static io.paperagent.v2.contracts.ContractFixtures.TASK_ID;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

class ToolAndEventContractTest {
    @Test
    void recordsFrameworkNeutralToolAndEventFacts() {
        ToolDescriptor descriptor = new ToolDescriptor(
                new ToolId("workspace.read"),
                "Read one workspace file",
                Set.of(Capability.READ_PROJECT));
        ToolCall call = new ToolCall(
                new ToolCallId("call-1"),
                descriptor.id(),
                TASK_ID,
                PLAN_ID,
                STEP_1,
                new ObjectValue(Map.of("path", new TextValue("paper/main.tex"))),
                T0);
        ToolResult result = new ToolResult(
                call.id(),
                ToolResultStatus.SUCCESS,
                Optional.of(new TextValue("contents")),
                Optional.empty(),
                T0.plusSeconds(1));
        EventEnvelope event = new EventEnvelope(
                new EventId("event-1"),
                TASK_ID,
                PLAN_ID,
                1,
                T0.plusSeconds(1),
                new EventType("tool.completed"),
                Optional.empty(),
                "correlation-1",
                new InlineEventPayload(new ObjectValue(Map.of(
                        "callId", new TextValue(call.id().value()),
                        "status", new TextValue(result.status().name())))));

        assertEquals(call.id(), result.toolCallId());
        assertEquals("tool.completed", event.type().value());
    }

    @Test
    void rejectsSelfCausation() {
        EventId id = new EventId("event-1");
        ContractViolationException exception = ContractFixtures.violation(() -> new EventEnvelope(
                id,
                TASK_ID,
                PLAN_ID,
                1,
                T0,
                new EventType("event"),
                Optional.of(id),
                "correlation-1",
                new EventPayloadRef("payload-1")));
        assertEquals(ViolationCode.INCONSISTENT_REFERENCE, exception.primaryCode());
    }

    @Test
    void rejectsRegressedEventSequence() {
        EventEnvelope previous = event("event-1", 5);
        EventEnvelope current = event("event-2", 5);
        assertEquals(
                ViolationCode.EVENT_SEQUENCE_REGRESSION,
                EventValidators.validateNext(previous, current).get(0).code());
    }

    private static EventEnvelope event(String id, long sequence) {
        return new EventEnvelope(
                new EventId(id),
                TASK_ID,
                PLAN_ID,
                sequence,
                T0.plusSeconds(sequence),
                new EventType("event"),
                Optional.empty(),
                "correlation-1",
                new EventPayloadRef("payload-1"));
    }
}
