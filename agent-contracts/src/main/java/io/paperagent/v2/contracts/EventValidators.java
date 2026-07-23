package io.paperagent.v2.contracts;

import java.util.List;

public final class EventValidators {
    private EventValidators() {
    }

    public static List<ContractViolation> validateNext(
            EventEnvelope previous,
            EventEnvelope current) {
        if (previous == null || current == null) {
            return List.of(Contracts.violation(ViolationCode.REQUIRED_VALUE_MISSING,
                    "event", "both previous and current events are required"));
        }
        if (!previous.taskFrameId().equals(current.taskFrameId())
                || !previous.planId().equals(current.planId())
                || !previous.correlationId().equals(current.correlationId())) {
            return List.of(Contracts.violation(ViolationCode.INCONSISTENT_REFERENCE,
                    "event", "event sequence members must share task, Plan, and correlation"));
        }
        if (current.sequence() <= previous.sequence()) {
            return List.of(Contracts.violation(ViolationCode.EVENT_SEQUENCE_REGRESSION,
                    "event.sequence", "event sequence must increase monotonically"));
        }
        return List.of();
    }

    public static void requireNext(EventEnvelope previous, EventEnvelope current) {
        Contracts.requireNoViolations(validateNext(previous, current));
    }
}
