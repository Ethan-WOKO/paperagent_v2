package io.paperagent.v2.persistence;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActiveStepReplanRepositoryConcurrencyTest {

    @Test
    void concurrentExactCompositeCallsApplyOnceAndReplayOneTwoLinkFact()
            throws Exception {
        ActiveStepReplanTestSupport.Harness harness =
                ActiveStepReplanTestSupport.active("exact-race");
        ActiveStepReplanRequest request =
                ActiveStepReplanTestSupport.request(harness, "exact-race");
        List<Callable<PersistenceResult<PersistedActiveStepReplan>>> calls =
                new ArrayList<>();
        for (int index = 0; index < 24; index++) {
            calls.add(() -> harness.activeReplans().supersedeAndReplan(request));
        }

        List<PersistenceResult<PersistedActiveStepReplan>> results = race(calls);

        assertEquals(1, results.stream().filter(result ->
                result.outcome() == PersistenceOutcome.APPLIED).count());
        assertEquals(23, results.stream().filter(result ->
                result.outcome() == PersistenceOutcome.REPLAYED).count());
        assertEquals(1, results.stream().map(result ->
                result.value().orElseThrow()).distinct().count());
        assertEquals(3, harness.state().executionMutationLinks.get(harness.plan().id()).size());
        assertEquals(4, harness.state().eventStreams.get(harness.plan().id()).size());
        assertEquals(1, harness.state().activeStepReplans.get(harness.plan().id()).size());
    }

    @Test
    void competingCompositeRequestsCannotForkTheActiveSource() throws Exception {
        ActiveStepReplanTestSupport.Harness harness =
                ActiveStepReplanTestSupport.active("competing-race");
        ActiveStepReplanRequest left =
                ActiveStepReplanTestSupport.request(harness, "competing-left");
        ActiveStepReplanRequest right =
                ActiveStepReplanTestSupport.request(harness, "competing-right");

        List<PersistenceResult<PersistedActiveStepReplan>> results = race(List.of(
                () -> harness.activeReplans().supersedeAndReplan(left),
                () -> harness.activeReplans().supersedeAndReplan(right)));

        assertEquals(1, results.stream().filter(result ->
                result.outcome() == PersistenceOutcome.APPLIED).count());
        assertEquals(1, results.stream().filter(result ->
                result.outcome() == PersistenceOutcome.REJECTED).count());
        assertEquals(1, harness.state().activeStepReplans.get(harness.plan().id()).size());
        assertEquals(3, harness.state().executionMutationLinks.get(harness.plan().id()).size());
        assertEquals(4, harness.state().eventStreams.get(harness.plan().id()).size());
    }

    private static <T> List<T> race(List<Callable<T>> calls) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(calls.size());
        CountDownLatch ready = new CountDownLatch(calls.size());
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> call : calls) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new AssertionError("race start timed out");
                    }
                    return call.call();
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(10, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(10, TimeUnit.SECONDS);
        }
    }
}
