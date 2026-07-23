package io.paperagent.v2.workspace;

import io.paperagent.v2.contracts.ContentHash;
import io.paperagent.v2.contracts.ContractViolationException;
import io.paperagent.v2.contracts.ProjectPath;
import io.paperagent.v2.contracts.ProjectVersionRef;
import io.paperagent.v2.contracts.WorkspaceId;
import io.paperagent.v2.contracts.WorkspaceRef;
import io.paperagent.v2.contracts.ViolationCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static io.paperagent.v2.workspace.WorkspaceTestSupport.VERSION;
import static io.paperagent.v2.workspace.WorkspaceTestSupport.assertBytes;
import static io.paperagent.v2.workspace.WorkspaceTestSupport.file;
import static io.paperagent.v2.workspace.WorkspaceTestSupport.materialize;
import static io.paperagent.v2.workspace.WorkspaceTestSupport.onlyContainer;
import static io.paperagent.v2.workspace.WorkspaceTestSupport.provider;
import static io.paperagent.v2.workspace.WorkspaceTestSupport.snapshot;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkspaceFailureTest {
    @TempDir
    Path root;

    @Test
    void rejectsHashMismatchBeforeCreatingWorkspace() throws Exception {
        ProjectFileSnapshot invalid = new ProjectFileSnapshot(
                new ProjectPath("bad.txt"),
                bytes("actual"),
                WorkspaceHashes.sha256(bytes("declared")),
                Map.of());
        LocalWorkspaceProvider provider = provider(root, snapshot(invalid));

        assertCode(
                WorkspaceErrorCode.HASH_MISMATCH,
                () -> materialize(provider, "hash-mismatch"));
        assertRootEmpty();
    }

    @Test
    void rejectsDuplicateAndPrefixCollisionsAndLeavesNoPartialWorkspace() throws Exception {
        assertMaterializationFailure(
                WorkspaceErrorCode.DUPLICATE_PATH,
                snapshot(file("same.txt", "one"), file("same.txt", "two")),
                "duplicate");
        assertMaterializationFailure(
                WorkspaceErrorCode.PATH_COLLISION,
                snapshot(file("parent", "file"), file("parent/child.txt", "child")),
                "prefix");
    }

    @Test
    void partialMaterializationFailureRemovesEveryCreatedFileAndDirectory() throws Exception {
        ProjectVersionSnapshot snapshot = snapshot(
                file("a-first.txt", "first"),
                file("b-second.txt", "second"));
        AtomicInteger writes = new AtomicInteger();
        LocalWorkspaceProvider provider = new LocalWorkspaceProvider(
                root,
                ignored -> snapshot,
                (source, target, replace) -> {
                    throw new AssertionError("materialization must not use the move strategy");
                },
                (target, content, options) -> {
                    if (writes.incrementAndGet() == 2) {
                        throw new IOException("forced second-file failure");
                    }
                    Files.write(target, content, options);
                });

        assertCode(
                WorkspaceErrorCode.IO_FAILURE,
                () -> materialize(provider, "partial-materialization"));
        assertEquals(2, writes.get());
        assertRootEmpty();
    }

    @Test
    void appliesFileAggregateAndCountLimitsBeforeWriting() throws Exception {
        assertLimitFailure(
                WorkspaceErrorCode.FILE_LIMIT_EXCEEDED,
                new WorkspaceLimits(2, 100, 10),
                snapshot(file("too-big.txt", "abc")),
                "file-limit");
        assertLimitFailure(
                WorkspaceErrorCode.AGGREGATE_LIMIT_EXCEEDED,
                new WorkspaceLimits(10, 3, 10),
                snapshot(file("a.txt", "aa"), file("b.txt", "bb")),
                "aggregate-limit");
        assertLimitFailure(
                WorkspaceErrorCode.FILE_COUNT_LIMIT_EXCEEDED,
                new WorkspaceLimits(10, 100, 1),
                snapshot(file("a.txt", "a"), file("b.txt", "b")),
                "count-limit");
    }

    @Test
    void rejectsWriteGrowthBeforeChangingPriorContent() {
        LocalWorkspaceProvider provider = provider(root, snapshot(file("base.txt", "12")));
        WorkspaceRef workspace = materialize(
                provider,
                "write-limit",
                new WorkspaceLimits(3, 3, 2));

        assertCode(
                WorkspaceErrorCode.FILE_LIMIT_EXCEEDED,
                () -> provider.replace(workspace, new ProjectPath("base.txt"), bytes("1234")));
        assertCode(
                WorkspaceErrorCode.AGGREGATE_LIMIT_EXCEEDED,
                () -> provider.create(workspace, new ProjectPath("new.txt"), bytes("12")));
        assertBytes("12", provider.read(workspace, new ProjectPath("base.txt")));
        assertEquals(1, provider.list(workspace).size());
    }

    @Test
    void failedReplaceFallbackPreservesPriorFileAndRemovesStagingArtifacts() throws Exception {
        ProjectVersionSnapshot snapshot = snapshot(file("base.txt", "before"));
        LocalWorkspaceProvider provider = new LocalWorkspaceProvider(
                root,
                ignored -> snapshot,
                (source, target, replace) -> {
                    throw new IOException("forced failure");
                });
        WorkspaceRef workspace = materialize(provider, "fallback-failure");

        assertCode(
                WorkspaceErrorCode.IO_FAILURE,
                () -> provider.replace(workspace, new ProjectPath("base.txt"), bytes("after")));
        assertBytes("before", provider.read(workspace, new ProjectPath("base.txt")));
        assertDirectoryEmpty(onlyContainer(root).resolve("staging"));
    }

    @Test
    void boundedBackupReadRejectsGrowthWithoutApplyingReplacement() throws Exception {
        ProjectVersionSnapshot snapshot = snapshot(file("base.txt", "before"));
        LocalWorkspaceProvider provider = new LocalWorkspaceProvider(
                root,
                ignored -> snapshot,
                (source, maximum, operation, projectPath) -> {
                    Files.write(source, bytes("!"), java.nio.file.StandardOpenOption.APPEND);
                    return LocalWorkspaceProvider.readBoundedNoFollow(
                            source,
                            maximum,
                            operation,
                            projectPath);
                });
        WorkspaceRef workspace = materialize(
                provider,
                "bounded-backup",
                new WorkspaceLimits(6, 64, 2));
        Path dataFile = onlyContainer(root).resolve("data").resolve("base.txt");

        assertCode(
                WorkspaceErrorCode.FILE_LIMIT_EXCEEDED,
                () -> provider.replace(workspace, new ProjectPath("base.txt"), bytes("after")));

        assertEquals("before!", Files.readString(dataFile));
        assertDirectoryEmpty(onlyContainer(root).resolve("staging"));
    }

    @Test
    void rejectsUnknownWorkspaceMismatchedReferenceAndInvalidOperationShapes() {
        LocalWorkspaceProvider provider = provider(root, snapshot(file("base.txt", "base")));
        WorkspaceRef unknown = new WorkspaceRef(new WorkspaceId("unknown"), VERSION);
        assertCode(WorkspaceErrorCode.WORKSPACE_NOT_FOUND, () -> provider.list(unknown));

        WorkspaceRef workspace = materialize(provider, "known");
        WorkspaceRef wrongSource = new WorkspaceRef(
                workspace.id(),
                new ProjectVersionRef("project-1", "other-version"));
        assertCode(
                WorkspaceErrorCode.WORKSPACE_REFERENCE_MISMATCH,
                () -> provider.list(wrongSource));
        assertCode(
                WorkspaceErrorCode.PATH_ALREADY_EXISTS,
                () -> provider.create(workspace, new ProjectPath("base.txt"), bytes("new")));
        assertCode(
                WorkspaceErrorCode.PATH_NOT_FOUND,
                () -> provider.replace(workspace, new ProjectPath("missing.txt"), bytes("new")));
        assertCode(
                WorkspaceErrorCode.PATH_NOT_FOUND,
                () -> provider.delete(workspace, new ProjectPath("missing.txt")));
    }

    @Test
    void sourceFailuresAndReferenceMismatchAreStable() {
        LocalWorkspaceProvider failing = new LocalWorkspaceProvider(root, ignored -> {
            throw new IllegalStateException("source details");
        });
        assertCode(
                WorkspaceErrorCode.SOURCE_FAILURE,
                () -> failing.materialize(new WorkspaceId("source-failure"), VERSION,
                        WorkspaceTestSupport.GENEROUS_LIMITS));

        ProjectVersionSnapshot wrong = new ProjectVersionSnapshot(
                new ProjectVersionRef("project-1", "wrong"),
                List.of(),
                Map.of());
        LocalWorkspaceProvider mismatch = new LocalWorkspaceProvider(root, ignored -> wrong);
        assertCode(
                WorkspaceErrorCode.SOURCE_REFERENCE_MISMATCH,
                () -> mismatch.materialize(new WorkspaceId("source-mismatch"), VERSION,
                        WorkspaceTestSupport.GENEROUS_LIMITS));
    }

    @Test
    void caseFoldCollisionIsRejectedWhenFilesystemIsCaseInsensitive() throws Exception {
        boolean caseSensitive = probeCaseSensitivity(root);
        LocalWorkspaceProvider provider = provider(root, snapshot(
                file("Readme.md", "one"),
                file("README.md", "two")));

        if (caseSensitive) {
            WorkspaceRef workspace = materialize(provider, "case-sensitive");
            assertEquals(2, provider.list(workspace).size());
        } else {
            assertCode(
                    WorkspaceErrorCode.PATH_COLLISION,
                    () -> materialize(provider, "case-insensitive"));
            assertRootEmpty();
        }
    }

    @Test
    void snapshotsAndMetadataAreDefensivelyCopied() {
        byte[] bytes = bytes("safe");
        Map<String, String> metadata = new java.util.LinkedHashMap<>();
        metadata.put("type", "text");
        ProjectFileSnapshot file = new ProjectFileSnapshot(
                new ProjectPath("file.txt"),
                bytes,
                WorkspaceHashes.sha256(bytes),
                metadata);
        List<ProjectFileSnapshot> files = new ArrayList<>();
        files.add(file);
        ProjectVersionSnapshot snapshot = new ProjectVersionSnapshot(VERSION, files, metadata);

        bytes[0] = 'X';
        metadata.put("type", "changed");
        files.clear();

        assertBytes("safe", file.content());
        assertEquals("text", file.metadata().get("type"));
        assertEquals(1, snapshot.files().size());
        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.metadata().put("new", "value"));
    }

    @Test
    void projectPathContractStillRejectsAbsoluteAndTraversalForms() {
        for (String invalid : List.of(
                "/absolute.txt",
                "\\absolute.txt",
                "C:/absolute.txt",
                "../escape.txt",
                "nested/../escape.txt",
                "nested\\escape.txt")) {
            ContractViolationException exception = assertThrows(
                    ContractViolationException.class,
                    () -> new ProjectPath(invalid));
            assertEquals(ViolationCode.INVALID_PATH, exception.violations().get(0).code());
        }
    }

    @Test
    void providerRejectsHostSpecificAliasPaths() {
        LocalWorkspaceProvider provider = provider(root, snapshot(file("base.txt", "base")));
        WorkspaceRef workspace = materialize(provider, "portable-path");

        assertCode(
                WorkspaceErrorCode.PATH_COLLISION,
                () -> provider.create(workspace, new ProjectPath("stream:name"), bytes("bad")));
        assertCode(
                WorkspaceErrorCode.PATH_COLLISION,
                () -> provider.create(workspace, new ProjectPath("CON.txt"), bytes("bad")));
    }

    @Test
    void exceptionDoesNotExposeConfiguredHostPathOrIoCause() {
        LocalWorkspaceProvider provider = provider(root, snapshot(file("base.txt", "base")));
        WorkspaceRef workspace = materialize(provider, "no-host-path");
        WorkspaceException exception = assertThrows(
                WorkspaceException.class,
                () -> provider.read(workspace, new ProjectPath("missing.txt")));

        assertFalse(exception.getMessage().contains(root.toString()));
        assertEquals(null, exception.getCause());
    }

    @Test
    void configuredProviderRootMustBeExplicitlyAbsolute() {
        assertCode(
                WorkspaceErrorCode.PATH_ESCAPE,
                () -> new LocalWorkspaceProvider(
                        Path.of("relative-workspaces"),
                        ignored -> snapshot()));
    }

    private void assertMaterializationFailure(
            WorkspaceErrorCode expected,
            ProjectVersionSnapshot snapshot,
            String id) throws Exception {
        assertCode(expected, () -> materialize(provider(root, snapshot), id));
        assertRootEmpty();
    }

    private void assertLimitFailure(
            WorkspaceErrorCode expected,
            WorkspaceLimits limits,
            ProjectVersionSnapshot snapshot,
            String id) throws Exception {
        assertCode(expected, () -> materialize(provider(root, snapshot), id, limits));
        assertRootEmpty();
    }

    private void assertRootEmpty() throws Exception {
        assertDirectoryEmpty(root);
    }

    private static void assertDirectoryEmpty(Path directory) throws Exception {
        try (var children = Files.list(directory)) {
            assertTrue(children.findAny().isEmpty());
        }
    }

    private static boolean probeCaseSensitivity(Path root) throws Exception {
        Path lower = root.resolve("case-probe");
        Path upper = root.resolve("CASE-PROBE");
        Files.writeString(lower, "x");
        try {
            return !Files.exists(upper);
        } finally {
            Files.deleteIfExists(lower);
            Files.deleteIfExists(upper);
        }
    }

    private static void assertCode(WorkspaceErrorCode expected, Runnable operation) {
        WorkspaceException exception = assertThrows(WorkspaceException.class, operation::run);
        assertEquals(expected, exception.code());
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
