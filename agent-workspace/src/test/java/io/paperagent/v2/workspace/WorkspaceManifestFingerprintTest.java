package io.paperagent.v2.workspace;

import io.paperagent.v2.contracts.ContentHash;
import io.paperagent.v2.contracts.ProjectPath;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.paperagent.v2.workspace.WorkspaceTestSupport.VERSION;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class WorkspaceManifestFingerprintTest {
    @Test
    void canonicalEncodingIgnoresListAndMapIterationOrder() {
        ProjectFileSnapshot alpha = file("alpha.txt", "alpha", ordered(
                "zeta", "last",
                "alpha", "first"));
        ProjectFileSnapshot beta = file("nested/beta.txt", "beta", ordered(
                "two", "2",
                "one", "1"));
        ProjectVersionSnapshot first = new ProjectVersionSnapshot(
                VERSION,
                List.of(beta, alpha),
                ordered("zeta", "last", "alpha", "first"));
        ProjectVersionSnapshot reordered = new ProjectVersionSnapshot(
                VERSION,
                List.of(alpha, beta),
                ordered("alpha", "first", "zeta", "last"));

        assertEquals(
                WorkspaceManifestFingerprint.calculate(first),
                WorkspaceManifestFingerprint.calculate(reordered));
    }

    @Test
    void everyAuthoritativeManifestComponentChangesFingerprint() {
        ProjectVersionSnapshot baseline = snapshot(
                List.of(file("paper.txt", "paper", Map.of("kind", "text"))),
                Map.of("source", "fixture"));
        ContentHash expected = WorkspaceManifestFingerprint.calculate(baseline);

        assertNotEquals(expected, fingerprint(snapshot(
                List.of(file("renamed.txt", "paper", Map.of("kind", "text"))),
                Map.of("source", "fixture"))));
        assertNotEquals(expected, fingerprint(snapshot(
                List.of(file("paper.txt", "paper!", Map.of("kind", "text"))),
                Map.of("source", "fixture"))));
        assertNotEquals(expected, fingerprint(snapshot(
                List.of(file("paper.txt", "paper", Map.of("kind", "binary"))),
                Map.of("source", "fixture"))));
        assertNotEquals(expected, fingerprint(snapshot(
                List.of(file("paper.txt", "paper", Map.of("kind", "text"))),
                Map.of("source", "changed"))));
        assertNotEquals(expected, fingerprint(snapshot(
                List.of(
                        file("paper.txt", "paper", Map.of("kind", "text")),
                        file("second.txt", "", Map.of())),
                Map.of("source", "fixture"))));
        assertNotEquals(expected, WorkspaceManifestFingerprint.calculate(
                new ProjectVersionSnapshot(
                        new io.paperagent.v2.contracts.ProjectVersionRef(
                                "project-1",
                                "version-2"),
                        baseline.files(),
                        baseline.metadata())));
    }

    @Test
    void emptyManifestHasStableVersionedFingerprint() {
        ProjectVersionSnapshot empty = snapshot(List.of(), Map.of());

        /*
         * Independently checked bytes are: length-prefixed v1 domain,
         * "project-1", "version-1", then big-endian zero snapshot-metadata
         * entry count and zero file count.
         */
        assertEquals(
                new ContentHash(
                        "sha256",
                        "85ca9adb9853c4afde5efcd48660e2ceaaa1425b12d6f8ca2986a901723e878b"),
                WorkspaceManifestFingerprint.calculate(empty));
    }

    private static ContentHash fingerprint(ProjectVersionSnapshot snapshot) {
        return WorkspaceManifestFingerprint.calculate(snapshot);
    }

    private static ProjectVersionSnapshot snapshot(
            List<ProjectFileSnapshot> files,
            Map<String, String> metadata) {
        return new ProjectVersionSnapshot(VERSION, files, metadata);
    }

    private static ProjectFileSnapshot file(
            String path,
            String content,
            Map<String, String> metadata) {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        return new ProjectFileSnapshot(
                new ProjectPath(path),
                bytes,
                WorkspaceHashes.sha256(bytes),
                metadata);
    }

    private static Map<String, String> ordered(String... keysAndValues) {
        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        for (int index = 0; index < keysAndValues.length; index += 2) {
            result.put(keysAndValues[index], keysAndValues[index + 1]);
        }
        return result;
    }
}
