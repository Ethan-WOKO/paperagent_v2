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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class FinalSynthesisReadModelTest {
    private static final ProjectVersionRef SOURCE_PROJECT_VERSION =
            new ProjectVersionRef("project-source-sentinel", "version-source-sentinel");
    private static final WorkspaceDiff WORKSPACE_DIFF = new WorkspaceDiff(
            new DiffId("diff-delivery-sentinel"),
            new WorkspaceRef(new WorkspaceId("workspace-delivery-sentinel"), SOURCE_PROJECT_VERSION),
            List.of(new WorkspaceDiffEntry(
                    DiffKind.ADD,
                    new ProjectPath("summary.txt"),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.of(new ContentHash(
                            "sha256", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")),
                    Map.of("tool-output-sentinel", "artifact-sentinel"))),
            Instant.parse("2026-07-27T08:00:00Z"));

    @Test
    void mapsEveryAllowedSummaryFieldWithOptionalSourceAndReceiptOrder() {
        FinalSynthesis synthesis = validSynthesis(
                Optional.of(SOURCE_PROJECT_VERSION),
                List.of(new ReceiptId("receipt-first-sentinel"), new ReceiptId("receipt-second-sentinel")));

        FinalSynthesisReadModel model = FinalSynthesisReadModel.from(synthesis);

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
    }

    @Test
    void snapshotsOptionalAndReceiptReferencesWithAnImmutableOrderedList() {
        FinalSynthesis synthesis = validSynthesis(
                Optional.of(SOURCE_PROJECT_VERSION),
                List.of(new ReceiptId("receipt-first-sentinel"), new ReceiptId("receipt-second-sentinel")));

        FinalSynthesisReadModel model = FinalSynthesisReadModel.from(synthesis);

        assertNotSame(synthesis.sourceProjectVersion().get(), model.sourceProjectVersion().get());
        assertNotSame(synthesis.receiptIds(), model.receiptIds());
        assertNotSame(synthesis.receiptIds().get(0), model.receiptIds().get(0));
        assertEquals(List.of("receipt-first-sentinel", "receipt-second-sentinel"),
                model.receiptIds().stream().map(ReceiptId::value).toList());
        assertThrows(UnsupportedOperationException.class,
                () -> model.receiptIds().add(new ReceiptId("receipt-third-sentinel")));
    }

    @Test
    void supportsAnAbsentOptionalSourceReference() {
        FinalSynthesisReadModel model = FinalSynthesisReadModel.from(validSynthesis(Optional.empty(), List.of()));

        assertTrue(model.sourceProjectVersion().isEmpty());
        assertTrue(model.receiptIds().isEmpty());
    }

    @Test
    void rejectsNullSynthesisDeterministically() {
        NullPointerException failure = assertThrows(NullPointerException.class,
                () -> FinalSynthesisReadModel.from(null));

        assertEquals("synthesis", failure.getMessage());
    }

    @Test
    void toStringRedactsNarrativeAndEveryDeliveryOrEvidenceSentinel() {
        FinalSynthesisReadModel model = FinalSynthesisReadModel.from(validSynthesis(
                Optional.of(SOURCE_PROJECT_VERSION), List.of(new ReceiptId("receipt-first-sentinel"))));

        String rendered = model.toString();

        assertTrue(rendered.contains("<provided>"));
        List.of(
                        "synthesis-sentinel",
                        "task-frame-sentinel",
                        "plan-sentinel",
                        "plan-revision-sentinel",
                        "project-source-sentinel",
                        "version-source-sentinel",
                        "diff-delivery-sentinel",
                        "workspace-delivery-sentinel",
                        "receipt-first-sentinel",
                        "narrative-sentinel",
                        "2026-07-27T09:00:00Z")
                .forEach(sentinel -> assertFalse(rendered.contains(sentinel), rendered));
    }

    @Test
    void exposesOnlyTheFrozenPublicReadSurfaceAndNoRawEvidenceTypes() {
        assertTrue(Modifier.isPublic(FinalSynthesisReadModel.class.getModifiers()));
        assertTrue(Modifier.isFinal(FinalSynthesisReadModel.class.getModifiers()));
        for (Constructor<?> constructor : FinalSynthesisReadModel.class.getDeclaredConstructors()) {
            assertTrue(Modifier.isPrivate(constructor.getModifiers()));
        }

        Set<String> publicMethods = java.util.Arrays.stream(FinalSynthesisReadModel.class.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .map(FinalSynthesisReadModelTest::methodSignature)
                .collect(Collectors.toSet());

        assertEquals(Set.of(
                        "from(io.paperagent.v2.contracts.FinalSynthesis)",
                        "synthesisId()",
                        "taskFrameId()",
                        "planId()",
                        "planRevisionId()",
                        "sourceProjectVersion()",
                        "workspaceDiffId()",
                        "workspaceId()",
                        "receiptIds()",
                        "narrative()",
                        "observedAt()",
                        "toString()"),
                publicMethods);

        Set<String> forbiddenTypeNames = Set.of(
                "FinalSynthesis",
                "WorkspaceDiff",
                "WorkspaceDiffEntry",
                "ExecutionReceipt",
                "OutputCapture",
                "ArtifactRef",
                "ContentHash");
        for (Method method : FinalSynthesisReadModel.class.getDeclaredMethods()) {
            if (Modifier.isPublic(method.getModifiers()) && !method.getName().equals("from")) {
                assertFalse(forbiddenTypeNames.contains(method.getReturnType().getSimpleName()));
                for (Class<?> parameterType : method.getParameterTypes()) {
                    assertFalse(forbiddenTypeNames.contains(parameterType.getSimpleName()));
                }
            }
        }
    }

    @Test
    void keepsProductionImportsAndPomDependenciesInsideTheFrozenBoundary() throws IOException {
        String source = Files.readString(Path.of(
                "src/main/java/io/paperagent/v2/api/synthesis/FinalSynthesisReadModel.java"));
        String pom = Files.readString(Path.of("pom.xml"));

        for (String importedType : source.lines()
                .filter(line -> line.startsWith("import "))
                .map(line -> line.substring("import ".length(), line.length() - 1))
                .toList()) {
            assertTrue(importedType.startsWith("java.")
                    || importedType.startsWith("io.paperagent.v2.contracts."), importedType);
        }
        List.of("agent-runtime", "agent-persistence", "agent-workspace", "agent-sandbox", "agent-providers",
                        "spring", "javax.", "jakarta.")
                .forEach(forbidden -> assertFalse(source.contains(forbidden), forbidden));
        List<String> dependencyBlocks = java.util.Arrays.stream(pom.split("<dependency>"))
                .skip(1)
                .map(block -> block.substring(0, block.indexOf("</dependency>")))
                .toList();
        assertEquals(2, dependencyBlocks.size());
        assertEquals(Set.of("agent-contracts", "junit-jupiter"), dependencyBlocks.stream()
                .map(block -> block.substring(
                        block.indexOf("<artifactId>") + "<artifactId>".length(),
                        block.indexOf("</artifactId>")))
                .collect(Collectors.toSet()));
        String contractsDependency = dependencyBlocks.stream()
                .filter(block -> block.contains("<artifactId>agent-contracts</artifactId>"))
                .findFirst()
                .orElseThrow();
        String junitDependency = dependencyBlocks.stream()
                .filter(block -> block.contains("<artifactId>junit-jupiter</artifactId>"))
                .findFirst()
                .orElseThrow();
        assertFalse(contractsDependency.contains("<scope>"));
        assertTrue(junitDependency.contains("<scope>test</scope>"));
    }

    private static FinalSynthesis validSynthesis(
            Optional<ProjectVersionRef> sourceProjectVersion, List<ReceiptId> receiptIds) {
        return new FinalSynthesis(
                new FinalSynthesisId("synthesis-sentinel"),
                new TaskFrameId("task-frame-sentinel"),
                new PlanId("plan-sentinel"),
                new PlanRevisionId("plan-revision-sentinel"),
                sourceProjectVersion,
                WORKSPACE_DIFF,
                receiptIds,
                "narrative-sentinel",
                Instant.parse("2026-07-27T09:00:00Z"));
    }

    private static String methodSignature(Method method) {
        return method.getName() + "(" + java.util.Arrays.stream(method.getParameterTypes())
                .map(Class::getName)
                .collect(Collectors.joining(",")) + ")";
    }
}
