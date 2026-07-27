package io.paperagent.v2.api.synthesis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.paperagent.v2.contracts.ContentHash;
import io.paperagent.v2.contracts.DiffId;
import io.paperagent.v2.contracts.DiffKind;
import io.paperagent.v2.contracts.FinalSynthesis;
import io.paperagent.v2.contracts.FinalSynthesisId;
import io.paperagent.v2.contracts.PlanId;
import io.paperagent.v2.contracts.PlanRevisionId;
import io.paperagent.v2.contracts.ProjectPath;
import io.paperagent.v2.contracts.ProjectVersionRef;
import io.paperagent.v2.contracts.ReceiptId;
import io.paperagent.v2.contracts.TaskFrameId;
import io.paperagent.v2.contracts.WorkspaceDiff;
import io.paperagent.v2.contracts.WorkspaceDiffEntry;
import io.paperagent.v2.contracts.WorkspaceId;
import io.paperagent.v2.contracts.WorkspaceRef;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class FinalSynthesisReadServiceTest {
    private static final FinalSynthesisId SYNTHESIS_ID = new FinalSynthesisId("synthesis-query-sentinel");
    private static final ProjectVersionRef SOURCE_PROJECT_VERSION =
            new ProjectVersionRef("project-query-sentinel", "version-query-sentinel");

    @Test
    void mapsOneMatchingCandidateThroughTheFrozenReadModelWithOneLookup() {
        FinalSynthesis synthesis = validSynthesis(SYNTHESIS_ID);
        AtomicReference<FinalSynthesisId> requestedId = new AtomicReference<>();
        AtomicInteger calls = new AtomicInteger();
        FinalSynthesisReadService service = new FinalSynthesisReadService(id -> {
            requestedId.set(id);
            calls.incrementAndGet();
            return Optional.of(synthesis);
        });

        Optional<FinalSynthesisReadModel> result = service.find(SYNTHESIS_ID);

        assertTrue(result.isPresent());
        FinalSynthesisReadModel model = result.orElseThrow();
        assertEquals(SYNTHESIS_ID, requestedId.get());
        assertEquals(1, calls.get());
        assertEquals(synthesis.id(), model.synthesisId());
        assertEquals(synthesis.taskFrameId(), model.taskFrameId());
        assertEquals(synthesis.planId(), model.planId());
        assertEquals(synthesis.planRevisionId(), model.planRevisionId());
        assertEquals(synthesis.sourceProjectVersion(), model.sourceProjectVersion());
        assertEquals(synthesis.workspaceDiff().id(), model.workspaceDiffId());
        assertEquals(synthesis.workspaceDiff().workspace().id(), model.workspaceId());
        assertEquals(synthesis.receiptIds(), model.receiptIds());
        assertEquals(synthesis.narrative(), model.narrative());
        assertEquals(synthesis.observedAt(), model.observedAt());
        assertNotSame(synthesis, model);
    }

    @Test
    void preservesAnEmptyLookupResultWithOneLookup() {
        AtomicInteger calls = new AtomicInteger();
        FinalSynthesisReadService service = new FinalSynthesisReadService(id -> {
            calls.incrementAndGet();
            return Optional.empty();
        });

        Optional<FinalSynthesisReadModel> result = service.find(SYNTHESIS_ID);

        assertTrue(result.isEmpty());
        assertEquals(1, calls.get());
    }

    @Test
    void rejectsNullSourceAndNullRequestedIdBeforeAnyLookup() {
        NullPointerException sourceFailure = assertThrows(
                NullPointerException.class, () -> new FinalSynthesisReadService(null));
        assertEquals("source", sourceFailure.getMessage());

        AtomicInteger calls = new AtomicInteger();
        FinalSynthesisReadService service = new FinalSynthesisReadService(id -> {
            calls.incrementAndGet();
            return Optional.empty();
        });

        NullPointerException idFailure = assertThrows(NullPointerException.class, () -> service.find(null));
        assertEquals("synthesisId", idFailure.getMessage());
        assertEquals(0, calls.get());
    }

    @Test
    void rejectsANullOptionalResultDeterministically() {
        AtomicInteger calls = new AtomicInteger();
        FinalSynthesisReadService service = new FinalSynthesisReadService(id -> {
            calls.incrementAndGet();
            return null;
        });

        NullPointerException failure = assertThrows(NullPointerException.class, () -> service.find(SYNTHESIS_ID));

        assertEquals("source.find(synthesisId)", failure.getMessage());
        assertEquals(1, calls.get());
    }

    @Test
    void rejectsAMismatchedCandidateBeforeReturningAnySummary() {
        AtomicInteger calls = new AtomicInteger();
        FinalSynthesisReadService service = new FinalSynthesisReadService(id -> {
            calls.incrementAndGet();
            return Optional.of(validSynthesis(new FinalSynthesisId("different-synthesis-sentinel")));
        });

        IllegalStateException failure = assertThrows(
                IllegalStateException.class, () -> service.find(SYNTHESIS_ID));

        assertEquals("source returned a synthesis with a different id", failure.getMessage());
        assertEquals(1, calls.get());
    }

    @Test
    void exposesOnlyTheFrozenReadOnlySourceAndSummaryQuerySurface() {
        assertTrue(Modifier.isPublic(FinalSynthesisReadSource.class.getModifiers()));
        assertTrue(FinalSynthesisReadSource.class.isInterface());
        assertEquals(Set.of("find(io.paperagent.v2.contracts.FinalSynthesisId)"),
                publicMethodSignatures(FinalSynthesisReadSource.class));

        Method sourceFind = declaredMethod(FinalSynthesisReadSource.class, "find");
        assertEquals(Optional.class, sourceFind.getReturnType());
        assertEquals(FinalSynthesis.class, parameterizedArgument(sourceFind.getGenericReturnType()));

        assertTrue(Modifier.isPublic(FinalSynthesisReadService.class.getModifiers()));
        assertTrue(Modifier.isFinal(FinalSynthesisReadService.class.getModifiers()));
        assertEquals(1, FinalSynthesisReadService.class.getDeclaredFields().length);
        assertTrue(Modifier.isPrivate(FinalSynthesisReadService.class.getDeclaredFields()[0].getModifiers()));
        assertTrue(Modifier.isFinal(FinalSynthesisReadService.class.getDeclaredFields()[0].getModifiers()));
        assertEquals(FinalSynthesisReadSource.class,
                FinalSynthesisReadService.class.getDeclaredFields()[0].getType());
        assertEquals(Set.of("find(io.paperagent.v2.contracts.FinalSynthesisId)"),
                publicMethodSignatures(FinalSynthesisReadService.class));

        Method serviceFind = declaredMethod(FinalSynthesisReadService.class, "find");
        assertEquals(Optional.class, serviceFind.getReturnType());
        assertEquals(FinalSynthesisReadModel.class, parameterizedArgument(serviceFind.getGenericReturnType()));
        assertFalse(serviceFind.getGenericReturnType().getTypeName().contains("FinalSynthesis>"));
    }

    @Test
    void keepsProductionImportsAndOuterConcernsOutsideTheFrozenBoundary() throws IOException {
        List<String> sources = List.of(
                Files.readString(Path.of("src/main/java/io/paperagent/v2/api/synthesis/FinalSynthesisReadSource.java")),
                Files.readString(Path.of("src/main/java/io/paperagent/v2/api/synthesis/FinalSynthesisReadService.java")));

        for (String source : sources) {
            for (String importedType : source.lines()
                    .filter(line -> line.startsWith("import "))
                    .map(line -> line.substring("import ".length(), line.length() - 1))
                    .toList()) {
                assertTrue(importedType.startsWith("java.")
                        || importedType.startsWith("io.paperagent.v2.contracts.")
                        || importedType.equals("io.paperagent.v2.api.synthesis.FinalSynthesisReadModel"), importedType);
            }
            List.of(
                            "agent-runtime",
                            "agent-persistence",
                            "agent-workspace",
                            "agent-sandbox",
                            "agent-providers",
                            "spring",
                            "javax.",
                            "jakarta.",
                            "controller",
                            "repository",
                            "cache",
                            "retry",
                            "receipt loading",
                            "ProjectVersion publication",
                            "paperagent_redo")
                    .forEach(forbidden -> assertFalse(source.contains(forbidden), forbidden));
        }
    }

    private static FinalSynthesis validSynthesis(FinalSynthesisId id) {
        WorkspaceDiff workspaceDiff = new WorkspaceDiff(
                new DiffId("diff-query-sentinel"),
                new WorkspaceRef(new WorkspaceId("workspace-query-sentinel"), SOURCE_PROJECT_VERSION),
                List.of(new WorkspaceDiffEntry(
                        DiffKind.ADD,
                        new ProjectPath("summary.txt"),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.of(new ContentHash(
                                "sha256", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")),
                        Map.of("tool-output-sentinel", "artifact-sentinel"))),
                Instant.parse("2026-07-27T12:00:00Z"));
        return new FinalSynthesis(
                id,
                new TaskFrameId("task-frame-query-sentinel"),
                new PlanId("plan-query-sentinel"),
                new PlanRevisionId("plan-revision-query-sentinel"),
                Optional.of(SOURCE_PROJECT_VERSION),
                workspaceDiff,
                List.of(new ReceiptId("receipt-query-sentinel")),
                "narrative-query-sentinel",
                Instant.parse("2026-07-27T13:00:00Z"));
    }

    private static Method declaredMethod(Class<?> type, String name) {
        return java.util.Arrays.stream(type.getDeclaredMethods())
                .filter(method -> method.getName().equals(name))
                .findFirst()
                .orElseThrow();
    }

    private static Set<String> publicMethodSignatures(Class<?> type) {
        return java.util.Arrays.stream(type.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .map(FinalSynthesisReadServiceTest::methodSignature)
                .collect(Collectors.toSet());
    }

    private static Class<?> parameterizedArgument(Type type) {
        return (Class<?>) ((ParameterizedType) type).getActualTypeArguments()[0];
    }

    private static String methodSignature(Method method) {
        return method.getName() + "(" + java.util.Arrays.stream(method.getParameterTypes())
                .map(Class::getName)
                .collect(Collectors.joining(",")) + ")";
    }
}
