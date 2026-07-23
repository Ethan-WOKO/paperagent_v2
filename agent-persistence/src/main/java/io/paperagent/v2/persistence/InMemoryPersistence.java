package io.paperagent.v2.persistence;

import java.time.Clock;
import java.util.Objects;

public final class InMemoryPersistence {
    private final TaskFrameRepository taskFrames;
    private final PlanRepository plans;
    private final EventRepository events;
    private final ReceiptRepository receipts;
    private final CheckpointRepository checkpoints;
    private final PlanBootstrapRepository planBootstraps;
    private final LeaseRepository leases;
    private final IdempotencyRepository idempotency;

    public InMemoryPersistence() {
        this(Clock.systemUTC());
    }

    public InMemoryPersistence(Clock leaseClock) {
        InMemoryState state =
                new InMemoryState(Objects.requireNonNull(leaseClock, "leaseClock"));
        taskFrames = new InMemoryTaskFrameRepository(state);
        plans = new InMemoryPlanRepository(state);
        events = new InMemoryEventRepository(state);
        receipts = new InMemoryReceiptRepository(state);
        checkpoints = new InMemoryCheckpointRepository(state);
        planBootstraps = new InMemoryPlanBootstrapRepository(state);
        leases = new InMemoryLeaseRepository(state);
        idempotency = new InMemoryIdempotencyRepository(state);
    }

    public TaskFrameRepository taskFrames() {
        return taskFrames;
    }

    public PlanRepository plans() {
        return plans;
    }

    public EventRepository events() {
        return events;
    }

    public ReceiptRepository receipts() {
        return receipts;
    }

    public CheckpointRepository checkpoints() {
        return checkpoints;
    }

    public PlanBootstrapRepository planBootstraps() {
        return planBootstraps;
    }

    public LeaseRepository leases() {
        return leases;
    }

    public IdempotencyRepository idempotency() {
        return idempotency;
    }
}
