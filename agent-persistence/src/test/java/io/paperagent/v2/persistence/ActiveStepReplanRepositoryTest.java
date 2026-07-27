package io.paperagent.v2.persistence;

import io.paperagent.v2.contracts.Checkpoint;
import io.paperagent.v2.contracts.EffectIntent;
import io.paperagent.v2.contracts.EffectProgress;
import io.paperagent.v2.contracts.EffectProgressId;
import io.paperagent.v2.contracts.EventId;
import io.paperagent.v2.contracts.ObjectValue;
import io.paperagent.v2.contracts.Plan;
import io.paperagent.v2.contracts.PlanExecutionState;
import io.paperagent.v2.contracts.PlanRevision;
import io.paperagent.v2.contracts.PlanRevisionId;
import io.paperagent.v2.contracts.PlanStep;
import io.paperagent.v2.contracts.PlanStepId;
import io.paperagent.v2.contracts.StepExecutionState;
import io.paperagent.v2.contracts.TextValue;
import io.paperagent.v2.contracts.ToolCallId;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActiveStepReplanRepositoryTest {

    @Test
    void activeStepReplanAtomicallyRecordsTwoFactsAndLeavesOrdinaryReplanUnsafe() {
        ActiveStepReplanTestSupport.Harness harness =
                ActiveStepReplanTestSupport.active("applied");
        ActiveStepReplanRequest request =
                ActiveStepReplanTestSupport.request(harness, "applied");
        ActiveStepReplanTestSupport.Snapshot before =
                ActiveStepReplanTestSupport.snapshot(harness.state());

        PersistedActiveStepReplan result = ActiveStepReplanTestSupport.requireApplied(
                harness.activeReplans().supersedeAndReplan(request));

        assertEquals(PersistenceFixtures.STEP_1, result.supersededStepId());
        assertEquals(4, result.supersededCheckpoint().version());
        assertEquals(5, result.replannedCheckpoint().version());
        assertEquals(request.supersessionEvent(), result.supersessionEvent());
        assertEquals(request.replanEvent(), result.replanEvent());
        assertEquals(StepExecutionState.SUPERSEDED_BY_REPLAN,
                result.supersededCheckpoint().checkpoint().stepStates()
                        .get(PersistenceFixtures.STEP_1));
        assertEquals(2, harness.state().plans.get(harness.plan().id()).revisions().size());
        assertEquals(4, harness.state().eventStreams.get(harness.plan().id()).size());
        assertEquals(3, harness.state().executionMutationLinks.get(harness.plan().id()).size());
        assertEquals(1, harness.state().activeStepReplans.get(harness.plan().id()).size());
        assertEquals(5, harness.state().checkpoints.get(harness.plan().id()).version());
        assertTrue(result.replannedRevision().steps().stream().noneMatch(step ->
                step.id().equals(PersistenceFixtures.STEP_1)));
        assertTrue(InMemoryExecutionMutationAuthority.validateAuthoritativeSource(
                harness.state(), harness.plan().id()) != null);
        ActiveStepReplanTestSupport.assertOnlyCompositeWrites(before, harness.state());

        ActiveStepReplanTestSupport.Harness ordinary =
                ActiveStepReplanTestSupport.active("ordinary");
        Plan source = ordinary.state().plans.get(ordinary.plan().id());
        Checkpoint activeCheckpoint = ordinary.state().checkpoints.get(source.id()).checkpoint();
        PlanRevision ordinaryRevision = new PlanRevision(
                new PlanRevisionId("ordinary-active-replan"), source.taskFrameId(), 2,
                Optional.of(source.latestRevision().id()), "ordinary active replan",
                activeCheckpoint.createdAt().plusSeconds(1),
                source.latestRevision().steps(), source.latestRevision().completedFacts());
        PlanReplanRequest ordinaryRequest = new PlanReplanRequest(
                source.id(), ActiveStepReplanTestSupport.TOKEN, 1,
                source.latestRevision().id(), 1, 3, 2,
                PersistenceFixtures.event("ordinary-active-event", source.taskFrameId(),
                        source.id(), 3), ordinaryRevision,
                ActiveStepReplanTestSupport.replannedCheckpoint(
                        source, ordinaryRevision, 3, activeCheckpoint.createdAt().plusSeconds(1)));
        ActiveStepReplanTestSupport.assertFailure(
                ordinary.replans().replan(ordinaryRequest),
                PersistenceErrorCode.PLAN_REPLAN_NOT_ELIGIBLE, "planReplan");
    }

    @Test
    void exactReplayUsesBothLinksWithoutClockOrMutableProjections() {
        ActiveStepReplanTestSupport.Harness harness =
                ActiveStepReplanTestSupport.active("replay");
        ActiveStepReplanRequest request =
                ActiveStepReplanTestSupport.request(harness, "replay");
        PersistedActiveStepReplan applied = ActiveStepReplanTestSupport.requireApplied(
                harness.activeReplans().supersedeAndReplan(request));
        harness.state().plans.clear();
        harness.state().checkpoints.clear();
        harness.state().leases.clear();
        harness.clock().failOnObservation();

        assertEquals(applied, ActiveStepReplanTestSupport.requireReplayed(
                harness.activeReplans().supersedeAndReplan(request)));
    }

    @Test
    void selectedStepEffectIntentProgressAndResultRejectWithoutAbandoningThem() {
        ActiveStepReplanTestSupport.Harness harness =
                ActiveStepReplanTestSupport.active("effect");
        EffectIntent intent = new EffectIntent(
                new ToolCallId("effect-active-step"), harness.plan().id(),
                PersistenceFixtures.STEP_1, "workspace.edit",
                new ObjectValue(Map.of("input", new TextValue("bounded"))));
        ActiveStepReplanTestSupport.requireApplied(harness.effectIntents().persist(
                new EffectIntentRequest(intent, ActiveStepReplanTestSupport.TOKEN, 1,
                        harness.activationEventId())));
        ActiveStepReplanTestSupport.requireApplied(harness.effectOutcomes().appendProgress(
                new EffectProgressRequest(new EffectProgress(
                        new EffectProgressId("effect-active-step-progress"),
                        intent.toolCallId(), 1, PersistenceFixtures.T0.plusSeconds(3),
                        new ObjectValue(Map.of("progress", new TextValue("started")))),
                        ActiveStepReplanTestSupport.TOKEN, 1)));
        ActiveStepReplanTestSupport.requireApplied(harness.effectOutcomes().recordResult(
                new EffectResultRequest(PersistenceFixtures.receipt(
                        "effect-active-step-receipt", intent.toolCallId().value()),
                        ActiveStepReplanTestSupport.TOKEN, 1)));
        ActiveStepReplanTestSupport.Snapshot before =
                ActiveStepReplanTestSupport.snapshot(harness.state());

        ActiveStepReplanTestSupport.assertFailure(
                harness.activeReplans().supersedeAndReplan(
                        ActiveStepReplanTestSupport.request(harness, "effect")),
                PersistenceErrorCode.ACTIVE_STEP_REPLAN_NOT_ELIGIBLE,
                "activeStepReplan.source");
        assertEquals(before, ActiveStepReplanTestSupport.snapshot(harness.state()));
        assertEquals(1, harness.state().effectIntents.size());
        assertEquals(1, harness.state().effectProgresses.size());
        assertEquals(1, harness.state().effectResults.size());
    }

    @Test
    void malformedCandidateAndSelectedStepMismatchLeaveBusinessStateUntouched() {
        ActiveStepReplanTestSupport.Harness malformed =
                ActiveStepReplanTestSupport.active("malformed");
        ActiveStepReplanRequest request =
                ActiveStepReplanTestSupport.request(malformed, "malformed");
        Checkpoint wrongSuperseded = new Checkpoint(
                request.supersededCheckpoint().taskFrameId(),
                request.supersededCheckpoint().planId(),
                request.supersededCheckpoint().revisionId(),
                request.supersededCheckpoint().revisionNumber(),
                request.supersededCheckpoint().lastEventSequence(),
                PlanExecutionState.ACTIVE,
                Map.of(PersistenceFixtures.STEP_1, StepExecutionState.ACTIVE,
                        PersistenceFixtures.STEP_2, StepExecutionState.NOT_STARTED),
                request.supersededCheckpoint().receiptReferences(),
                request.supersededCheckpoint().createdAt());
        ActiveStepReplanRequest malformedRequest = new ActiveStepReplanRequest(
                request.planId(), request.leaseToken(), request.fencingToken(),
                request.expectedRevisionId(), request.expectedRevisionNumber(),
                request.expectedCheckpointVersion(), request.expectedEventHeadSequence(),
                request.activeStepId(), request.supersessionEvent(), wrongSuperseded,
                request.replanEvent(), request.replannedRevision(), request.replannedCheckpoint());
        ActiveStepReplanTestSupport.Snapshot before =
                ActiveStepReplanTestSupport.snapshot(malformed.state());
        ActiveStepReplanTestSupport.assertFailure(
                malformed.activeReplans().supersedeAndReplan(malformedRequest),
                PersistenceErrorCode.CHECKPOINT_VALIDATION_FAILED,
                "request.supersededCheckpoint");
        assertEquals(before, ActiveStepReplanTestSupport.snapshot(malformed.state()));

        ActiveStepReplanTestSupport.Harness wrongStep =
                ActiveStepReplanTestSupport.active("wrong-step");
        ActiveStepReplanRequest wrongStepRequest =
                ActiveStepReplanTestSupport.request(wrongStep, "wrong-step", PersistenceFixtures.STEP_2);
        ActiveStepReplanTestSupport.assertFailure(
                wrongStep.activeReplans().supersedeAndReplan(wrongStepRequest),
                PersistenceErrorCode.ACTIVE_STEP_REPLAN_NOT_ELIGIBLE,
                "activeStepReplan.source");
    }
}

