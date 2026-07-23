package io.paperagent.v2.persistence;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LeaseRepositoryTest {
    private static final Instant T0 = PersistenceFixtures.T0;

    @Test
    void liveLeaseCanRenewAndReleaseOnlyWithOwnerToken() {
        InMemoryPersistence persistence = PersistenceFixtures.initializedPersistence();
        PersistenceResult<LeaseRecord> acquired = persistence.leases().acquire(
                PersistenceFixtures.PLAN_ID,
                "worker-a",
                "lease-token-a",
                T0,
                T0.plusSeconds(30));
        LeaseRecord lease = acquired.value().orElseThrow();
        assertEquals(PersistenceOutcome.APPLIED, acquired.outcome());
        assertEquals(1, lease.fencingToken());
        assertEquals(
                PersistenceOutcome.REPLAYED,
                persistence.leases().acquire(
                        PersistenceFixtures.PLAN_ID,
                        "worker-a",
                        "lease-token-a",
                        T0,
                        T0.plusSeconds(30)).outcome());

        assertFailure(
                persistence.leases().acquire(
                        PersistenceFixtures.PLAN_ID,
                        "worker-b",
                        "lease-token-b",
                        T0.plusSeconds(1),
                        T0.plusSeconds(40)),
                PersistenceErrorCode.LEASE_HELD);
        assertFailure(
                persistence.leases().renew(
                        PersistenceFixtures.PLAN_ID,
                        "not-owner",
                        T0.plusSeconds(1),
                        T0.plusSeconds(40)),
                PersistenceErrorCode.LEASE_TOKEN_INVALID);
        PersistenceResult<LeaseRecord> renewed = persistence.leases().renew(
                PersistenceFixtures.PLAN_ID,
                "lease-token-a",
                T0.plusSeconds(1),
                T0.plusSeconds(40));
        assertEquals(PersistenceOutcome.APPLIED, renewed.outcome());
        assertEquals(1, renewed.value().orElseThrow().fencingToken());

        assertFailure(
                persistence.leases().release(
                        PersistenceFixtures.PLAN_ID, "not-owner", T0.plusSeconds(2)),
                PersistenceErrorCode.LEASE_TOKEN_INVALID);
        assertEquals(
                PersistenceOutcome.APPLIED,
                persistence.leases().release(
                        PersistenceFixtures.PLAN_ID,
                        "lease-token-a",
                        T0.plusSeconds(2)).outcome());
        assertFailure(
                persistence.leases().find(PersistenceFixtures.PLAN_ID),
                PersistenceErrorCode.NOT_FOUND);
    }

    @Test
    void expiryTakeoverIncrementsFenceAndStaleTokenCannotAct() {
        InMemoryPersistence persistence = PersistenceFixtures.initializedPersistence();
        persistence.leases().acquire(
                PersistenceFixtures.PLAN_ID,
                "worker-a",
                "lease-token-a",
                T0,
                T0.plusSeconds(10));

        PersistenceResult<LeaseRecord> takeover = persistence.leases().acquire(
                PersistenceFixtures.PLAN_ID,
                "worker-b",
                "lease-token-b",
                T0.plusSeconds(10),
                T0.plusSeconds(20));
        assertEquals(PersistenceOutcome.APPLIED, takeover.outcome());
        assertEquals(2, takeover.value().orElseThrow().fencingToken());
        assertFailure(
                persistence.leases().renew(
                        PersistenceFixtures.PLAN_ID,
                        "lease-token-a",
                        T0.plusSeconds(11),
                        T0.plusSeconds(30)),
                PersistenceErrorCode.LEASE_TOKEN_INVALID);
        assertFailure(
                persistence.leases().release(
                        PersistenceFixtures.PLAN_ID,
                        "lease-token-a",
                        T0.plusSeconds(11)),
                PersistenceErrorCode.LEASE_TOKEN_INVALID);
    }

    @Test
    void releasedOrExpiredTokensCannotBeReusedAndFenceStillIncreases() {
        InMemoryPersistence persistence = PersistenceFixtures.initializedPersistence();
        persistence.leases().acquire(
                PersistenceFixtures.PLAN_ID,
                "worker-a",
                "lease-token-a",
                T0,
                T0.plusSeconds(10));
        persistence.leases().release(
                PersistenceFixtures.PLAN_ID, "lease-token-a", T0.plusSeconds(1));

        assertFailure(
                persistence.leases().acquire(
                        PersistenceFixtures.PLAN_ID,
                        "worker-a",
                        "lease-token-a",
                        T0.plusSeconds(2),
                        T0.plusSeconds(12)),
                PersistenceErrorCode.LEASE_TOKEN_INVALID);
        LeaseRecord next = persistence.leases().acquire(
                PersistenceFixtures.PLAN_ID,
                "worker-b",
                "lease-token-b",
                T0.plusSeconds(2),
                T0.plusSeconds(12)).value().orElseThrow();
        assertEquals(2, next.fencingToken());
    }

    @Test
    void expiredLeaseCannotRenewOrRelease() {
        InMemoryPersistence persistence = PersistenceFixtures.initializedPersistence();
        persistence.leases().acquire(
                PersistenceFixtures.PLAN_ID,
                "worker-a",
                "lease-token-a",
                T0,
                T0.plusSeconds(10));
        assertFailure(
                persistence.leases().renew(
                        PersistenceFixtures.PLAN_ID,
                        "lease-token-a",
                        T0.plusSeconds(10),
                        T0.plusSeconds(20)),
                PersistenceErrorCode.LEASE_EXPIRED);
        assertFailure(
                persistence.leases().release(
                        PersistenceFixtures.PLAN_ID,
                        "lease-token-a",
                        T0.plusSeconds(10)),
                PersistenceErrorCode.LEASE_EXPIRED);
    }

    @Test
    void exactlyOneConcurrentLeaseOwnerAcquires() throws Exception {
        InMemoryPersistence persistence = PersistenceFixtures.initializedPersistence();
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<PersistenceResult<LeaseRecord>> first =
                    executor.submit(() -> acquireAfterBarrier(
                            persistence, barrier, "worker-a", "lease-token-a"));
            Future<PersistenceResult<LeaseRecord>> second =
                    executor.submit(() -> acquireAfterBarrier(
                            persistence, barrier, "worker-b", "lease-token-b"));
            List<PersistenceResult<LeaseRecord>> results = List.of(
                    first.get(5, TimeUnit.SECONDS),
                    second.get(5, TimeUnit.SECONDS));

            assertEquals(
                    1,
                    results.stream()
                            .filter(result -> result.outcome() == PersistenceOutcome.APPLIED)
                            .count());
            assertEquals(
                    1,
                    results.stream()
                            .filter(result -> result.failure()
                                    .map(failure -> failure.code() == PersistenceErrorCode.LEASE_HELD)
                                    .orElse(false))
                            .count());
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void leaseInputsUseStableErrorsWithoutReadingClock() {
        InMemoryPersistence persistence = PersistenceFixtures.initializedPersistence();
        assertFailure(
                persistence.leases().acquire(
                        null, "owner", "token", T0, T0.plusSeconds(1)),
                PersistenceErrorCode.INVALID_ARGUMENT);
        assertFailure(
                persistence.leases().acquire(
                        PersistenceFixtures.PLAN_ID, " ", "token", T0, T0.plusSeconds(1)),
                PersistenceErrorCode.INVALID_ARGUMENT);
        assertFailure(
                persistence.leases().acquire(
                        PersistenceFixtures.PLAN_ID, "owner", "token", T0, T0),
                PersistenceErrorCode.INVALID_ARGUMENT);
        assertFailure(
                persistence.leases().renew(
                        PersistenceFixtures.PLAN_ID, null, T0, T0.plusSeconds(1)),
                PersistenceErrorCode.INVALID_ARGUMENT);

        persistence.leases().acquire(
                PersistenceFixtures.PLAN_ID,
                "owner",
                "valid-token",
                T0,
                T0.plusSeconds(10));
        assertFailure(
                persistence.leases().release(
                        PersistenceFixtures.PLAN_ID,
                        "valid-token",
                        T0.minusSeconds(1)),
                PersistenceErrorCode.INVALID_ARGUMENT);
    }

    private static void assertFailure(
            PersistenceResult<?> result,
            PersistenceErrorCode expectedCode) {
        assertEquals(PersistenceOutcome.REJECTED, result.outcome());
        assertEquals(expectedCode, result.failure().orElseThrow().code());
    }

    private static PersistenceResult<LeaseRecord> acquireAfterBarrier(
            InMemoryPersistence persistence,
            CyclicBarrier barrier,
            String owner,
            String token) throws Exception {
        barrier.await(5, TimeUnit.SECONDS);
        return persistence.leases().acquire(
                PersistenceFixtures.PLAN_ID,
                owner,
                token,
                T0,
                T0.plusSeconds(10));
    }
}
