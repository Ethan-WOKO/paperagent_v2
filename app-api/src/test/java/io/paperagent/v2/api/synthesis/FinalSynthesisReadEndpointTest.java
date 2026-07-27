package io.paperagent.v2.api.synthesis;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class FinalSynthesisReadEndpointTest {
    private static final FinalSynthesisId SYNTHESIS_ID = new FinalSynthesisId("endpoint-synthesis-sentinel");
    private static final ProjectVersionRef SOURCE_PROJECT_VERSION =
            new ProjectVersionRef("endpoint-project-sentinel", "endpoint-version-sentinel");

    @Test
    void returnsFoundWithOnlyTheSafeModelAfterOneValidLookup() {
        FinalSynthesis synthesis = validSynthesis(SYNTHESIS_ID);
        AtomicReference<FinalSynthesisId> requestedId = new AtomicReference<>();
        AtomicInteger calls = new AtomicInteger();
        FinalSynthesisReadEndpoint endpoint = endpoint(id -> {
            requestedId.set(id);
            calls.incrementAndGet();
            return Optional.of(synthesis);
        });

        FinalSynthesisReadResponse response = endpoint.read(SYNTHESIS_ID.value());

        assertEquals(FinalSynthesisReadResponseStatus.FOUND, response.status());
        assertTrue(response.body().isPresent());
        assertEquals(SYNTHESIS_ID, requestedId.get());
        assertEquals(1, calls.get());
        FinalSynthesisReadModel body = response.body().orElseThrow();
        assertEquals(SYNTHESIS_ID, body.synthesisId());
        assertEquals(synthesis.narrative(), body.narrative());
        assertEquals(synthesis.receiptIds(), body.receiptIds());
    }

    @Test
    void returnsBodylessNotFoundAfterOneValidLookup() {
        AtomicInteger calls = new AtomicInteger();
        FinalSynthesisReadEndpoint endpoint = endpoint(id -> {
            calls.incrementAndGet();
            return Optional.empty();
        });

        FinalSynthesisReadResponse response = endpoint.read(SYNTHESIS_ID.value());

        assertEquals(FinalSynthesisReadResponseStatus.NOT_FOUND, response.status());
        assertTrue(response.body().isEmpty());
        assertEquals(1, calls.get());
    }

    @Test
    void returnsBodylessInvalidIdentifierWithoutLookupForNullBlankOrUnsupportedInput() {
        AtomicInteger calls = new AtomicInteger();
        FinalSynthesisReadEndpoint endpoint = endpoint(id -> {
            calls.incrementAndGet();
            return Optional.empty();
        });

        Arrays.asList(null, "", "   ", "unsupported/id").forEach(rawSynthesisId -> {
            FinalSynthesisReadResponse response = endpoint.read(rawSynthesisId);
            assertEquals(FinalSynthesisReadResponseStatus.INVALID_IDENTIFIER, response.status());
            assertTrue(response.body().isEmpty());
        });

        assertEquals(0, calls.get());
    }

    @Test
    void propagatesSourceConsistencyFailuresInsteadOfReturningAResponse() {
        AtomicInteger calls = new AtomicInteger();
        FinalSynthesisReadEndpoint endpoint = endpoint(id -> {
            calls.incrementAndGet();
            return Optional.of(validSynthesis(new FinalSynthesisId("different-synthesis-sentinel")));
        });

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> endpoint.read(SYNTHESIS_ID.value()));

        assertEquals("source returned a synthesis with a different id", failure.getMessage());
        assertEquals(1, calls.get());
    }

    @Test
    void rejectsNullServiceAndInvalidResponseStatusBodyCombinations() {
        NullPointerException endpointFailure = assertThrows(NullPointerException.class,
                () -> new FinalSynthesisReadEndpoint(null));
        assertEquals("service", endpointFailure.getMessage());

        FinalSynthesisReadModel body = FinalSynthesisReadModel.from(validSynthesis(SYNTHESIS_ID));
        assertThrows(NullPointerException.class, () -> FinalSynthesisReadResponse.found(null));
        assertThrows(IllegalArgumentException.class, () -> FinalSynthesisReadResponse.of(
                FinalSynthesisReadResponseStatus.FOUND, Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> FinalSynthesisReadResponse.of(
                FinalSynthesisReadResponseStatus.NOT_FOUND, Optional.of(body)));
        assertThrows(IllegalArgumentException.class, () -> FinalSynthesisReadResponse.of(
                FinalSynthesisReadResponseStatus.INVALID_IDENTIFIER, Optional.of(body)));
        assertThrows(NullPointerException.class, () -> FinalSynthesisReadResponse.of(null, Optional.empty()));
        assertThrows(NullPointerException.class, () -> FinalSynthesisReadResponse.of(
                FinalSynthesisReadResponseStatus.NOT_FOUND, null));
    }

    @Test
    void responseToStringRedactsTheStatusBodyNarrativeAndEvidenceSentinels() {
        FinalSynthesisReadResponse response = FinalSynthesisReadResponse.found(
                FinalSynthesisReadModel.from(validSynthesis(SYNTHESIS_ID)));

        String rendered = response.toString();

        assertTrue(rendered.contains("<provided>"));
        List.of(
                        "FOUND",
                        "endpoint-synthesis-sentinel",
                        "endpoint-project-sentinel",
                        "endpoint-version-sentinel",
                        "endpoint-diff-sentinel",
                        "endpoint-workspace-sentinel",
                        "endpoint-receipt-sentinel",
                        "endpoint-narrative-sentinel",
                        "raw-evidence-sentinel",
                        "2026-07-27T15:00:00Z")
                .forEach(sentinel -> assertFalse(rendered.contains(sentinel), rendered));
    }

    @Test
    void exposesOnlyTheFrozenResponseAndRawIdEndpointSurface() throws NoSuchMethodException {
        assertArrayEquals(new FinalSynthesisReadResponseStatus[] {
                FinalSynthesisReadResponseStatus.FOUND,
                FinalSynthesisReadResponseStatus.NOT_FOUND,
                FinalSynthesisReadResponseStatus.INVALID_IDENTIFIER
        }, FinalSynthesisReadResponseStatus.values());

        assertTrue(Modifier.isPublic(FinalSynthesisReadResponse.class.getModifiers()));
        assertTrue(Modifier.isFinal(FinalSynthesisReadResponse.class.getModifiers()));
        for (Constructor<?> constructor : FinalSynthesisReadResponse.class.getDeclaredConstructors()) {
            assertTrue(Modifier.isPrivate(constructor.getModifiers()));
        }
        assertEquals(Set.of(
                        "found(io.paperagent.v2.api.synthesis.FinalSynthesisReadModel)",
                        "notFound()",
                        "invalidIdentifier()",
                        "status()",
                        "body()",
                        "toString()"),
                publicMethodSignatures(FinalSynthesisReadResponse.class));
        assertEquals(FinalSynthesisReadResponseStatus.class,
                FinalSynthesisReadResponse.class.getDeclaredMethod("status").getReturnType());
        assertEquals(Optional.class, FinalSynthesisReadResponse.class.getDeclaredMethod("body").getReturnType());
        assertEquals(FinalSynthesisReadModel.class, parameterizedArgument(
                FinalSynthesisReadResponse.class.getDeclaredMethod("body").getGenericReturnType()));
        assertEquals(2, FinalSynthesisReadResponse.class.getDeclaredFields().length);
        for (Field field : FinalSynthesisReadResponse.class.getDeclaredFields()) {
            assertTrue(Modifier.isPrivate(field.getModifiers()));
            assertTrue(Modifier.isFinal(field.getModifiers()));
        }

        assertTrue(Modifier.isPublic(FinalSynthesisReadEndpoint.class.getModifiers()));
        assertTrue(Modifier.isFinal(FinalSynthesisReadEndpoint.class.getModifiers()));
        assertEquals(Set.of("read(java.lang.String)"), publicMethodSignatures(FinalSynthesisReadEndpoint.class));
        assertEquals(FinalSynthesisReadResponse.class,
                FinalSynthesisReadEndpoint.class.getDeclaredMethod("read", String.class).getReturnType());
        assertEquals(1, FinalSynthesisReadEndpoint.class.getDeclaredFields().length);
        Field service = FinalSynthesisReadEndpoint.class.getDeclaredFields()[0];
        assertTrue(Modifier.isPrivate(service.getModifiers()));
        assertTrue(Modifier.isFinal(service.getModifiers()));
        assertEquals(FinalSynthesisReadService.class, service.getType());
    }

    @Test
    void keepsProductionImportsAndOuterConcernsOutsideTheFrozenFacade() throws IOException {
        List<String> sources = List.of(
                Files.readString(Path.of("src/main/java/io/paperagent/v2/api/synthesis/FinalSynthesisReadResponseStatus.java")),
                Files.readString(Path.of("src/main/java/io/paperagent/v2/api/synthesis/FinalSynthesisReadResponse.java")),
                Files.readString(Path.of("src/main/java/io/paperagent/v2/api/synthesis/FinalSynthesisReadEndpoint.java")));

        for (String source : sources) {
            for (String importedType : source.lines()
                    .filter(line -> line.startsWith("import "))
                    .map(line -> line.substring("import ".length(), line.length() - 1))
                    .toList()) {
                assertTrue(importedType.startsWith("java.")
                        || importedType.startsWith("io.paperagent.v2.contracts.")
                        || importedType.startsWith("io.paperagent.v2.api.synthesis."), importedType);
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
                            "http",
                            "network",
                            "authentication",
                            "ProjectVersion publication",
                            "paperagent_redo")
                    .forEach(forbidden -> assertFalse(source.contains(forbidden), forbidden));
        }
    }

    private static FinalSynthesisReadEndpoint endpoint(FinalSynthesisReadSource source) {
        return new FinalSynthesisReadEndpoint(new FinalSynthesisReadService(source));
    }

    private static FinalSynthesis validSynthesis(FinalSynthesisId id) {
        WorkspaceDiff workspaceDiff = new WorkspaceDiff(
                new DiffId("endpoint-diff-sentinel"),
                new WorkspaceRef(new WorkspaceId("endpoint-workspace-sentinel"), SOURCE_PROJECT_VERSION),
                List.of(new WorkspaceDiffEntry(
                        DiffKind.ADD,
                        new ProjectPath("summary.txt"),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.of(new ContentHash(
                                "sha256", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")),
                        Map.of("raw-evidence-sentinel", "artifact-sentinel"))),
                Instant.parse("2026-07-27T14:00:00Z"));
        return new FinalSynthesis(
                id,
                new TaskFrameId("endpoint-task-frame-sentinel"),
                new PlanId("endpoint-plan-sentinel"),
                new PlanRevisionId("endpoint-plan-revision-sentinel"),
                Optional.of(SOURCE_PROJECT_VERSION),
                workspaceDiff,
                List.of(new ReceiptId("endpoint-receipt-sentinel")),
                "endpoint-narrative-sentinel",
                Instant.parse("2026-07-27T15:00:00Z"));
    }

    private static Set<String> publicMethodSignatures(Class<?> type) {
        return Arrays.stream(type.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .map(FinalSynthesisReadEndpointTest::methodSignature)
                .collect(Collectors.toSet());
    }

    private static Class<?> parameterizedArgument(Type type) {
        return (Class<?>) ((ParameterizedType) type).getActualTypeArguments()[0];
    }

    private static String methodSignature(Method method) {
        return method.getName() + "(" + Arrays.stream(method.getParameterTypes())
                .map(Class::getName)
                .collect(Collectors.joining(",")) + ")";
    }
}
