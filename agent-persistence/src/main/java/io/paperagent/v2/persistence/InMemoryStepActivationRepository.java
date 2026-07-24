package io.paperagent.v2.persistence;

import io.paperagent.v2.contracts.Checkpoint;
import io.paperagent.v2.contracts.CheckpointValidators;
import io.paperagent.v2.contracts.CompletionFact;
import io.paperagent.v2.contracts.EventEnvelope;
import io.paperagent.v2.contracts.EventId;
import io.paperagent.v2.contracts.Plan;
import io.paperagent.v2.contracts.PlanExecutionState;
import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.PlanRevision;
import io.paperagent.v2.contracts.PlanStep;
import io.paperagent.v2.contracts.PlanStepId;
import io.paperagent.v2.contracts.StepExecutionState;
import io.paperagent.v2.contracts.TaskFrame;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

final class InMemoryStepActivationRepository
        implements StepActivationRepository {
    private static final String PARTIAL_PATH = "stepActivation";
    private static final String ELIGIBILITY_PATH = "stepActivation.source";

    private final InMemoryState state;

    InMemoryStepActivationRepository(InMemoryState state) {
        this.state = state;
    }

    @Override
    public PersistenceResult<PersistedStepActivation> activate(
            StepActivationRequest request) {
        if (request == null) {
            return PersistenceChecks.invalid("request");
        }
        synchronized (state.monitor) {
            Map<EventId, InMemoryState.StepActivationMarker> planMarkers =
                    state.stepActivations.get(request.planId());
            if (planMarkers != null
                    && planMarkers.containsKey(request.activationEvent().id())) {
                InMemoryState.StepActivationMarker marker =
                        planMarkers.get(request.activationEvent().id());
                if (!isSelfConsistentMarker(
                                request.planId(),
                                request.activationEvent().id(),
                                marker)) {
                    return partialState();
                }
                return marker.request().equals(request)
                        ? PersistenceResult.replayed(marker.result())
                        : PersistenceResult.rejected(
                                PersistenceErrorCode.CONFLICTING_REPLAY,
                                "request.activationEvent.id");
            }

            Instant effectiveNow = state.observeLeaseTime();
            AuthoritativeSource source =
                    validateAuthoritativeSource(state, request.planId());
            if (source == null) {
                return hasPlanScopedOccupancy(state, request.planId())
                        ? partialState()
                        : PersistenceChecks.notFound("request.planId");
            }

            LeaseRecord lease = state.leases.get(request.planId());
            if (lease == null) {
                return PersistenceResult.rejected(
                        PersistenceErrorCode.LEASE_NOT_HELD,
                        "request.planId");
            }
            if (!lease.leaseToken().equals(request.leaseToken())) {
                return PersistenceResult.rejected(
                        PersistenceErrorCode.LEASE_TOKEN_INVALID,
                        "request.leaseToken");
            }
            if (lease.fencingToken() != request.fencingToken()) {
                return PersistenceResult.rejected(
                        PersistenceErrorCode.LEASE_FENCING_TOKEN_INVALID,
                        "request.fencingToken");
            }
            if (lease.isExpiredAt(effectiveNow)) {
                return PersistenceResult.rejected(
                        PersistenceErrorCode.LEASE_EXPIRED,
                        "request.planId");
            }

            PlanRevision latest = source.plan().latestRevision();
            if (!latest.id().equals(request.expectedRevisionId())) {
                return PersistenceResult.rejected(
                        PersistenceErrorCode.STALE_VERSION,
                        "request.expectedRevisionId");
            }
            if (latest.number() != request.expectedRevisionNumber()) {
                return PersistenceResult.rejected(
                        PersistenceErrorCode.STALE_VERSION,
                        "request.expectedRevisionNumber");
            }
            if (source.checkpoint().version()
                    != request.expectedCheckpointVersion()) {
                return PersistenceResult.rejected(
                        PersistenceErrorCode.STALE_VERSION,
                        "request.expectedCheckpointVersion");
            }
            if (source.eventHeadSequence()
                    != request.expectedEventHeadSequence()) {
                return PersistenceResult.rejected(
                        PersistenceErrorCode.STALE_VERSION,
                        "request.expectedEventHeadSequence");
            }

            if (!isEligible(
                    source.plan(),
                    source.checkpoint().checkpoint(),
                    request.stepId())) {
                return PersistenceResult.rejected(
                        PersistenceErrorCode.STEP_ACTIVATION_NOT_ELIGIBLE,
                        ELIGIBILITY_PATH);
            }

            EventEnvelope event = request.activationEvent();
            if (!event.planId().equals(request.planId())) {
                return PersistenceChecks.invalid(
                        "request.activationEvent.planId");
            }
            if (!event.taskFrameId().equals(source.taskFrame().id())) {
                return PersistenceResult.rejected(
                        PersistenceErrorCode.TASK_FRAME_MISMATCH,
                        "request.activationEvent.taskFrameId");
            }
            if (event.sequence() <= source.eventHeadSequence()) {
                return PersistenceResult.rejected(
                        PersistenceErrorCode.EVENT_SEQUENCE_NOT_MONOTONIC,
                        "request.activationEvent.sequence");
            }
            if (state.eventsById.containsKey(event.id())) {
                return PersistenceResult.rejected(
                        PersistenceErrorCode.CONFLICTING_REPLAY,
                        "request.activationEvent.id");
            }

            PersistenceResult<PersistedStepActivation> invalidTarget =
                    validateTarget(request, source);
            if (invalidTarget != null) {
                return invalidTarget;
            }

            VersionedCheckpoint activated = new VersionedCheckpoint(
                    request.expectedCheckpointVersion() + 1,
                    request.activatedCheckpoint());
            PersistedStepActivation result = new PersistedStepActivation(
                    request.planId(),
                    request.stepId(),
                    lease.ownerId(),
                    lease.fencingToken(),
                    event,
                    activated);
            InMemoryState.ExecutionMutationHead resultHead =
                    new InMemoryState.ExecutionMutationHead(
                            latest.id(),
                            latest.number(),
                            activated.version(),
                            event.sequence(),
                            event.id());
            InMemoryState.ExecutionMutationLink link =
                    new InMemoryState.ExecutionMutationLink(
                            source.head(),
                            resultHead,
                            InMemoryState.ExecutionMutationMarkerIdentity
                                    .stepActivation(event.id()));
            InMemoryState.StepActivationMarker committedMarker =
                    new InMemoryState.StepActivationMarker(
                            request, result, link);

            NavigableMap<Long, EventEnvelope> committedStream =
                    new TreeMap<>(source.eventStream());
            committedStream.put(event.sequence(), event);
            Map<EventId, InMemoryState.StepActivationMarker> committedMarkers =
                    new java.util.LinkedHashMap<>(source.activationMarkers());
            committedMarkers.put(event.id(), committedMarker);
            List<InMemoryState.ExecutionMutationLink> committedLinks =
                    new java.util.ArrayList<>(source.links());
            committedLinks.add(link);

            state.eventStreams.put(request.planId(), committedStream);
            state.eventsById.put(event.id(), event);
            state.checkpoints.put(request.planId(), activated);
            state.stepActivations.put(request.planId(), committedMarkers);
            state.executionMutationLinks.put(request.planId(), committedLinks);
            state.executionMutationHeads.put(request.planId(), resultHead);
            return PersistenceResult.applied(result);
        }
    }

    static AuthoritativeSource validateAuthoritativeSource(
            InMemoryState state,
            PlanId planId) {
        Plan plan = state.plans.get(planId);
        PersistedPlanBootstrap bootstrap = state.planBootstraps.get(planId);
        InMemoryState.ExecutionStartMarker start =
                state.executionStarts.get(planId);
        VersionedCheckpoint current = state.checkpoints.get(planId);
        InMemoryState.ExecutionMutationHead currentHead =
                state.executionMutationHeads.get(planId);
        List<InMemoryState.ExecutionMutationLink> links =
                state.executionMutationLinks.get(planId);
        Map<EventId, InMemoryState.StepActivationMarker> activationMarkers =
                state.stepActivations.get(planId);
        NavigableMap<Long, EventEnvelope> stream =
                state.eventStreams.get(planId);
        if (plan == null
                || bootstrap == null
                || start == null
                || current == null
                || currentHead == null
                || !isCompleteHead(currentHead)
                || links == null
                || activationMarkers == null
                || stream == null
                || stream.isEmpty()) {
            return null;
        }
        TaskFrame taskFrame = state.taskFrames.get(plan.taskFrameId());
        if (!hasCanonicalBootstrapRoot(
                        planId, taskFrame, plan, bootstrap)
                || !hasCanonicalStart(
                        planId, taskFrame, plan, bootstrap, start)
                || !hasConsistentEventProjection(
                        state, planId, taskFrame, stream)
                || !start.result().startEvent().equals(stream.get(1L))) {
            return null;
        }

        InMemoryState.ExecutionMutationHead root =
                headFromStart(start.result());
        if (!isValidChain(
                        planId,
                        root,
                        currentHead,
                        links,
                        activationMarkers,
                        stream)) {
            return null;
        }

        PlanRevision latest = plan.latestRevision();
        Checkpoint checkpoint = current.checkpoint();
        EventEnvelope headEvent = stream.lastEntry().getValue();
        if (links.isEmpty()) {
            if (!start.result().startedCheckpoint().equals(current)) {
                return null;
            }
        } else {
            InMemoryState.ExecutionMutationLink tip =
                    links.get(links.size() - 1);
            InMemoryState.StepActivationMarker tipMarker =
                    activationMarkers.get(tip.markerIdentity().eventId());
            if (tipMarker == null
                    || !tipMarker.result().activatedCheckpoint().equals(current)) {
                return null;
            }
        }
        if (!currentHead.revisionId().equals(latest.id())
                || currentHead.revisionNumber() != latest.number()
                || currentHead.checkpointVersion() != current.version()
                || currentHead.eventHeadSequence() != stream.lastKey()
                || !currentHead.mutationEventId().equals(headEvent.id())
                || !checkpoint.revisionId().equals(latest.id())
                || checkpoint.revisionNumber() != latest.number()
                || checkpoint.lastEventSequence() != stream.lastKey()
                || checkpoint.lastEventSequence() == 0
                || current.version() < 2
                || !checkpoint.planId().equals(planId)
                || !checkpoint.taskFrameId().equals(taskFrame.id())
                || !hasCoherentStepAndFactShape(checkpoint, latest)
                || !CheckpointValidators.validate(
                                checkpoint,
                                taskFrame,
                                plan,
                                start.result().startedCheckpoint().checkpoint())
                        .isEmpty()
                || !referencedReceiptsExist(state, checkpoint, latest)) {
            return null;
        }
        return new AuthoritativeSource(
                taskFrame,
                plan,
                current,
                stream.lastKey(),
                currentHead,
                stream,
                links,
                activationMarkers);
    }

    static InMemoryState.ExecutionMutationHead headFromStart(
            PersistedExecutionStart start) {
        Checkpoint checkpoint = start.startedCheckpoint().checkpoint();
        return new InMemoryState.ExecutionMutationHead(
                checkpoint.revisionId(),
                checkpoint.revisionNumber(),
                start.startedCheckpoint().version(),
                start.startEvent().sequence(),
                start.startEvent().id());
    }

    private static boolean hasCanonicalBootstrapRoot(
            PlanId planId,
            TaskFrame taskFrame,
            Plan currentPlan,
            PersistedPlanBootstrap bootstrap) {
        if (taskFrame == null
                || !taskFrame.equals(
                        bootstrap == null
                                ? null
                                : bootstrap.taskFrame())
                || !taskFrame.id().equals(currentPlan.taskFrameId())
                || !planId.equals(currentPlan.id())
                || !planId.equals(bootstrap.plan().id())
                || !taskFrame.id().equals(bootstrap.plan().taskFrameId())
                || !isExactPrefix(
                        bootstrap.plan().revisions(),
                        currentPlan.revisions())) {
            return false;
        }
        VersionedCheckpoint initial = bootstrap.initialCheckpoint();
        PlanRevision revision = bootstrap.plan().latestRevision();
        Checkpoint checkpoint = initial.checkpoint();
        return initial.version() == 1
                && checkpoint.taskFrameId().equals(taskFrame.id())
                && checkpoint.planId().equals(planId)
                && matchesRevision(revision, checkpoint)
                && checkpoint.lastEventSequence() == 0
                && checkpoint.planState() == PlanExecutionState.NOT_STARTED
                && hasExactStepShape(
                        checkpoint, revision, StepExecutionState.NOT_STARTED)
                && checkpoint.receiptReferences().isEmpty()
                && revision.completedFacts().isEmpty()
                && CheckpointValidators.validate(
                                checkpoint,
                                taskFrame,
                                bootstrap.plan(),
                                null)
                        .isEmpty();
    }

    private static boolean hasCanonicalStart(
            PlanId planId,
            TaskFrame taskFrame,
            Plan currentPlan,
            PersistedPlanBootstrap bootstrap,
            InMemoryState.ExecutionStartMarker marker) {
        if (marker.request() == null || marker.result() == null) {
            return false;
        }
        ExecutionStartRequest request = marker.request();
        PersistedExecutionStart result = marker.result();
        EventEnvelope event = result.startEvent();
        VersionedCheckpoint started = result.startedCheckpoint();
        Checkpoint checkpoint = started.checkpoint();
        PlanRevision revision = findRevision(
                currentPlan, checkpoint.revisionNumber());
        Plan planAtStart = revision == null
                ? null
                : planEndingAt(currentPlan, revision);
        return request.planId().equals(planId)
                && result.planId().equals(planId)
                && request.fencingToken() == result.fencingToken()
                && request.startEvent().equals(event)
                && request.startedCheckpoint().equals(checkpoint)
                && started.version() == 2
                && event.planId().equals(planId)
                && event.taskFrameId().equals(taskFrame.id())
                && event.sequence() == 1
                && checkpoint.planId().equals(planId)
                && checkpoint.taskFrameId().equals(taskFrame.id())
                && checkpoint.lastEventSequence() == 1
                && checkpoint.planState() == PlanExecutionState.ACTIVE
                && !checkpoint.createdAt().isBefore(
                        bootstrap.initialCheckpoint().checkpoint().createdAt())
                && isNotBeforeBootstrap(
                        checkpoint,
                        bootstrap.initialCheckpoint().checkpoint())
                && revision != null
                && matchesRevision(revision, checkpoint)
                && revision.completedFacts().isEmpty()
                && hasExactStepShape(
                        checkpoint,
                        revision,
                        StepExecutionState.NOT_STARTED)
                && checkpoint.receiptReferences().isEmpty()
                && planAtStart != null
                && CheckpointValidators.validate(
                                checkpoint,
                                taskFrame,
                                planAtStart,
                                bootstrap.initialCheckpoint().checkpoint())
                        .isEmpty();
    }

    private static boolean isNotBeforeBootstrap(
            Checkpoint started,
            Checkpoint bootstrap) {
        return started.revisionNumber() > bootstrap.revisionNumber()
                || started.revisionNumber() == bootstrap.revisionNumber()
                        && started.revisionId().equals(bootstrap.revisionId());
    }

    private static boolean hasConsistentEventProjection(
            InMemoryState state,
            PlanId planId,
            TaskFrame taskFrame,
            NavigableMap<Long, EventEnvelope> stream) {
        Set<EventId> ids = new HashSet<>();
        long previous = 0;
        for (Map.Entry<Long, EventEnvelope> entry : stream.entrySet()) {
            Long sequence = entry.getKey();
            EventEnvelope event = entry.getValue();
            if (sequence == null
                    || event == null
                    || sequence != event.sequence()
                    || sequence <= previous
                    || !event.planId().equals(planId)
                    || !event.taskFrameId().equals(taskFrame.id())
                    || !ids.add(event.id())
                    || !event.equals(state.eventsById.get(event.id()))) {
                return false;
            }
            previous = sequence;
        }
        int indexedForPlan = 0;
        for (Map.Entry<EventId, EventEnvelope> indexed :
                state.eventsById.entrySet()) {
            EventEnvelope event = indexed.getValue();
            if (event != null && planId.equals(event.planId())) {
                indexedForPlan++;
                if (!event.id().equals(indexed.getKey())
                        || !event.equals(stream.get(event.sequence()))) {
                    return false;
                }
            }
        }
        return indexedForPlan == stream.size();
    }

    private static boolean isValidChain(
            PlanId planId,
            InMemoryState.ExecutionMutationHead root,
            InMemoryState.ExecutionMutationHead currentHead,
            List<InMemoryState.ExecutionMutationLink> links,
            Map<EventId, InMemoryState.StepActivationMarker> markers,
            NavigableMap<Long, EventEnvelope> stream) {
        if (links.size() != markers.size()) {
            return false;
        }
        InMemoryState.ExecutionMutationHead previous = root;
        Set<EventId> visited = new HashSet<>();
        for (InMemoryState.ExecutionMutationLink link : links) {
            if (!isCompleteLink(link)
                    || !link.previousHead().equals(previous)
                    || !"STEP_ACTIVATION".equals(
                            link.markerIdentity().operationType())
                    || !visited.add(link.markerIdentity().eventId())
                    || link.resultHead().checkpointVersion()
                            != previous.checkpointVersion() + 1
                    || link.resultHead().eventHeadSequence()
                            <= previous.eventHeadSequence()) {
                return false;
            }
            InMemoryState.StepActivationMarker marker =
                    markers.get(link.markerIdentity().eventId());
            if (!isMarkerBackedLink(planId, marker, link)
                    || !marker.result().activationEvent().equals(
                            stream.get(
                                    marker.result()
                                            .activationEvent()
                                            .sequence()))) {
                return false;
            }
            previous = link.resultHead();
        }
        Set<EventId> successorEvents = stream.values().stream()
                .filter(event -> event.sequence() != 1)
                .map(EventEnvelope::id)
                .collect(Collectors.toSet());
        return visited.equals(markers.keySet())
                && stream.size() == links.size() + 1
                && successorEvents.equals(visited)
                && previous.equals(currentHead);
    }

    private static boolean isMarkerBackedLink(
            PlanId planId,
            InMemoryState.StepActivationMarker marker,
            InMemoryState.ExecutionMutationLink link) {
        if (marker == null
                || marker.request() == null
                || marker.result() == null
                || marker.provenanceLink() == null
                || !isCompleteLink(link)
                || !marker.provenanceLink().equals(link)) {
            return false;
        }
        StepActivationRequest request = marker.request();
        PersistedStepActivation result = marker.result();
        Checkpoint target = request.activatedCheckpoint();
        VersionedCheckpoint persisted = result.activatedCheckpoint();
        InMemoryState.ExecutionMutationHead previous = link.previousHead();
        InMemoryState.ExecutionMutationHead resultHead = link.resultHead();
        return request.planId().equals(planId)
                && result.planId().equals(planId)
                && request.activationEvent().id().equals(
                        link.markerIdentity().eventId())
                && request.activationEvent().equals(result.activationEvent())
                && request.stepId().equals(result.stepId())
                && request.fencingToken() == result.fencingToken()
                && request.expectedRevisionId().equals(previous.revisionId())
                && request.expectedRevisionNumber()
                        == previous.revisionNumber()
                && request.expectedCheckpointVersion()
                        == previous.checkpointVersion()
                && request.expectedEventHeadSequence()
                        == previous.eventHeadSequence()
                && persisted.version()
                        == request.expectedCheckpointVersion() + 1
                && persisted.checkpoint().equals(target)
                && resultHead.revisionId().equals(previous.revisionId())
                && resultHead.revisionNumber() == previous.revisionNumber()
                && resultHead.revisionId().equals(target.revisionId())
                && resultHead.revisionNumber() == target.revisionNumber()
                && resultHead.checkpointVersion() == persisted.version()
                && resultHead.eventHeadSequence()
                        == request.activationEvent().sequence()
                && resultHead.mutationEventId().equals(
                        request.activationEvent().id());
    }

    private static boolean isCompleteLink(
            InMemoryState.ExecutionMutationLink link) {
        return link != null
                && isCompleteHead(link.previousHead())
                && isCompleteHead(link.resultHead())
                && link.markerIdentity() != null
                && link.markerIdentity().operationType() != null
                && link.markerIdentity().eventId() != null;
    }

    private static boolean isCompleteHead(
            InMemoryState.ExecutionMutationHead head) {
        return head != null
                && head.revisionId() != null
                && head.revisionNumber() > 0
                && head.checkpointVersion() > 0
                && head.eventHeadSequence() > 0
                && head.mutationEventId() != null;
    }

    private static boolean isSelfConsistentMarker(
            PlanId planId,
            EventId markerKey,
            InMemoryState.StepActivationMarker marker) {
        if (marker == null
                || marker.request() == null
                || marker.result() == null
                || marker.provenanceLink() == null
                || marker.provenanceLink().markerIdentity() == null) {
            return false;
        }
        return "STEP_ACTIVATION".equals(
                        marker.provenanceLink()
                                .markerIdentity()
                                .operationType())
                && markerKey.equals(
                        marker.provenanceLink()
                                .markerIdentity()
                                .eventId())
                && markerKey.equals(
                        marker.request().activationEvent().id())
                && isMarkerBackedLink(
                        planId, marker, marker.provenanceLink());
    }

    private static boolean referencedReceiptsExist(
            InMemoryState state,
            Checkpoint checkpoint,
            PlanRevision revision) {
        for (var receiptId : checkpoint.receiptReferences()) {
            if (!state.receipts.containsKey(receiptId)) {
                return false;
            }
        }
        for (CompletionFact fact : revision.completedFacts().values()) {
            for (var receiptId : fact.receiptReferences()) {
                if (!state.receipts.containsKey(receiptId)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean hasCoherentStepAndFactShape(
            Checkpoint checkpoint,
            PlanRevision revision) {
        Set<PlanStepId> stepIds = revision.steps().stream()
                .map(PlanStep::id)
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
                        .allMatch(value ->
                                value == StepExecutionState.SUCCEEDED);
    }

    static boolean isEligible(
            Plan plan,
            Checkpoint checkpoint,
            PlanStepId targetId) {
        PlanRevision revision = plan.latestRevision();
        PlanStep target = revision.steps().stream()
                .filter(step -> step.id().equals(targetId))
                .findFirst()
                .orElse(null);
        if (checkpoint.planState() != PlanExecutionState.ACTIVE
                || target == null
                || checkpoint.stepStates().get(targetId)
                        != StepExecutionState.NOT_STARTED
                || revision.completedFacts().containsKey(targetId)) {
            return false;
        }
        for (PlanStepId dependency : target.dependencies()) {
            if (checkpoint.stepStates().get(dependency)
                            != StepExecutionState.SUCCEEDED
                    || !revision.completedFacts().containsKey(dependency)) {
                return false;
            }
        }
        for (Map.Entry<PlanStepId, StepExecutionState> entry :
                checkpoint.stepStates().entrySet()) {
            StepExecutionState value = entry.getValue();
            if (!entry.getKey().equals(targetId)
                            && (value == StepExecutionState.ACTIVE
                                    || value == StepExecutionState.PAUSED)
                    || value == StepExecutionState.FAILED
                    || value == StepExecutionState.CANCELLED) {
                return false;
            }
        }
        return true;
    }

    private static PersistenceResult<PersistedStepActivation> validateTarget(
            StepActivationRequest request,
            AuthoritativeSource source) {
        Checkpoint current = source.checkpoint().checkpoint();
        Checkpoint target = request.activatedCheckpoint();
        if (target.lastEventSequence()
                != request.activationEvent().sequence()) {
            return PersistenceResult.rejected(
                    PersistenceErrorCode.CHECKPOINT_VALIDATION_FAILED,
                    "request.activatedCheckpoint.lastEventSequence");
        }
        if (!target.taskFrameId().equals(current.taskFrameId())
                || !target.planId().equals(current.planId())
                || !target.revisionId().equals(current.revisionId())
                || target.revisionNumber() != current.revisionNumber()
                || target.planState() != PlanExecutionState.ACTIVE
                || !target.receiptReferences().equals(
                        current.receiptReferences())
                || !target.stepStates().keySet().equals(
                        current.stepStates().keySet())
                || !hasOnlyTargetActivation(
                        current, target, request.stepId())
                || !CheckpointValidators.validate(
                                target,
                                source.taskFrame(),
                                source.plan(),
                                current)
                        .isEmpty()) {
            return PersistenceResult.rejected(
                    PersistenceErrorCode.CHECKPOINT_VALIDATION_FAILED,
                    "request.activatedCheckpoint");
        }
        return null;
    }

    private static boolean hasOnlyTargetActivation(
            Checkpoint current,
            Checkpoint target,
            PlanStepId targetId) {
        for (Map.Entry<PlanStepId, StepExecutionState> entry :
                current.stepStates().entrySet()) {
            StepExecutionState expected = entry.getKey().equals(targetId)
                    ? StepExecutionState.ACTIVE
                    : entry.getValue();
            if (target.stepStates().get(entry.getKey()) != expected) {
                return false;
            }
        }
        return true;
    }

    static boolean hasPlanScopedOccupancy(
            InMemoryState state,
            PlanId planId) {
        return state.plans.containsKey(planId)
                || state.planBootstraps.containsKey(planId)
                || state.checkpoints.containsKey(planId)
                || state.eventStreams.containsKey(planId)
                || state.eventsById.values().stream()
                        .anyMatch(event ->
                                event != null && planId.equals(event.planId()))
                || state.executionStarts.containsKey(planId)
                || state.executionMutationHeads.containsKey(planId)
                || state.executionMutationLinks.containsKey(planId)
                || state.stepActivations.containsKey(planId)
                || state.leases.containsKey(planId)
                || state.fencingTokens.containsKey(planId);
    }

    private static PlanRevision findRevision(Plan plan, long number) {
        return plan.revisions().stream()
                .filter(revision -> revision.number() == number)
                .findFirst()
                .orElse(null);
    }

    private static Plan planEndingAt(
            Plan plan,
            PlanRevision revision) {
        int index = plan.revisions().indexOf(revision);
        return index < 0
                ? null
                : new Plan(
                        plan.id(),
                        plan.taskFrameId(),
                        plan.revisions().subList(0, index + 1));
    }

    private static boolean matchesRevision(
            PlanRevision revision,
            Checkpoint checkpoint) {
        return revision.id().equals(checkpoint.revisionId())
                && revision.number() == checkpoint.revisionNumber();
    }

    private static boolean isExactPrefix(List<?> prefix, List<?> values) {
        return values.size() >= prefix.size()
                && values.subList(0, prefix.size()).equals(prefix);
    }

    private static boolean hasExactStepShape(
            Checkpoint checkpoint,
            PlanRevision revision,
            StepExecutionState expected) {
        Set<PlanStepId> stepIds = revision.steps().stream()
                .map(PlanStep::id)
                .collect(Collectors.toSet());
        return checkpoint.stepStates().keySet().equals(stepIds)
                && checkpoint.stepStates().values().stream()
                        .allMatch(value -> value == expected);
    }

    private static PersistenceResult<PersistedStepActivation> partialState() {
        return PersistenceResult.rejected(
                PersistenceErrorCode.STEP_ACTIVATION_PARTIAL_STATE,
                PARTIAL_PATH);
    }

    record AuthoritativeSource(
            TaskFrame taskFrame,
            Plan plan,
            VersionedCheckpoint checkpoint,
            long eventHeadSequence,
            InMemoryState.ExecutionMutationHead head,
            NavigableMap<Long, EventEnvelope> eventStream,
            List<InMemoryState.ExecutionMutationLink> links,
            Map<EventId, InMemoryState.StepActivationMarker>
                    activationMarkers) {

        @Override
        public String toString() {
            return "AuthoritativeSource["
                    + "taskFrame=<provided>, "
                    + "plan=<provided>, "
                    + "checkpoint=<provided>, "
                    + "eventHeadSequence=<provided>, "
                    + "head=<provided>, "
                    + "eventStream=<provided>, "
                    + "links=<provided>, "
                    + "activationMarkers=<provided>]";
        }
    }
}
