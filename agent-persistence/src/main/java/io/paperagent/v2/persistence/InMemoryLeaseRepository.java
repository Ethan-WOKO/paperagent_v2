package io.paperagent.v2.persistence;

import io.paperagent.v2.contracts.PlanId;

import java.time.Instant;

final class InMemoryLeaseRepository implements LeaseRepository {
    private final InMemoryState state;

    InMemoryLeaseRepository(InMemoryState state) {
        this.state = state;
    }

    @Override
    public PersistenceResult<LeaseRecord> acquire(
            PlanId planId,
            String ownerId,
            String leaseToken,
            Instant now,
            Instant expiresAt) {
        PersistenceResult<LeaseRecord> invalid =
                validateAcquire(planId, ownerId, leaseToken, now, expiresAt);
        if (invalid != null) {
            return invalid;
        }
        synchronized (state.monitor) {
            if (!state.plans.containsKey(planId)) {
                return PersistenceChecks.notFound("planId");
            }
            LeaseRecord current = state.leases.get(planId);
            if (current != null && now.isBefore(current.acquiredAt())) {
                return PersistenceChecks.invalid("now");
            }
            if (current != null && !current.isExpiredAt(now)) {
                if (current.ownerId().equals(ownerId)
                        && current.leaseToken().equals(leaseToken)
                        && current.expiresAt().equals(expiresAt)) {
                    return PersistenceResult.replayed(current);
                }
                return PersistenceResult.rejected(
                        PersistenceErrorCode.LEASE_HELD, "planId");
            }
            if (state.usedLeaseTokens.contains(leaseToken)) {
                return PersistenceResult.rejected(
                        PersistenceErrorCode.LEASE_TOKEN_INVALID, "leaseToken");
            }
            long fencingToken = state.fencingTokens.getOrDefault(planId, 0L) + 1;
            LeaseRecord acquired = new LeaseRecord(
                    planId, ownerId, leaseToken, fencingToken, now, expiresAt);
            state.fencingTokens.put(planId, fencingToken);
            state.usedLeaseTokens.add(leaseToken);
            state.leases.put(planId, acquired);
            return PersistenceResult.applied(acquired);
        }
    }

    @Override
    public PersistenceResult<LeaseRecord> renew(
            PlanId planId,
            String leaseToken,
            Instant now,
            Instant expiresAt) {
        if (PersistenceChecks.missing(planId)) {
            return PersistenceChecks.invalid("planId");
        }
        if (PersistenceChecks.blank(leaseToken)) {
            return PersistenceChecks.invalid("leaseToken");
        }
        if (PersistenceChecks.missing(now)) {
            return PersistenceChecks.invalid("now");
        }
        if (PersistenceChecks.missing(expiresAt) || !expiresAt.isAfter(now)) {
            return PersistenceChecks.invalid("expiresAt");
        }
        synchronized (state.monitor) {
            LeaseRecord current = state.leases.get(planId);
            if (current == null) {
                return PersistenceResult.rejected(
                        PersistenceErrorCode.LEASE_NOT_HELD, "planId");
            }
            if (now.isBefore(current.acquiredAt())) {
                return PersistenceChecks.invalid("now");
            }
            if (!current.leaseToken().equals(leaseToken)) {
                return PersistenceResult.rejected(
                        PersistenceErrorCode.LEASE_TOKEN_INVALID, "leaseToken");
            }
            if (current.isExpiredAt(now)) {
                return PersistenceResult.rejected(
                        PersistenceErrorCode.LEASE_EXPIRED, "now");
            }
            if (current.expiresAt().equals(expiresAt)) {
                return PersistenceResult.replayed(current);
            }
            if (!expiresAt.isAfter(current.expiresAt())) {
                return PersistenceChecks.invalid("expiresAt");
            }
            LeaseRecord renewed = new LeaseRecord(
                    current.planId(),
                    current.ownerId(),
                    current.leaseToken(),
                    current.fencingToken(),
                    current.acquiredAt(),
                    expiresAt);
            state.leases.put(planId, renewed);
            return PersistenceResult.applied(renewed);
        }
    }

    @Override
    public PersistenceResult<LeaseRecord> release(
            PlanId planId,
            String leaseToken,
            Instant now) {
        if (PersistenceChecks.missing(planId)) {
            return PersistenceChecks.invalid("planId");
        }
        if (PersistenceChecks.blank(leaseToken)) {
            return PersistenceChecks.invalid("leaseToken");
        }
        if (PersistenceChecks.missing(now)) {
            return PersistenceChecks.invalid("now");
        }
        synchronized (state.monitor) {
            LeaseRecord current = state.leases.get(planId);
            if (current == null) {
                return PersistenceResult.rejected(
                        PersistenceErrorCode.LEASE_NOT_HELD, "planId");
            }
            if (now.isBefore(current.acquiredAt())) {
                return PersistenceChecks.invalid("now");
            }
            if (!current.leaseToken().equals(leaseToken)) {
                return PersistenceResult.rejected(
                        PersistenceErrorCode.LEASE_TOKEN_INVALID, "leaseToken");
            }
            if (current.isExpiredAt(now)) {
                return PersistenceResult.rejected(
                        PersistenceErrorCode.LEASE_EXPIRED, "now");
            }
            state.leases.remove(planId);
            return PersistenceResult.applied(current);
        }
    }

    @Override
    public PersistenceResult<LeaseRecord> find(PlanId planId) {
        if (PersistenceChecks.missing(planId)) {
            return PersistenceChecks.invalid("planId");
        }
        synchronized (state.monitor) {
            LeaseRecord current = state.leases.get(planId);
            return current == null
                    ? PersistenceChecks.notFound("planId")
                    : PersistenceResult.found(current);
        }
    }

    private static PersistenceResult<LeaseRecord> validateAcquire(
            PlanId planId,
            String ownerId,
            String leaseToken,
            Instant now,
            Instant expiresAt) {
        if (PersistenceChecks.missing(planId)) {
            return PersistenceChecks.invalid("planId");
        }
        if (PersistenceChecks.blank(ownerId)) {
            return PersistenceChecks.invalid("ownerId");
        }
        if (PersistenceChecks.blank(leaseToken)) {
            return PersistenceChecks.invalid("leaseToken");
        }
        if (PersistenceChecks.missing(now)) {
            return PersistenceChecks.invalid("now");
        }
        if (PersistenceChecks.missing(expiresAt) || !expiresAt.isAfter(now)) {
            return PersistenceChecks.invalid("expiresAt");
        }
        return null;
    }
}
