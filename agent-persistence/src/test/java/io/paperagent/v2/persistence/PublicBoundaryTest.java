package io.paperagent.v2.persistence;

import io.paperagent.v2.contracts.EventEnvelope;
import io.paperagent.v2.contracts.EventId;
import io.paperagent.v2.contracts.PlanId;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.Clock;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PublicBoundaryTest {
    @Test
    void portsDoNotExposeInMemoryImplementations() {
        List<Class<?>> ports = List.of(
                TaskFrameRepository.class,
                PlanRepository.class,
                EventRepository.class,
                ReceiptRepository.class,
                CheckpointRepository.class,
                PlanBootstrapRepository.class,
                LeaseRepository.class,
                IdempotencyRepository.class);

        for (Class<?> port : ports) {
            assertTrue(port.isInterface());
            for (Method method : port.getMethods()) {
                assertFalse(
                        method.getReturnType().getSimpleName().startsWith("InMemory"),
                        method.toString());
                for (Class<?> parameterType : method.getParameterTypes()) {
                    assertFalse(
                            parameterType.getSimpleName().startsWith("InMemory"),
                            method.toString());
                }
            }
        }
    }

    @Test
    void implementationClassesRemainPackagePrivate() {
        Set<Class<?>> implementations = Set.of(
                InMemoryTaskFrameRepository.class,
                InMemoryPlanRepository.class,
                InMemoryEventRepository.class,
                InMemoryReceiptRepository.class,
                InMemoryCheckpointRepository.class,
                InMemoryPlanBootstrapRepository.class,
                InMemoryLeaseRepository.class,
                InMemoryIdempotencyRepository.class,
                InMemoryState.class);
        implementations.forEach(type -> assertFalse(Modifier.isPublic(type.getModifiers())));
    }

    @Test
    void eventRepositoryHasOnlyThePlanGlobalHardCutSurface() {
        Map<String, Method> methods = Arrays.stream(
                        EventRepository.class.getDeclaredMethods())
                .collect(Collectors.toUnmodifiableMap(
                        Method::getName,
                        Function.identity()));

        assertEquals(Set.of("append", "find", "readAfter"), methods.keySet());
        assertMethod(
                methods.get("append"),
                PersistenceResult.class,
                EventEnvelope.class);
        assertMethod(
                methods.get("find"),
                PersistenceResult.class,
                EventId.class);
        assertMethod(
                methods.get("readAfter"),
                PersistenceResult.class,
                PlanId.class,
                long.class);
        assertFalse(methods.containsKey("read"));
    }

    @Test
    void leaseRepositoryHasOnlyTheTrustedTimeHardCutSurface() {
        Map<String, Method> methods = Arrays.stream(
                        LeaseRepository.class.getDeclaredMethods())
                .collect(Collectors.toUnmodifiableMap(
                        Method::getName,
                        Function.identity()));

        assertEquals(Set.of("acquire", "renew", "release", "find"), methods.keySet());
        assertMethod(
                methods.get("acquire"),
                PersistenceResult.class,
                PlanId.class,
                String.class,
                String.class,
                java.time.Instant.class);
        assertMethod(
                methods.get("renew"),
                PersistenceResult.class,
                PlanId.class,
                String.class,
                java.time.Instant.class);
        assertMethod(
                methods.get("release"),
                PersistenceResult.class,
                PlanId.class,
                String.class);
        assertMethod(
                methods.get("find"),
                PersistenceResult.class,
                PlanId.class);
    }

    @Test
    void inMemoryPersistenceHasExactlyDefaultAndClockConstructors() {
        Set<List<Class<?>>> signatures = Arrays.stream(
                        InMemoryPersistence.class.getConstructors())
                .map(Constructor::getParameterTypes)
                .map(List::of)
                .collect(Collectors.toUnmodifiableSet());

        assertEquals(
                Set.of(List.of(), List.of(Clock.class)),
                signatures);
    }

    private static void assertMethod(
            Method method,
            Class<?> returnType,
            Class<?>... parameterTypes) {
        assertEquals(returnType, method.getReturnType());
        assertArrayEquals(parameterTypes, method.getParameterTypes());
    }
}
