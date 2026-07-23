package io.paperagent.v2.persistence;

import io.paperagent.v2.contracts.EventEnvelope;
import io.paperagent.v2.contracts.EventId;
import io.paperagent.v2.contracts.ExecutionReceipt;
import io.paperagent.v2.contracts.Plan;
import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.ReceiptId;
import io.paperagent.v2.contracts.TaskFrame;
import io.paperagent.v2.contracts.TaskFrameId;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
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
}
