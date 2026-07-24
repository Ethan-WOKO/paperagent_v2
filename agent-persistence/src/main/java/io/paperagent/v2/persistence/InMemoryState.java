package io.paperagent.v2.persistence;

import io.paperagent.v2.contracts.EventEnvelope;
import io.paperagent.v2.contracts.EventId;
import io.paperagent.v2.contracts.ExecutionReceipt;
import io.paperagent.v2.contracts.Plan;
import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.PlanRevisionId;
import io.paperagent.v2.contracts.ReceiptId;
import io.paperagent.v2.contracts.TaskFrame;
import io.paperagent.v2.contracts.TaskFrameId;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;

final class InMemoryState {
    final Clock leaseClock;
    final Object monitor = new Object();
    final Map<TaskFrameId, TaskFrame> taskFrames = new LinkedHashMap<>();
    final Map<PlanId, Plan> plans = new LinkedHashMap<>();
    final Map<EventId, EventEnvelope> eventsById = new LinkedHashMap<>();
    final Map<PlanId, NavigableMap<Long, EventEnvelope>> eventStreams =
            new HashMap<>();
    final Map<ReceiptId, ExecutionReceipt> receipts = new LinkedHashMap<>();
    final Map<PlanId, VersionedCheckpoint> checkpoints = new LinkedHashMap<>();
    final Map<PlanId, PersistedPlanBootstrap> planBootstraps = new LinkedHashMap<>();
    final Map<PlanId, ExecutionStartMarker> executionStarts = new LinkedHashMap<>();
    final Map<PlanId, ExecutionMutationHead> executionMutationHeads =
            new LinkedHashMap<>();
    final Map<PlanId, List<ExecutionMutationLink>> executionMutationLinks =
            new LinkedHashMap<>();
    final Map<PlanId, Map<EventId, StepActivationMarker>> stepActivations =
            new LinkedHashMap<>();
    final Map<PlanId, LeaseRecord> leases = new HashMap<>();
    final Map<PlanId, Long> fencingTokens = new HashMap<>();
    final Set<String> usedLeaseTokens = new HashSet<>();
    final Map<IdempotencyKey, IdempotencyRecord> idempotency = new LinkedHashMap<>();
    Instant leaseTimeHighWater;

    InMemoryState(Clock leaseClock) {
        this.leaseClock = leaseClock;
    }

    Instant observeLeaseTime() {
        Instant rawNow = leaseClock.instant();
        Instant effectiveNow =
                leaseTimeHighWater == null || rawNow.isAfter(leaseTimeHighWater)
                        ? rawNow
                        : leaseTimeHighWater;
        leaseTimeHighWater = effectiveNow;
        return effectiveNow;
    }

    record ExecutionStartMarker(
            ExecutionStartRequest request,
            PersistedExecutionStart result) {

        @Override
        public String toString() {
            return "ExecutionStartMarker[request=<provided>, result=<provided>]";
        }
    }

    record ExecutionMutationHead(
            PlanRevisionId revisionId,
            long revisionNumber,
            long checkpointVersion,
            long eventHeadSequence,
            EventId mutationEventId) {

        @Override
        public String toString() {
            return "ExecutionMutationHead["
                    + "revisionId=<provided>, "
                    + "revisionNumber=<provided>, "
                    + "checkpointVersion=<provided>, "
                    + "eventHeadSequence=<provided>, "
                    + "mutationEventId=<provided>]";
        }
    }

    record ExecutionMutationMarkerIdentity(
            String operationType,
            EventId eventId) {

        static ExecutionMutationMarkerIdentity stepActivation(EventId eventId) {
            return new ExecutionMutationMarkerIdentity(
                    "STEP_ACTIVATION", eventId);
        }

        @Override
        public String toString() {
            return "ExecutionMutationMarkerIdentity["
                    + "operationType=<provided>, eventId=<provided>]";
        }
    }

    record ExecutionMutationLink(
            ExecutionMutationHead previousHead,
            ExecutionMutationHead resultHead,
            ExecutionMutationMarkerIdentity markerIdentity) {

        @Override
        public String toString() {
            return "ExecutionMutationLink["
                    + "previousHead=<provided>, "
                    + "resultHead=<provided>, "
                    + "markerIdentity=<provided>]";
        }
    }

    record StepActivationMarker(
            StepActivationRequest request,
            PersistedStepActivation result,
            ExecutionMutationLink provenanceLink) {

        @Override
        public String toString() {
            return "StepActivationMarker["
                    + "request=<provided>, "
                    + "result=<provided>, "
                    + "provenanceLink=<provided>]";
        }
    }
}
