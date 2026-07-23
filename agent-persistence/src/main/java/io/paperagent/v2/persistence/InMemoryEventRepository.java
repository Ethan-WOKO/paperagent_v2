package io.paperagent.v2.persistence;

import io.paperagent.v2.contracts.EventEnvelope;
import io.paperagent.v2.contracts.EventId;
import io.paperagent.v2.contracts.EventValidators;
import io.paperagent.v2.contracts.Plan;
import io.paperagent.v2.contracts.PlanId;

import java.util.List;
import java.util.NavigableMap;
import java.util.TreeMap;

final class InMemoryEventRepository implements EventRepository {
    private final InMemoryState state;

    InMemoryEventRepository(InMemoryState state) {
        this.state = state;
    }

    @Override
    public PersistenceResult<EventEnvelope> append(EventEnvelope event) {
        if (PersistenceChecks.missing(event)) {
            return PersistenceChecks.invalid("event");
        }
        synchronized (state.monitor) {
            EventEnvelope existing = state.eventsById.get(event.id());
            if (existing != null) {
                return existing.equals(event)
                        ? PersistenceResult.replayed(existing)
                        : PersistenceResult.rejected(
                                PersistenceErrorCode.CONFLICTING_REPLAY, "event.id");
            }
            Plan plan = state.plans.get(event.planId());
            if (plan == null) {
                return PersistenceChecks.notFound("event.planId");
            }
            if (!plan.taskFrameId().equals(event.taskFrameId())) {
                return PersistenceResult.rejected(
                        PersistenceErrorCode.TASK_FRAME_MISMATCH, "event.taskFrameId");
            }
            InMemoryState.EventStreamKey key =
                    new InMemoryState.EventStreamKey(event.planId(), event.correlationId());
            NavigableMap<Long, EventEnvelope> stream = state.eventStreams.get(key);
            if (stream != null && !stream.isEmpty()) {
                EventEnvelope previous = stream.lastEntry().getValue();
                if (!EventValidators.validateNext(previous, event).isEmpty()
                        || stream.containsKey(event.sequence())) {
                    return PersistenceResult.rejected(
                            PersistenceErrorCode.EVENT_SEQUENCE_NOT_MONOTONIC, "event.sequence");
                }
            }
            if (stream == null) {
                stream = new TreeMap<>();
                state.eventStreams.put(key, stream);
            }
            stream.put(event.sequence(), event);
            state.eventsById.put(event.id(), event);
            return PersistenceResult.applied(event);
        }
    }

    @Override
    public PersistenceResult<EventEnvelope> find(EventId eventId) {
        if (PersistenceChecks.missing(eventId)) {
            return PersistenceChecks.invalid("eventId");
        }
        synchronized (state.monitor) {
            EventEnvelope event = state.eventsById.get(eventId);
            return event == null
                    ? PersistenceChecks.notFound("eventId")
                    : PersistenceResult.found(event);
        }
    }

    @Override
    public PersistenceResult<List<EventEnvelope>> read(PlanId planId, String correlationId) {
        if (PersistenceChecks.missing(planId)) {
            return PersistenceChecks.invalid("planId");
        }
        if (PersistenceChecks.invalidIdentifier(correlationId)) {
            return PersistenceChecks.invalid("correlationId");
        }
        synchronized (state.monitor) {
            if (!state.plans.containsKey(planId)) {
                return PersistenceChecks.notFound("planId");
            }
            NavigableMap<Long, EventEnvelope> stream = state.eventStreams.get(
                    new InMemoryState.EventStreamKey(planId, correlationId));
            List<EventEnvelope> snapshot = stream == null
                    ? List.of()
                    : List.copyOf(stream.values());
            return PersistenceResult.found(snapshot);
        }
    }
}