final class ActiveStepReplanTestSupport {
    static final String OWNER = "active-replan-owner";
    static final String TOKEN = "active-replan-token";

    private ActiveStepReplanTestSupport() {
    }

    static Harness active(String suffix) {
        PersistenceFixtures.MutableCountingClock clock =
                new PersistenceFixtures.MutableCountingClock(PersistenceFixtures.T0);
        InMemoryState state = new InMemoryState(clock);
        Plan plan = PersistenceFixtures.plan();
        requireApplied(new InMemoryPlanBootstrapRepository(state).bootstrap(
                PersistenceFixtures.taskFrame(), plan,
                PersistenceFixtures.initialCheckpoint(plan)));
        InMemoryLeaseRepository leases = new InMemoryLeaseRepository(state);
        LeaseRecord lease = requireApplied(leases.acquire(
                plan.id(), OWNER, TOKEN,
                PersistenceFixtures.T0.plus(Duration.ofMinutes(1))));
        requireApplied(new InMemoryExecutionStartRepository(state).start(
                PersistenceFixtures.executionStartRequest(
                        plan, TOKEN, lease.fencingToken(), "active-replan-start-" + suffix)));
        PersistenceFixtures.confirmExecutionContext(
                new InMemoryPlanExecutionContextRepository(state), plan, TOKEN,
                lease.fencingToken(), PersistenceFixtures.workspaceSpec("active-replan-" + suffix));
        EventId activationEventId = new EventId("active-replan-activation-" + suffix);
        requireApplied(new InMemoryStepActivationRepository(state).activate(
                PersistenceFixtures.stepActivationRequest(
                        plan,
                        state.checkpoints.get(plan.id()).checkpoint(),
                        2, 1, TOKEN, lease.fencingToken(), PersistenceFixtures.STEP_1,
                        activationEventId.value(), 2)));
        return new Harness(state, clock, plan, leases,
                new InMemoryPlanReplanRepository(state),
                new InMemoryActiveStepReplanRepository(state),
                new InMemoryEffectIntentRepository(state),
                new InMemoryEffectOutcomeRepository(state), activationEventId);
    }

