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
                ExecutionStartRepository.class,
                ExecutionStartRecoveryRepository.class,
                StepActivationRepository.class,
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
                InMemoryExecutionStartRepository.class,
                InMemoryExecutionStartRecoveryRepository.class,
                InMemoryStepActivationRepository.class,
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

    @Test
    void executionStartSurfaceIsExactAndDoesNotExposeLeaseTokenInResult()
            throws Exception {
        Method start = Arrays.stream(ExecutionStartRepository.class.getDeclaredMethods())
                .collect(Collectors.toUnmodifiableMap(
                        Method::getName,
                        Function.identity()))
                .get("start");
        assertEquals(1, ExecutionStartRepository.class.getDeclaredMethods().length);
        assertMethod(
                start,
                PersistenceResult.class,
                ExecutionStartRequest.class);

        assertEquals(
                List.of(
                        "planId",
                        "leaseToken",
                        "fencingToken",
                        "startEvent",
                        "startedCheckpoint"),
                Arrays.stream(ExecutionStartRequest.class.getRecordComponents())
                        .map(component -> component.getName())
                        .toList());
        assertEquals(
                List.of(
                        PlanId.class,
                        String.class,
                        long.class,
                        EventEnvelope.class,
                        io.paperagent.v2.contracts.Checkpoint.class),
                Arrays.stream(ExecutionStartRequest.class.getRecordComponents())
                        .map(component -> component.getType())
                        .toList());
        assertEquals(
                List.of(
                        "planId",
                        "leaseOwnerId",
                        "fencingToken",
                        "startEvent",
                        "startedCheckpoint"),
                Arrays.stream(PersistedExecutionStart.class.getRecordComponents())
                        .map(component -> component.getName())
                        .toList());
        assertEquals(
                List.of(
                        PlanId.class,
                        String.class,
                        long.class,
                        EventEnvelope.class,
                        VersionedCheckpoint.class),
                Arrays.stream(PersistedExecutionStart.class.getRecordComponents())
                        .map(component -> component.getType())
                        .toList());
        assertEquals(
                ExecutionStartRepository.class,
                InMemoryPersistence.class
                        .getDeclaredMethod("executionStarts")
                        .getReturnType());
    }

    @Test
    void executionStartRecoverySurfaceIsExactAndTokenFree()
            throws Exception {
        Method inspect = Arrays.stream(
                        ExecutionStartRecoveryRepository.class.getDeclaredMethods())
                .collect(Collectors.toUnmodifiableMap(
                        Method::getName,
                        Function.identity()))
                .get("inspect");
        assertEquals(
                1,
                ExecutionStartRecoveryRepository.class
                        .getDeclaredMethods()
                        .length);
        assertMethod(inspect, PersistenceResult.class, PlanId.class);

        assertTrue(ExecutionStartRecoverySnapshot.class.isSealed());
        Map<String, Method> snapshotMethods = Arrays.stream(
                        ExecutionStartRecoverySnapshot.class.getDeclaredMethods())
                .collect(Collectors.toUnmodifiableMap(
                        Method::getName,
                        Function.identity()));
        assertEquals(Set.of("planId"), snapshotMethods.keySet());
        assertMethod(snapshotMethods.get("planId"), PlanId.class);
        assertEquals(
                Set.of(
                        PersistedExecutionStartReady.class,
                        PersistedExecutionStartCommitted.class),
                Set.of(ExecutionStartRecoverySnapshot.class
                        .getPermittedSubclasses()));
        assertEquals(
                List.of("bootstrap", "currentPlan"),
                Arrays.stream(
                                PersistedExecutionStartReady.class
                                        .getRecordComponents())
                        .map(component -> component.getName())
                        .toList());
        assertEquals(
                List.of(PersistedPlanBootstrap.class, io.paperagent.v2.contracts.Plan.class),
                Arrays.stream(
                                PersistedExecutionStartReady.class
                                        .getRecordComponents())
                        .map(component -> component.getType())
                        .toList());
        assertEquals(
                List.of("bootstrap", "currentPlan", "executionStart"),
                Arrays.stream(
                                PersistedExecutionStartCommitted.class
                                        .getRecordComponents())
                        .map(component -> component.getName())
                        .toList());
        assertEquals(
                List.of(
                        PersistedPlanBootstrap.class,
                        io.paperagent.v2.contracts.Plan.class,
                        PersistedExecutionStart.class),
                Arrays.stream(
                                PersistedExecutionStartCommitted.class
                                        .getRecordComponents())
                        .map(component -> component.getType())
                        .toList());
        for (Class<?> snapshot : List.of(
                PersistedExecutionStartReady.class,
                PersistedExecutionStartCommitted.class)) {
            assertTrue(Arrays.stream(snapshot.getRecordComponents())
                    .noneMatch(component ->
                            component.getType() == ExecutionStartRequest.class
                                    || component.getType() == LeaseRecord.class));
            assertTrue(Arrays.stream(snapshot.getDeclaredFields())
                    .noneMatch(field ->
                            field.getType() == ExecutionStartRequest.class
                                    || field.getType() == LeaseRecord.class));
        }
        assertEquals(
                ExecutionStartRecoveryRepository.class,
                InMemoryPersistence.class
                        .getDeclaredMethod("executionStartRecovery")
                        .getReturnType());
    }

    @Test
    void stepActivationSurfaceIsExactAndTokenFreeInResult()
            throws Exception {
        assertTrue(StepActivationRepository.class.isInterface());
        assertEquals(
                1,
                StepActivationRepository.class.getDeclaredMethods().length);
        assertMethod(
                StepActivationRepository.class.getDeclaredMethod(
                        "activate", StepActivationRequest.class),
                PersistenceResult.class,
                StepActivationRequest.class);
        assertEquals(
                List.of(
                        "planId",
                        "leaseToken",
                        "fencingToken",
                        "expectedRevisionId",
                        "expectedRevisionNumber",
                        "expectedCheckpointVersion",
                        "expectedEventHeadSequence",
                        "stepId",
                        "activationEvent",
                        "activatedCheckpoint"),
                Arrays.stream(StepActivationRequest.class.getRecordComponents())
                        .map(component -> component.getName())
                        .toList());
        assertEquals(
                List.of(
                        PlanId.class,
                        String.class,
                        long.class,
                        io.paperagent.v2.contracts.PlanRevisionId.class,
                        long.class,
                        long.class,
                        long.class,
                        io.paperagent.v2.contracts.PlanStepId.class,
                        EventEnvelope.class,
                        io.paperagent.v2.contracts.Checkpoint.class),
                Arrays.stream(StepActivationRequest.class.getRecordComponents())
                        .map(component -> component.getType())
                        .toList());
        assertEquals(
                List.of(
                        "planId",
                        "stepId",
                        "leaseOwnerId",
                        "fencingToken",
                        "activationEvent",
                        "activatedCheckpoint"),
                Arrays.stream(PersistedStepActivation.class.getRecordComponents())
                        .map(component -> component.getName())
                        .toList());
        assertEquals(
                List.of(
                        PlanId.class,
                        io.paperagent.v2.contracts.PlanStepId.class,
                        String.class,
                        long.class,
                        EventEnvelope.class,
                        VersionedCheckpoint.class),
                Arrays.stream(PersistedStepActivation.class.getRecordComponents())
                        .map(component -> component.getType())
                        .toList());
        assertTrue(Arrays.stream(PersistedStepActivation.class.getRecordComponents())
                .noneMatch(component ->
                        component.getName().equals("leaseToken")
                                || component.getType()
                                        == StepActivationRequest.class));
        assertEquals(
                StepActivationRepository.class,
                InMemoryPersistence.class
                        .getDeclaredMethod("stepActivations")
                        .getReturnType());
    }

    private static void assertMethod(
            Method method,
            Class<?> returnType,
            Class<?>... parameterTypes) {
        assertEquals(returnType, method.getReturnType());
        assertArrayEquals(parameterTypes, method.getParameterTypes());
    }
}
