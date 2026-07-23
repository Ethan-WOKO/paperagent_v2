package io.paperagent.v2.e2e;

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

class Wave2ModuleBoundaryTest {
    private static final Set<String> APPROVED_DEPENDENCIES = Set.of(
            "io.paperagent.v2:agent-contracts",
            "io.paperagent.v2:agent-persistence",
            "io.paperagent.v2:agent-workspace",
            "io.paperagent.v2:agent-sandbox",
            "io.paperagent.v2:agent-providers",
            "org.junit.jupiter:junit-jupiter");

    @Test
    void pomContainsOnlyApprovedTestScopeDependenciesAndNoProductionTree() throws Exception {
        Path module = moduleDirectory();
        var document = DocumentBuilderFactory.newInstance()
                .newDocumentBuilder()
                .parse(module.resolve("pom.xml").toFile());
        var dependencies = document.getElementsByTagName("dependency");
        List<String> coordinates = new ArrayList<>();
        for (int index = 0; index < dependencies.getLength(); index++) {
            Element dependency = (Element) dependencies.item(index);
            coordinates.add(
                    text(dependency, "groupId") + ":" + text(dependency, "artifactId"));
            assertEquals("test", text(dependency, "scope"));
        }
        assertEquals(APPROVED_DEPENDENCIES, Set.copyOf(coordinates));
        assertEquals(APPROVED_DEPENDENCIES.size(), coordinates.size());
        assertFalse(Files.exists(module.resolve("src/main")));
    }

    @Test
    void testSourcesUseOnlyApprovedBoundariesAndDeterministicInputs() throws Exception {
        Path sourceRoot = moduleDirectory().resolve("src/test/java");
        assertTrue(Files.isDirectory(sourceRoot));
        List<String> forbiddenMarkers = List.of(
                "io.paperagent.v2." + "runtime",
                "io.paperagent.v2." + "app",
                "org.spring" + "framework",
                "com.fasterxml" + ".",
                "ok" + "http",
                "retro" + "fit",
                "e" + "2b",
                "paperagent." + "v1",
                "java." + "net.",
                "Http" + "Client",
                "System." + "getenv",
                "System." + "getProperty",
                "Instant." + "now(",
                "Clock." + "system",
                "UUID." + "randomUUID",
                "Thread." + "sleep",
                "Process" + "Builder",
                "Runtime." + "getRuntime",
                "\"" + "." + "env",
                "Secret" + "Ref",
                "new Tool" + "Call(",
                "new Execution" + "Receipt(",
                "new Event" + "Envelope(",
                "new Check" + "point(");

        try (var paths = Files.walk(sourceRoot)) {
            for (Path sourcePath : paths
                    .filter(path -> path.toString().endsWith(".java"))
                    .toList()) {
                String source = Files.readString(sourcePath);
                for (String marker : forbiddenMarkers) {
                    assertFalse(
                            source.contains(marker),
                            () -> sourcePath + " contains forbidden marker " + marker);
                }
                for (String line : source.lines().toList()) {
                    String trimmed = line.trim();
                    if (trimmed.startsWith("import ")) {
                        assertTrue(
                                approvedImport(trimmed),
                                () -> sourcePath + " crosses module boundary: " + trimmed);
                    }
                }
            }
        }
    }

    private static boolean approvedImport(String declaration) {
        return declaration.startsWith("import java.")
                || declaration.startsWith("import javax.xml.")
                || declaration.startsWith("import org.w3c.dom.")
                || declaration.startsWith("import org.junit.jupiter.")
                || declaration.startsWith("import static org.junit.jupiter.")
                || declaration.startsWith("import io.paperagent.v2.contracts.")
                || declaration.startsWith("import io.paperagent.v2.persistence.")
                || declaration.startsWith("import io.paperagent.v2.workspace.")
                || declaration.startsWith("import io.paperagent.v2.sandbox.")
                || declaration.startsWith("import io.paperagent.v2.providers.");
    }

    private static Path moduleDirectory() {
        Path current = Path.of("").toAbsolutePath();
        if (Files.isRegularFile(current.resolve("pom.xml"))
                && current.getFileName().toString().equals("e2e-tests")) {
            return current;
        }
        return current.resolve("e2e-tests");
    }

    private static String text(Element parent, String name) {
        var elements = parent.getElementsByTagName(name);
        return elements.getLength() == 0 ? "" : elements.item(0).getTextContent().trim();
    }
}