    static ActiveStepReplanRequest request(Harness harness, String suffix) {
        return request(harness, suffix, PersistenceFixtures.STEP_1);
    }

    static ActiveStepReplanRequest request(
            Harness harness,
            String suffix,
            PlanStepId activeStepId) {
        Plan source = harness.state().plans.get(harness.plan().id());
        Checkpoint current = harness.state().checkpoints.get(source.id()).checkpoint();
        long supersessionSequence = current.lastEventSequence() + 1;
        long replanSequence = supersessionSequence + 1;
        Map<PlanStepId, StepExecutionState> supersededStates =
                new LinkedHashMap<>(current.stepStates());
        supersededStates.put(activeStepId, StepExecutionState.SUPERSEDED_BY_REPLAN);
        Checkpoint superseded = new Checkpoint(
                source.taskFrameId(), source.id(), current.revisionId(),
                current.revisionNumber(), supersessionSequence, PlanExecutionState.ACTIVE,
                supersededStates, current.receiptReferences(),
                current.createdAt().plusSeconds(1));
        PlanStep replacement = PersistenceFixtures.step(
                new PlanStepId("replacement-" + suffix), Set.of());
        PlanRevision replanned = new PlanRevision(
                new PlanRevisionId("active-replan-revision-" + suffix),
                source.taskFrameId(), source.latestRevision().number() + 1,
                Optional.of(source.latestRevision().id()), "active replan " + suffix,
                current.createdAt().plusSeconds(2), List.of(replacement),
                source.latestRevision().completedFacts());
        Checkpoint replannedCheckpoint = replannedCheckpoint(
                source, replanned, replanSequence, current.createdAt().plusSeconds(2));
        return new ActiveStepReplanRequest(
                source.id(), TOKEN, 1, source.latestRevision().id(),
                source.latestRevision().number(),
                harness.state().checkpoints.get(source.id()).version(),
                current.lastEventSequence(), activeStepId,
                PersistenceFixtures.event("active-replan-supersession-" + suffix,
                        source.taskFrameId(), source.id(), supersessionSequence),
                superseded,
                PersistenceFixtures.event("active-replan-event-" + suffix,
                        source.taskFrameId(), source.id(), replanSequence),
                replanned, replannedCheckpoint);
    }

