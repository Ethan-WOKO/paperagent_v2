package io.paperagent.v2.persistence;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Set;

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
}
