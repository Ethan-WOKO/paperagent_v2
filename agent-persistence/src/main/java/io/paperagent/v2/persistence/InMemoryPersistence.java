package io.paperagent.v2.persistence;

public final class InMemoryPersistence {
    private final TaskFrameRepository taskFrames;
    private final PlanRepository plans;
    private final EventRepository events;
    private final ReceiptRepository receipts;
    private final CheckpointRepository checkpoints;
    private final LeaseRepository leases;
    private final IdempotencyRepository idempotency;

    public InMemoryPersistence() {
        InMemoryState state = new InMemoryState();
        taskFrames = new InMemoryTaskFrameRepository(state);
        plans = new InMemoryPlanRepository(state);
        events = new InMemoryEventRepository(state);
        receipts = new InMemoryReceiptRepository(state);
        checkpoints = new InMemoryCheckpointRepository(state);
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

    public LeaseRepository leases() {
        return leases;
    }

    public IdempotencyRepository idempotency() {
        return idempotency;
    }
}