    static Checkpoint replannedCheckpoint(
            Plan source,
            PlanRevision revision,
            long eventSequence,
            java.time.Instant createdAt) {
        Map<PlanStepId, StepExecutionState> states = new LinkedHashMap<>();
        revision.steps().forEach(step -> states.put(step.id(),
                revision.completedFacts().containsKey(step.id())
                        ? StepExecutionState.SUCCEEDED
                        : StepExecutionState.NOT_STARTED));
        return new Checkpoint(
                source.taskFrameId(), source.id(), revision.id(), revision.number(),
                eventSequence, PlanExecutionState.ACTIVE, states, List.of(), createdAt);
    }

    static Snapshot snapshot(InMemoryState state) {
        return new Snapshot(
                new LinkedHashMap<>(state.plans),
                new LinkedHashMap<>(state.eventsById),
                new LinkedHashMap<>(state.eventStreams),
                new LinkedHashMap<>(state.checkpoints),
                new LinkedHashMap<>(state.activeStepReplans),
                new LinkedHashMap<>(state.executionMutationHeads),
                new LinkedHashMap<>(state.executionMutationLinks),
                new LinkedHashMap<>(state.effectIntents));
    }

    static void assertOnlyCompositeWrites(Snapshot before, InMemoryState state) {
        assertEquals(before.plans().size() + 1,
                state.plans.get(PersistenceFixtures.PLAN_ID).revisions().size());
        assertEquals(before.eventsById().size() + 2, state.eventsById.size());
        assertEquals(before.activeReplans().size() + 1, state.activeStepReplans.size());
        assertEquals(before.effectIntents(), state.effectIntents);
    }

    static void assertFailure(
            PersistenceResult<?> result,
            PersistenceErrorCode code,
            String path) {
        assertEquals(PersistenceOutcome.REJECTED, result.outcome(), result.toString());
        assertEquals(code, result.failure().orElseThrow().code());
        assertEquals(path, result.failure().orElseThrow().path());
        assertTrue(result.value().isEmpty());
    }

    static <T> T requireApplied(PersistenceResult<T> result) {
        assertEquals(PersistenceOutcome.APPLIED, result.outcome(), result.toString());
        return result.value().orElseThrow();
    }

    static <T> T requireReplayed(PersistenceResult<T> result) {
        assertEquals(PersistenceOutcome.REPLAYED, result.outcome(), result.toString());
        return result.value().orElseThrow();
    }

    record Harness(
            InMemoryState state,
            PersistenceFixtures.MutableCountingClock clock,
            Plan plan,
            InMemoryLeaseRepository leases,
            PlanReplanRepository replans,
            ActiveStepReplanRepository activeReplans,
            EffectIntentRepository effectIntents,
            EffectOutcomeRepository effectOutcomes,
            EventId activationEventId) {
    }

    record Snapshot(
            Map<?, ?> plans,
            Map<?, ?> eventsById,
            Map<?, ?> eventStreams,
            Map<?, ?> checkpoints,
            Map<?, ?> activeReplans,
            Map<?, ?> heads,
            Map<?, ?> links,
            Map<?, ?> effectIntents) {
    }
}
