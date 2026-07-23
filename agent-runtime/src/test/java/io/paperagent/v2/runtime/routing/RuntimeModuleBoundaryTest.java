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
                boolean bootstrapSource = isBootstrapSource(sourceRoot, sourcePath);
                for (String line : Files.readAllLines(sourcePath)) {
                    String trimmed = line.trim();
                    if (trimmed.startsWith("import ")) {
                        assertTrue(
                                trimmed.startsWith("import java.")
                                        || trimmed.startsWith(
                                                "import io.paperagent.v2.contracts.")
                                        || trimmed.startsWith(
                                                "import io.paperagent.v2.runtime.")
                                        || bootstrapSource
                                                && ALLOWED_BOOTSTRAP_PERSISTENCE_IMPORTS
                                                        .contains(trimmed),
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
                boolean bootstrapSource = isBootstrapSource(sourceRoot, sourcePath);
                String source = Files.readString(sourcePath).toLowerCase();
                for (String marker : FORBIDDEN_SOURCE_MARKERS) {
                    if (marker.equals(PERSISTENCE_PREFIX) && bootstrapSource) {
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
                                bootstrapSource
                                        && ALLOWED_BOOTSTRAP_PERSISTENCE_IMPORTS
                                                .contains(trimmed),
                                () -> sourcePath
                                        + " contains non-allowlisted persistence reference: "
                                        + trimmed);
                    }
                }
            }
        }
    }

    private static boolean isBootstrapSource(Path sourceRoot, Path sourcePath) {
        Path relative = sourceRoot.relativize(sourcePath);
        return relative.startsWith(
                Path.of("io", "paperagent", "v2", "runtime", "bootstrap"));
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
