package io.paperagent.v2.runtime.routing;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;

import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeModuleBoundaryTest {
    private static final String PERSISTENCE_PREFIX =
            "io.paperagent.v2.persistence";
    private static final Set<String> ALLOWED_BOOTSTRAP_PERSISTENCE_IMPORTS = Set.of(
            "import io.paperagent.v2.persistence.PlanBootstrapRepository;",
            "import io.paperagent.v2.persistence.PersistenceResult;",
            "import io.paperagent.v2.persistence.PersistedPlanBootstrap;");
    private static final Set<String> ALLOWED_EXECUTION_PERSISTENCE_IMPORTS = Set.of(
            "import io.paperagent.v2.persistence.PersistenceResult;",
            "import io.paperagent.v2.persistence.PersistedPlanBootstrap;",
            "import io.paperagent.v2.persistence.PersistenceOutcome;",
            "import io.paperagent.v2.persistence.PersistenceFailure;");
    private static final Set<String>
            ALLOWED_EXECUTION_START_PERSISTENCE_IMPORTS = Set.of(
                    "import io.paperagent.v2.persistence"
                            + ".ExecutionStartRepository;",
                    "import io.paperagent.v2.persistence"
                            + ".ExecutionStartRequest;",
                    "import io.paperagent.v2.persistence.LeaseRecord;",
                    "import io.paperagent.v2.persistence.LeaseRepository;",
                    "import io.paperagent.v2.persistence"
                            + ".PersistedExecutionStart;",
                    "import io.paperagent.v2.persistence"
                            + ".PersistedPlanBootstrap;",
                    "import io.paperagent.v2.persistence.PersistenceFailure;",
                    "import io.paperagent.v2.persistence.PersistenceOutcome;",
                    "import io.paperagent.v2.persistence.PersistenceResult;");
    private static final Set<String>
            ALLOWED_RECOVERY_MATERIALIZATION_PERSISTENCE_IMPORTS = Set.of(
                    "import io.paperagent.v2.persistence"
                            + ".PersistedExecutionStartReady;");
    private static final List<String> FORBIDDEN_SOURCE_MARKERS = List.of(
            PERSISTENCE_PREFIX,
            "io.paperagent.v2.workspace",
            "io.paperagent.v2.sandbox",
            "io.paperagent.v2.providers",
            "io.paperagent.v2.app",
            "org.springframework.",
            "com.fasterxml.",
            "okhttp",
            "retrofit",
            "openai",
            "anthropic",
            "e2b",
            "paperagent.v1",
            "PlanAgentService",
            "PlanningAgentPlanner",
            "CompletionVerifier",
            "Candidate",
            "java.net.",
            "java.net.http",
            "java.io.",
            "java.nio.file.",
            "ProcessBuilder",
            "Runtime.getRuntime",
            "System.getenv",
            "System.getProperty",
            "SecretRef",
            "\".env",
            "System.currentTimeMillis",
            "Instant.now",
            "Clock.",
            "UUID.randomUUID",
            "Thread.sleep");

    @Test
    void productionDependsOnlyOnContractsPersistenceAndJdk() throws Exception {
        Path module = moduleDirectory();
        var document = DocumentBuilderFactory.newInstance()
                .newDocumentBuilder()
                .parse(module.resolve("pom.xml").toFile());
        var dependencies = document.getElementsByTagName("dependency");
        List<String> productionDependencies = new ArrayList<>();
        List<String> testDependencies = new ArrayList<>();
        for (int index = 0; index < dependencies.getLength(); index++) {
            Element dependency = (Element) dependencies.item(index);
            String coordinate =
                    text(dependency, "groupId") + ":" + text(dependency, "artifactId");
            if ("test".equals(text(dependency, "scope"))) {
                testDependencies.add(coordinate);
            } else {
                productionDependencies.add(coordinate);
            }
        }
        assertEquals(
                List.of(
                        "io.paperagent.v2:agent-contracts",
                        "io.paperagent.v2:agent-persistence"),
                productionDependencies);
        assertEquals(List.of("org.junit.jupiter:junit-jupiter"), testDependencies);

        Path sourceRoot = module.resolve("src/main/java");
        assertTrue(Files.isDirectory(sourceRoot));
        try (var paths = Files.walk(sourceRoot)) {
            for (Path sourcePath : paths
                    .filter(path -> path.toString().endsWith(".java"))
                    .toList()) {
                Set<String> allowedPersistenceImports =
                        allowedPersistenceImports(sourceRoot, sourcePath);
                for (String line : Files.readAllLines(sourcePath)) {
                    String trimmed = line.trim();
                    if (trimmed.startsWith("import ")) {
                        assertTrue(
                                trimmed.startsWith("import java.")
                                        || trimmed.startsWith(
                                                "import io.paperagent.v2.contracts.")
                                        || trimmed.startsWith(
                                                "import io.paperagent.v2.runtime.")
                                        || allowedPersistenceImports.contains(trimmed),
                                () -> sourcePath + " crosses production boundary: " + trimmed);
                    }
                }
            }
        }
    }

    @Test
    void productionHasNoForbiddenRuntimeSideEffectsOrV1Markers() throws Exception {
        Path sourceRoot = moduleDirectory().resolve("src/main/java");
        try (var paths = Files.walk(sourceRoot)) {
            for (Path sourcePath : paths
                    .filter(path -> path.toString().endsWith(".java"))
                    .toList()) {
                String source = Files.readString(sourcePath).toLowerCase();
                for (String marker : FORBIDDEN_SOURCE_MARKERS) {
                    if (marker.equals(PERSISTENCE_PREFIX)) {
                        continue;
                    }
                    assertFalse(
                            source.contains(marker.toLowerCase()),
                            () -> sourcePath + " contains forbidden marker " + marker);
                }
                for (String line : Files.readAllLines(sourcePath)) {
                    if (line.contains(PERSISTENCE_PREFIX)) {
                        String trimmed = line.trim();
                        assertTrue(
                                allowedPersistenceImports(sourceRoot, sourcePath)
                                        .contains(trimmed),
                                () -> sourcePath
                                        + " contains non-allowlisted persistence reference: "
                                        + trimmed);
                    }
                }
            }
        }
    }

    @Test
    void persistenceImportsArePackageExactAndFailClosed() {
        Path sourceRoot = Path.of("src", "main", "java");
        Path bootstrapSource = sourceRoot.resolve(Path.of(
                "io",
                "paperagent",
                "v2",
                "runtime",
                "bootstrap",
                "Bootstrap.java"));
        Path executionSource = sourceRoot.resolve(Path.of(
                "io",
                "paperagent",
                "v2",
                "runtime",
                "execution",
                "Gate.java"));
        Path executionStartSource = sourceRoot.resolve(Path.of(
                "io",
                "paperagent",
                "v2",
                "runtime",
                "execution",
                "start",
                "Starter.java"));
        Path recoveryMaterializationSource = sourceRoot.resolve(Path.of(
                "io",
                "paperagent",
                "v2",
                "runtime",
                "execution",
                "recovery",
                "materialization",
                "Materializer.java"));
        Path otherRuntimeSource = sourceRoot.resolve(Path.of(
                "io",
                "paperagent",
                "v2",
                "runtime",
                "planning",
                "Planner.java"));

        for (String allowed : ALLOWED_BOOTSTRAP_PERSISTENCE_IMPORTS) {
            assertTrue(allowedPersistenceImports(sourceRoot, bootstrapSource)
                    .contains(allowed));
        }
        for (String allowed : ALLOWED_EXECUTION_PERSISTENCE_IMPORTS) {
            assertTrue(allowedPersistenceImports(sourceRoot, executionSource)
                    .contains(allowed));
        }
        for (String allowed
                : ALLOWED_EXECUTION_START_PERSISTENCE_IMPORTS) {
            assertTrue(
                    allowedPersistenceImports(
                            sourceRoot,
                            executionStartSource).contains(allowed));
        }
        assertEquals(
                ALLOWED_RECOVERY_MATERIALIZATION_PERSISTENCE_IMPORTS,
                allowedPersistenceImports(
                        sourceRoot,
                        recoveryMaterializationSource));

        assertFalse(allowedPersistenceImports(sourceRoot, bootstrapSource)
                .contains(
                        "import io.paperagent.v2.persistence.PersistenceOutcome;"));
        assertFalse(allowedPersistenceImports(sourceRoot, executionSource)
                .contains(
                        "import io.paperagent.v2.persistence.PlanBootstrapRepository;"));
        assertFalse(allowedPersistenceImports(sourceRoot, executionSource)
                .contains(
                        "import io.paperagent.v2.persistence.InMemoryPersistence;"));
        assertFalse(allowedPersistenceImports(sourceRoot, executionSource)
                .contains(
                        "import io.paperagent.v2.persistence.LeaseRepository;"));
        assertFalse(allowedPersistenceImports(sourceRoot, executionSource)
                .contains(
                        "import io.paperagent.v2.persistence"
                                + ".ExecutionStartRepository;"));
        assertFalse(
                allowedPersistenceImports(sourceRoot, executionStartSource)
                        .contains(
                                "import io.paperagent.v2.persistence"
                                        + ".InMemoryPersistence;"));
        assertFalse(
                allowedPersistenceImports(
                        sourceRoot,
                        recoveryMaterializationSource)
                        .contains(
                                "import io.paperagent.v2.persistence"
                                        + ".PersistedExecutionStartCommitted;"));
        assertFalse(
                allowedPersistenceImports(sourceRoot, executionSource)
                        .contains(
                                "import io.paperagent.v2.persistence"
                                        + ".PersistedExecutionStartReady;"));
        assertFalse(allowedPersistenceImports(sourceRoot, executionSource)
                .contains("import io.paperagent.v2.persistence.*;"));
        assertFalse(allowedPersistenceImports(sourceRoot, executionSource)
                .contains(
                        "import static io.paperagent.v2.persistence"
                                + ".PersistenceOutcome.APPLIED;"));
        assertFalse(allowedPersistenceImports(sourceRoot, executionSource)
                .contains(
                        "return io.paperagent.v2.persistence"
                                + ".PersistenceResult.applied(value);"));
        assertTrue(
                allowedPersistenceImports(sourceRoot, otherRuntimeSource)
                        .isEmpty());
    }

    @Test
    void executionDoesNotUseSuccessfulShortcut() throws Exception {
        Path module = moduleDirectory();
        for (Path sourceRoot : List.of(
                module.resolve("src/main/java"),
                module.resolve("src/test/java"))) {
            Path executionRoot = sourceRoot.resolve(
                    Path.of("io", "paperagent", "v2", "runtime", "execution"));
            if (!Files.isDirectory(executionRoot)) {
                continue;
            }
            try (var paths = Files.walk(executionRoot)) {
                for (Path sourcePath : paths
                        .filter(path -> path.toString().endsWith(".java"))
                        .toList()) {
                    String source = Files.readString(sourcePath);
                    assertFalse(
                            source.contains(".successful("),
                            () -> sourcePath
                                    + " must classify persistence outcomes explicitly");
                }
            }
        }
    }

    private static boolean isBootstrapSource(Path sourceRoot, Path sourcePath) {
        Path relative = sourceRoot.relativize(sourcePath);
        return relative.startsWith(
                Path.of("io", "paperagent", "v2", "runtime", "bootstrap"));
    }

    private static boolean isExecutionSource(Path sourceRoot, Path sourcePath) {
        Path relative = sourceRoot.relativize(sourcePath);
        return relative.startsWith(
                Path.of("io", "paperagent", "v2", "runtime", "execution"));
    }

    private static boolean isExecutionStartSource(
            Path sourceRoot,
            Path sourcePath) {
        Path relative = sourceRoot.relativize(sourcePath);
        return relative.startsWith(Path.of(
                "io",
                "paperagent",
                "v2",
                "runtime",
                "execution",
                "start"));
    }

    private static boolean isRecoveryMaterializationSource(
            Path sourceRoot,
            Path sourcePath) {
        Path relative = sourceRoot.relativize(sourcePath);
        return relative.startsWith(Path.of(
                "io",
                "paperagent",
                "v2",
                "runtime",
                "execution",
                "recovery",
                "materialization"));
    }

    private static Set<String> allowedPersistenceImports(
            Path sourceRoot,
            Path sourcePath) {
        if (isBootstrapSource(sourceRoot, sourcePath)) {
            return ALLOWED_BOOTSTRAP_PERSISTENCE_IMPORTS;
        }
        if (isRecoveryMaterializationSource(sourceRoot, sourcePath)) {
            return ALLOWED_RECOVERY_MATERIALIZATION_PERSISTENCE_IMPORTS;
        }
        if (isExecutionStartSource(sourceRoot, sourcePath)) {
            return ALLOWED_EXECUTION_START_PERSISTENCE_IMPORTS;
        }
        if (isExecutionSource(sourceRoot, sourcePath)) {
            return ALLOWED_EXECUTION_PERSISTENCE_IMPORTS;
        }
        return Set.of();
    }

    private static Path moduleDirectory() {
        Path current = Path.of("").toAbsolutePath();
        if (Files.isRegularFile(current.resolve("pom.xml"))
                && current.getFileName().toString().equals("agent-runtime")) {
            return current;
        }
        return current.resolve("agent-runtime");
    }

    private static String text(Element parent, String name) {
        var elements = parent.getElementsByTagName(name);
        return elements.getLength() == 0 ? "" : elements.item(0).getTextContent().trim();
    }
}
