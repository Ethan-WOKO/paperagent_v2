package io.paperagent.v2.persistence;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StepActivationRepositoryBoundaryTest {
    @Test
    void referenceImplementationIsUniqueAndDoesNotComposeOrdinaryWriters()
            throws IOException {
        Path sourceRoot = Path.of("src", "main", "java");
        List<Path> production;
        try (var paths = Files.walk(sourceRoot)) {
            production = paths.filter(path -> path.toString().endsWith(".java"))
                    .toList();
        }
        long implementations = 0;
        for (Path path : production) {
            String source = Files.readString(path);
            String normalized = source.replaceAll("\\s+", " ");
            if (normalized.contains(
                    "implements StepActivationRepository")) {
                implementations++;
                assertTrue(path.getFileName().toString()
                        .equals("InMemoryStepActivationRepository.java"));
            }
        }
        assertEquals(1, implementations);

        String source = Files.readString(sourceRoot.resolve(
                Path.of(
                        "io",
                        "paperagent",
                        "v2",
                        "persistence",
                        "InMemoryStepActivationRepository.java")));
        String normalizedSource = source.replaceAll("\\s+", " ");
        for (String forbidden : List.of(
                "PlanRepository",
                "EventRepository",
                "CheckpointRepository",
                ".append(",
                ".save(",
                "java.io.",
                "java.net.",
                "java.nio.file.",
                "java.lang.Process",
                "java.lang.Thread",
                "System.getenv",
                "System.getProperty",
                "org.junit.",
                "src/test",
                "getDeclared",
                "setAccessible",
                "Random",
                "UUID")) {
            assertFalse(source.contains(forbidden), forbidden);
        }
        assertTrue(source.contains("state.observeLeaseTime()"));
        assertFalse(source.contains("Clock"));
        assertTrue(normalizedSource.contains("current.version() < 2"));
        assertFalse(normalizedSource.contains("current.version() == 2"));
        assertFalse(normalizedSource.contains("current.version() != 2"));
    }

    @Test
    void internalAuthorityTypesStayPackagePrivateAndOutsidePublicFacts() {
        for (Class<?> type : List.of(
                InMemoryState.ExecutionStartMarker.class,
                InMemoryState.ExecutionMutationHead.class,
                InMemoryState.ExecutionMutationLink.class,
                InMemoryState.ExecutionMutationMarkerIdentity.class,
                InMemoryState.StepActivationMarker.class,
                InMemoryStepActivationRepository.AuthoritativeSource.class)) {
            assertFalse(java.lang.reflect.Modifier.isPublic(
                    type.getModifiers()));
        }
        String resultFields = java.util.Arrays.stream(
                        PersistedStepActivation.class.getDeclaredFields())
                .map(field -> field.getType().getName())
                .reduce("", (left, right) -> left + " " + right);
        assertFalse(resultFields.contains("ExecutionMutation"));
        assertFalse(resultFields.contains("StepActivationRequest"));
    }
}
