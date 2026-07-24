package io.paperagent.v2.persistence;

import io.paperagent.v2.contracts.Checkpoint;
import io.paperagent.v2.contracts.CompletionFact;
import io.paperagent.v2.contracts.EventEnvelope;
import io.paperagent.v2.contracts.EventId;
import io.paperagent.v2.contracts.Plan;
import io.paperagent.v2.contracts.PlanExecutionState;
import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.PlanRevision;
import io.paperagent.v2.contracts.PlanStepId;
import io.paperagent.v2.contracts.StepExecutionState;
import io.paperagent.v2.contracts.TaskFrame;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.stream.Collectors;

final class InMemoryExecutionStartRecoveryRepository
        implements ExecutionStartRecoveryRepository {
    private final InMemoryState state;

    InMemoryExecutionStartRecoveryRepository(InMemoryState state) {
        this.state = state;
    }

    @Override
    public PersistenceResult<ExecutionStartRecoverySnapshot> inspect(PlanId planId) {
        if (PersistenceChecks.missing(planId)) {
            return PersistenceChecks.invalid("planId");
        }
        synchronized (state.monitor) {
            if (!hasPlanScopedOccupancy(planId)) {
                return PersistenceChecks.notFound("planId");
            }

            PersistedPlanBootstrap bootstrap = state.planBootstraps.get(planId);
            Plan currentPlan = state.plans.get(planId);
            if (!hasConsistentBootstrapRoot(planId, bootstrap, currentPlan)) {
                return partialState();
            }

            boolean hasStartMarker = state.executionStarts.containsKey(planId);
            if (!hasStartMarker) {
                if (!isStrictReady(planId, bootstrap, currentPlan)) {
                    return partialState();
                }
                return PersistenceResult.found(
                        new PersistedExecutionStartReady(bootstrap, currentPlan));
            }

            InMemoryState.ExecutionStartMarker marker =
                    state.executionStarts.get(planId);
            if (!hasConsistentPermanentStart(
                            planId, bootstrap, currentPlan, marker)
                    || !hasConsistentEventProjection(
                            planId, bootstrap.taskFrame(), marker.result())) {
                return partialState();
            }

            if (isStrictCommitted(planId, currentPlan, marker.result())) {
                return PersistenceResult.found(
                        new PersistedExecutionStartCommitted(
                                bootstrap, currentPlan, marker.result()));
            }
            if (isRecognizableAdvanced(
                    planId, bootstrap.taskFrame(), currentPlan, marker.result())) {
                return advancedState();
            }
            return partialState();
        }
    }

    private boolean hasPlanScopedOccupancy(PlanId planId) {
        return state.plans.containsKey(planId)
                || state.checkpoints.containsKey(planId)
                || state.eventStreams.containsKey(planId)
                || state.eventsById.values().stream()
                        .anyMatch(event ->
                                event != null && planId.equals(event.planId()))
                || state.planBootstraps.containsKey(planId)
                || state.executionStarts.containsKey(planId)
                || state.leases.containsKey(planId)
                || state.fencingTokens.containsKey(planId);
    }

    private boolean hasConsistentBootstrapRoot(
            PlanId planId,
            PersistedPlanBootstrap bootstrap,
            Plan currentPlan) {
        if (bootstrap == null
                || currentPlan == null
                || !planId.equals(bootstrap.plan().id())
                || !planId.equals(currentPlan.id())) {
            return false;
        }
        TaskFrame bootstrapTaskFrame = bootstrap.taskFrame();
        if (!bootstrapTaskFrame.equals(
                        state.taskFrames.get(bootstrapTaskFrame.id()))
                || !bootstrapTaskFrame.id().equals(bootstrap.plan().taskFrameId())
                || !bootstrapTaskFrame.id().equals(currentPlan.taskFrameId())
                || !isExactPrefix(
                        bootstrap.plan().revisions(), currentPlan.revisions())) {
            return false;
        }
        return isCanonicalBootstrapCheckpoint(bootstrap);
    }

    private static boolean isCanonicalBootstrapCheckpoint(
            PersistedPlanBootstrap bootstrap) {
        VersionedCheckpoint source = bootstrap.initialCheckpoint();
        if (source.version() != 1) {
            return false;
        }
        Checkpoint checkpoint = source.checkpoint();
        Plan bootstrapPlan = bootstrap.plan();
        PlanRevision latest = bootstrapPlan.latestRevision();
        return checkpoint.taskFrameId().equals(bootstrap.taskFrame().id())
                && checkpoint.planId().equals(bootstrapPlan.id())
                && matchesRevision(latest, checkpoint)
                && checkpoint.lastEventSequence() == 0
                && checkpoint.planState() == PlanExecutionState.NOT_STARTED
                && hasExactStepShape(
                        checkpoint, latest, StepExecutionState.NOT_STARTED)
                && checkpoint.receiptReferences().isEmpty()
                && latest.completedFacts().isEmpty();
    }

    private boolean isStrictReady(
            PlanId planId,
            PersistedPlanBootstrap bootstrap,
            Plan currentPlan) {
        NavigableMap<Long, EventEnvelope> stream =
                state.eventStreams.get(planId);
        return bootstrap.initialCheckpoint().equals(state.checkpoints.get(planId))
                && (stream == null
                        ? !state.eventStreams.containsKey(planId)
                        : stream.isEmpty())
                && planIndexIsEmpty(planId)
                && currentPlan.latestRevision().completedFacts().isEmpty();
    }

    private boolean hasConsistentPermanentStart(
            PlanId planId,
            PersistedPlanBootstrap bootstrap,
            Plan currentPlan,
            InMemoryState.ExecutionStartMarker marker) {
        if (marker == null || marker.request() == null || marker.result() == null) {
            return false;
        }
        ExecutionStartRequest request = marker.request();
        PersistedExecutionStart result = marker.result();
        EventEnvelope event = result.startEvent();
        VersionedCheckpoint started = result.startedCheckpoint();
        Checkpoint checkpoint = started.checkpoint();
        Checkpoint bootstrapCheckpoint =
                bootstrap.initialCheckpoint().checkpoint();
        PlanRevision startedRevision =
                findRevision(currentPlan, checkpoint.revisionNumber());
        return request.planId().equals(planId)
                && result.planId().equals(planId)
                && request.fencingToken() == result.fencingToken()
                && request.startEvent().equals(event)
                && request.startedCheckpoint().equals(checkpoint)
                && started.version() == 2
                && event.planId().equals(planId)
                && event.taskFrameId().equals(bootstrap.taskFrame().id())
                && event.sequence() == 1
                && checkpoint.planId().equals(planId)
                && checkpoint.taskFrameId().equals(bootstrap.taskFrame().id())
                && checkpoint.lastEventSequence() == 1
                && checkpoint.planState() == PlanExecutionState.ACTIVE
                && isNotBeforeBootstrap(checkpoint, bootstrapCheckpoint)
                && startedRevision != null
                && matchesRevision(startedRevision, checkpoint)
                && startedRevision.completedFacts().isEmpty()
                && hasExactStepShape(
                        checkpoint,
                        startedRevision,
                        StepExecutionState.NOT_STARTED)
                && checkpoint.receiptReferences().isEmpty();
    }

    private static boolean isNotBeforeBootstrap(
            Checkpoint started,
            Checkpoint bootstrap) {
        boolean revisionIsNotBefore =
                started.revisionNumber() > bootstrap.revisionNumber()
                        || started.revisionNumber()
                                        == bootstrap.revisionNumber()
                                && started.revisionId()
                                        .equals(bootstrap.revisionId());
        return revisionIsNotBefore
                && !started.createdAt().isBefore(bootstrap.createdAt());
    }

    private boolean hasConsistentEventProjection(
            PlanId planId,
            TaskFrame taskFrame,
            PersistedExecutionStart executionStart) {
        NavigableMap<Long, EventEnvelope> stream =
                state.eventStreams.get(planId);
        if (stream == null
                || stream.isEmpty()
                || !executionStart.startEvent().equals(stream.get(1L))) {
            return false;
        }

        Set<EventId> streamIds = new HashSet<>();
        long previousSequence = 0;
        for (Map.Entry<Long, EventEnvelope> entry : stream.entrySet()) {
            Long streamSequence = entry.getKey();
            EventEnvelope event = entry.getValue();
            if (streamSequence == null
                    || event == null
                    || streamSequence != event.sequence()
                    || event.sequence() <= previousSequence
                    || !event.planId().equals(planId)
                    || !event.taskFrameId().equals(taskFrame.id())
                    || !streamIds.add(event.id())
                    || !event.equals(state.eventsById.get(event.id()))) {
                return false;
            }
            previousSequence = event.sequence();
        }

        int indexedForPlan = 0;
        for (Map.Entry<EventId, EventEnvelope> indexed :
                state.eventsById.entrySet()) {
            EventEnvelope event = indexed.getValue();
            if (event != null && event.planId().equals(planId)) {
                indexedForPlan++;
                if (!indexed.getKey().equals(event.id())
                        || !event.equals(stream.get(event.sequence()))) {
                    return false;
                }
            }
        }
        return indexedForPlan == stream.size();
    }

    private boolean isStrictCommitted(
            PlanId planId,
            Plan currentPlan,
            PersistedExecutionStart executionStart) {
        NavigableMap<Long, EventEnvelope> stream =
                state.eventStreams.get(planId);
        Checkpoint started = executionStart.startedCheckpoint().checkpoint();
        return stream.size() == 1
                && executionStart.startedCheckpoint().equals(
                        state.checkpoints.get(planId))
                && matchesRevision(currentPlan.latestRevision(), started);
    }

    private boolean isRecognizableAdvanced(
            PlanId planId,
            TaskFrame taskFrame,
            Plan currentPlan,
            PersistedExecutionStart executionStart) {
        VersionedCheckpoint current = state.checkpoints.get(planId);
        VersionedCheckpoint started = executionStart.startedCheckpoint();
        if (current == null
                || current.version() < started.version()
                || current.version() == started.version()
                        && !current.equals(started)
                || !isMonotonicCheckpointSuccessor(
                        planId,
                        taskFrame,
                        currentPlan,
                        started.checkpoint(),
                        current.checkpoint())) {
            return false;
        }
        return referencedReceiptsExist(current.checkpoint(), currentPlan);
    }

    private boolean isMonotonicCheckpointSuccessor(
            PlanId planId,
            TaskFrame taskFrame,
            Plan currentPlan,
            Checkpoint started,
            Checkpoint current) {
        if (!current.planId().equals(planId)
                || !current.taskFrameId().equals(taskFrame.id())
                || current.revisionNumber() < started.revisionNumber()
                || current.lastEventSequence() < started.lastEventSequence()
                || current.createdAt().isBefore(started.createdAt())
                || !current.receiptReferences()
                        .containsAll(started.receiptReferences())
                || current.planState() == PlanExecutionState.NOT_STARTED) {
            return false;
        }

        PlanRevision revision =
                findRevision(currentPlan, current.revisionNumber());
        if (revision == null
                || !matchesRevision(revision, current)
                || current.revisionNumber() == started.revisionNumber()
                        && !current.revisionId().equals(started.revisionId())
                || !hasCoherentStepAndFactShape(current, revision)) {
            return false;
        }

        NavigableMap<Long, EventEnvelope> stream =
                state.eventStreams.get(planId);
        EventEnvelope cursorEvent = stream.get(current.lastEventSequence());
        return cursorEvent != null
                && cursorEvent.planId().equals(planId)
                && cursorEvent.sequence() == current.lastEventSequence();
    }

    private boolean referencedReceiptsExist(
            Checkpoint checkpoint,
            Plan currentPlan) {
        for (var receiptId : checkpoint.receiptReferences()) {
            if (!state.receipts.containsKey(receiptId)) {
                return false;
            }
        }
        for (CompletionFact fact :
                currentPlan.latestRevision().completedFacts().values()) {
            for (var receiptId : fact.receiptReferences()) {
                if (!state.receipts.containsKey(receiptId)) {
                    return false;
                }
            }
        }
        return true;
    }

    private boolean planIndexIsEmpty(PlanId planId) {
        return state.eventsById.values().stream()
                .noneMatch(event ->
                        event != null && planId.equals(event.planId()));
    }

    private static boolean hasExactStepShape(
            Checkpoint checkpoint,
            PlanRevision revision,
            StepExecutionState expectedState) {
        Set<PlanStepId> stepIds = revision.steps().stream()
                .map(step -> step.id())
                .collect(Collectors.toSet());
        return checkpoint.stepStates().keySet().equals(stepIds)
                && checkpoint.stepStates().values().stream()
                        .allMatch(value -> value == expectedState);
    }

    private static boolean hasCoherentStepAndFactShape(
            Checkpoint checkpoint,
            PlanRevision revision) {
        Set<PlanStepId> stepIds = revision.steps().stream()
                .map(step -> step.id())
                .collect(Collectors.toSet());
        if (!checkpoint.stepStates().keySet().equals(stepIds)) {
            return false;
        }
        for (PlanStepId stepId : stepIds) {
            boolean succeeded =
                    checkpoint.stepStates().get(stepId)
                            == StepExecutionState.SUCCEEDED;
            if (succeeded != revision.completedFacts().containsKey(stepId)) {
                return false;
            }
        }
        return checkpoint.planState() != PlanExecutionState.SUCCEEDED
                || checkpoint.stepStates().values().stream()
                        .allMatch(state -> state == StepExecutionState.SUCCEEDED);
    }

    private static PlanRevision findRevision(Plan plan, long number) {
        return plan.revisions().stream()
                .filter(revision -> revision.number() == number)
                .findFirst()
                .orElse(null);
    }

    private static boolean matchesRevision(
            PlanRevision revision,
            Checkpoint checkpoint) {
        return revision.id().equals(checkpoint.revisionId())
                && revision.number() == checkpoint.revisionNumber();
    }

    private static boolean isExactPrefix(
            List<?> prefix,
            List<?> values) {
        return values.size() >= prefix.size()
                && values.subList(0, prefix.size()).equals(prefix);
    }

    private static PersistenceResult<ExecutionStartRecoverySnapshot> partialState() {
        return PersistenceResult.rejected(
                PersistenceErrorCode.EXECUTION_RECOVERY_PARTIAL_STATE,
                "executionRecovery");
    }

    private static PersistenceResult<ExecutionStartRecoverySnapshot> advancedState() {
        return PersistenceResult.rejected(
                PersistenceErrorCode.EXECUTION_RECOVERY_ADVANCED_STATE,
                "executionRecovery");
    }
}
