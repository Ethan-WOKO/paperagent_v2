package io.paperagent.v2.workspace;

import io.paperagent.v2.contracts.DiffId;
import io.paperagent.v2.contracts.ProjectPath;
import io.paperagent.v2.contracts.WorkspaceRef;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

import static io.paperagent.v2.workspace.WorkspaceTestSupport.VERSION;
import static io.paperagent.v2.workspace.WorkspaceTestSupport.assertBytes;
import static io.paperagent.v2.workspace.WorkspaceTestSupport.dataRoot;
import static io.paperagent.v2.workspace.WorkspaceTestSupport.file;
import static io.paperagent.v2.workspace.WorkspaceTestSupport.materialize;
import static io.paperagent.v2.workspace.WorkspaceTestSupport.onlyContainer;
import static io.paperagent.v2.workspace.WorkspaceTestSupport.provider;
import static io.paperagent.v2.workspace.WorkspaceTestSupport.snapshot;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkspaceLinkSecurityTest {
    @TempDir
    Path root;

    @Test
    void realSymbolicLinkEscapeIsRejectedForEveryFilesystemBoundary() throws Exception {
        Path providerRoot = root.resolve("provider");
        Path outside = root.resolve("outside");
        Files.createDirectories(providerRoot);
        Files.createDirectories(outside);
        Path outsideFile = outside.resolve("secret.txt");
        Files.writeString(outsideFile, "outside");
        LocalWorkspaceProvider provider = provider(
                providerRoot,
                snapshot(file("inside.txt", "inside")));
        WorkspaceRef workspace = materialize(provider, "link-boundary");
        Path link = dataRoot(providerRoot).resolve("link");
        createRequiredSymbolicLink(link, outside);

        assertLinkEscape(() -> provider.read(workspace, new ProjectPath("link/secret.txt")));
        assertLinkEscape(() -> provider.create(
                workspace,
                new ProjectPath("link/new.txt"),
                "new".getBytes(StandardCharsets.UTF_8)));
        assertLinkEscape(() -> provider.delete(workspace, new ProjectPath("link/secret.txt")));
        assertLinkEscape(() -> provider.move(
                workspace,
                new ProjectPath("inside.txt"),
                new ProjectPath("link/moved.txt")));
        assertLinkEscape(() -> provider.move(
                workspace,
                new ProjectPath("link/secret.txt"),
                new ProjectPath("stolen.txt")));
        assertLinkEscape(() -> provider.diff(
                workspace,
                new DiffId("link-diff"),
                Instant.EPOCH));
        assertLinkEscape(() -> provider.cleanup(workspace));
        assertEquals("outside", Files.readString(outsideFile));
        assertTrue(Files.exists(outsideFile));

        Files.delete(link);
        provider.cleanup(workspace);
    }

    @Test
    void cleanupRejectsWorkspaceContainerRedirectedOutsideProviderRoot() throws Exception {
        Path providerRoot = root.resolve("provider");
        Path outside = root.resolve("outside");
        Path parked = root.resolve("parked");
        Files.createDirectories(providerRoot);
        Files.createDirectories(outside);
        Files.writeString(outside.resolve("keep.txt"), "keep");
        LocalWorkspaceProvider provider = provider(
                providerRoot,
                snapshot(file("inside.txt", "inside")));
        WorkspaceRef workspace = materialize(provider, "cleanup-escape");
        Path container = onlyContainer(providerRoot);
        Files.move(container, parked);
        createRequiredSymbolicLink(container, outside);

        assertLinkEscape(() -> provider.cleanup(workspace));
        assertEquals("keep", Files.readString(outside.resolve("keep.txt")));

        Files.delete(container);
        Files.move(parked, container);
        provider.cleanup(workspace);
    }

    @Test
    void backupReadRejectsTargetSwappedToRealSymbolicLink() throws Exception {
        Path providerRoot = root.resolve("provider");
        Path outsideFile = root.resolve("outside.txt");
        Files.createDirectories(providerRoot);
        Files.writeString(outsideFile, "outside");
        requireSymbolicLinkSupport(root.resolve("probe-link"), outsideFile);
        ProjectVersionSnapshot snapshot = snapshot(file("inside.txt", "inside"));
        AtomicBoolean swapped = new AtomicBoolean();
        LocalWorkspaceProvider provider = new LocalWorkspaceProvider(
                providerRoot,
                ignored -> snapshot,
                (source, maximum, operation, projectPath) -> {
                    Files.delete(source);
                    Files.createSymbolicLink(source, outsideFile);
                    swapped.set(true);
                    return LocalWorkspaceProvider.readBoundedNoFollow(
                            source,
                            maximum,
                            operation,
                            projectPath);
                });
        WorkspaceRef workspace = materialize(provider, "backup-link-swap");
        Path target = dataRoot(providerRoot).resolve("inside.txt");

        assertLinkEscape(() -> provider.replace(
                workspace,
                new ProjectPath("inside.txt"),
                "replacement".getBytes(StandardCharsets.UTF_8)));

        assertTrue(swapped.get());
        assertTrue(Files.isSymbolicLink(target));
        assertEquals("outside", Files.readString(outsideFile));
        assertDirectoryEmpty(onlyContainer(providerRoot).resolve("staging"));

        Files.delete(target);
        Files.writeString(target, "inside");
        provider.cleanup(workspace);
    }

    @Test
    void failedReplaceRestoresBackupByReplacingSwappedLinkEntry() throws Exception {
        Path providerRoot = root.resolve("provider");
        Path outsideFile = root.resolve("outside.txt");
        Files.createDirectories(providerRoot);
        Files.writeString(outsideFile, "outside");
        requireSymbolicLinkSupport(root.resolve("probe-link"), outsideFile);
        ProjectVersionSnapshot snapshot = snapshot(file("inside.txt", "before"));
        AtomicBoolean moveAttempted = new AtomicBoolean();
        LocalWorkspaceProvider provider = new LocalWorkspaceProvider(
                providerRoot,
                ignored -> snapshot,
                (source, target, replace) -> {
                    moveAttempted.set(true);
                    Files.delete(target);
                    Files.createSymbolicLink(target, outsideFile);
                    throw new IOException("forced replace failure after target swap");
                });
        WorkspaceRef workspace = materialize(provider, "restore-link-swap");

        WorkspaceException failure = assertThrows(
                WorkspaceException.class,
                () -> provider.replace(
                        workspace,
                        new ProjectPath("inside.txt"),
                        "after".getBytes(StandardCharsets.UTF_8)));

        assertEquals(WorkspaceErrorCode.IO_FAILURE, failure.code());
        assertTrue(moveAttempted.get());
        Path target = dataRoot(providerRoot).resolve("inside.txt");
        assertFalse(Files.isSymbolicLink(target));
        assertBytes("before", provider.read(workspace, new ProjectPath("inside.txt")));
        assertEquals("outside", Files.readString(outsideFile));
        assertDirectoryEmpty(onlyContainer(providerRoot).resolve("staging"));
        provider.cleanup(workspace);
    }

    @Test
    void replaceRejectsOccupiedBackupLinkWithoutFollowingIt() throws Exception {
        Path providerRoot = root.resolve("provider");
        Path outsideFile = root.resolve("outside.txt");
        Files.createDirectories(providerRoot);
        Files.writeString(outsideFile, "outside");
        requireSymbolicLinkSupport(root.resolve("probe-link"), outsideFile);
        LocalWorkspaceProvider provider = provider(
                providerRoot,
                snapshot(file("inside.txt", "before")));
        WorkspaceRef workspace = materialize(provider, "occupied-backup-link");
        Path staging = onlyContainer(providerRoot).resolve("staging");
        Path backup = staging.resolve(
                WorkspaceHashes.sha256Text("inside.txt") + ".bak");
        Files.createSymbolicLink(backup, outsideFile);

        WorkspaceException failure = assertThrows(
                WorkspaceException.class,
                () -> provider.replace(
                        workspace,
                        new ProjectPath("inside.txt"),
                        "after".getBytes(StandardCharsets.UTF_8)));

        assertEquals(WorkspaceErrorCode.TEMPORARY_PATH_OCCUPIED, failure.code());
        assertTrue(Files.isSymbolicLink(backup));
        assertEquals("outside", Files.readString(outsideFile));
        assertBytes("before", provider.read(workspace, new ProjectPath("inside.txt")));

        Files.delete(backup);
        provider.cleanup(workspace);
    }

    private static void createRequiredSymbolicLink(Path link, Path target) throws IOException {
        try {
            Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException | FileSystemException exception) {
            if (isWindows()) {
                Assumptions.assumeTrue(
                        false,
                        "Windows test host cannot create symbolic links; Linux CI must execute this test");
            }
            throw exception;
        }
    }

    private static void requireSymbolicLinkSupport(Path probe, Path target) throws IOException {
        createRequiredSymbolicLink(probe, target);
        Files.delete(probe);
    }

    private static void assertDirectoryEmpty(Path directory) throws IOException {
        try (var entries = Files.list(directory)) {
            assertTrue(entries.findAny().isEmpty());
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static void assertLinkEscape(Runnable operation) {
        WorkspaceException exception = assertThrows(WorkspaceException.class, operation::run);
        assertEquals(WorkspaceErrorCode.LINK_ESCAPE, exception.code());
    }
}
