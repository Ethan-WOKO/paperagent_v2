package io.paperagent.v2.workspace;

import io.paperagent.v2.contracts.ContentHash;
import io.paperagent.v2.contracts.DiffId;
import io.paperagent.v2.contracts.ProjectPath;
import io.paperagent.v2.contracts.ProjectVersionRef;
import io.paperagent.v2.contracts.WorkspaceDiff;
import io.paperagent.v2.contracts.WorkspaceId;
import io.paperagent.v2.contracts.WorkspaceRef;

import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

/**
 * JDK local-filesystem reference implementation.
 *
 * <p>Host paths are configuration details and are never returned by this API.
 * All public operations are synchronized so one provider instance cannot race
 * its own validation and mutation steps.</p>
 */
public final class LocalWorkspaceProvider implements WorkspacePort {
    private static final String DATA_DIRECTORY = "data";
    private static final String STAGING_DIRECTORY = "staging";

    private final Path providerRoot;
    private final ProjectVersionSource source;
    private final WorkspaceFileMover mover;
    private final WorkspaceMaterializationWriter materializationWriter;
    private final WorkspaceBackupReader backupReader;
    private final Map<WorkspaceId, WorkspaceState> workspaces = new HashMap<>();

    public LocalWorkspaceProvider(Path providerRoot, ProjectVersionSource source) {
        this(
                providerRoot,
                source,
                LocalWorkspaceProvider::defaultMove,
                Files::write,
                LocalWorkspaceProvider::readBoundedNoFollow);
    }

    LocalWorkspaceProvider(
            Path providerRoot,
            ProjectVersionSource source,
            WorkspaceFileMover mover) {
        this(
                providerRoot,
                source,
                mover,
                Files::write,
                LocalWorkspaceProvider::readBoundedNoFollow);
    }

    LocalWorkspaceProvider(
            Path providerRoot,
            ProjectVersionSource source,
            WorkspaceBackupReader backupReader) {
        this(
                providerRoot,
                source,
                LocalWorkspaceProvider::defaultMove,
                Files::write,
                backupReader);
    }

    LocalWorkspaceProvider(
            Path providerRoot,
            ProjectVersionSource source,
            WorkspaceFileMover mover,
            WorkspaceMaterializationWriter materializationWriter) {
        this(
                providerRoot,
                source,
                mover,
                materializationWriter,
                LocalWorkspaceProvider::readBoundedNoFollow);
    }

    private LocalWorkspaceProvider(
            Path providerRoot,
            ProjectVersionSource source,
            WorkspaceFileMover mover,
            WorkspaceMaterializationWriter materializationWriter,
            WorkspaceBackupReader backupReader) {
        WorkspaceValues.require(providerRoot, "configureWorkspace");
        this.source = WorkspaceValues.require(source, "configureWorkspace");
        this.mover = WorkspaceValues.require(mover, "configureWorkspace");
        this.materializationWriter =
                WorkspaceValues.require(materializationWriter, "configureWorkspace");
        this.backupReader = WorkspaceValues.require(backupReader, "configureWorkspace");
        if (!providerRoot.isAbsolute()) {
            throw failure(WorkspaceErrorCode.PATH_ESCAPE, "configureWorkspace", null);
        }
        try {
            Path absolute = providerRoot.normalize();
            Files.createDirectories(absolute);
            this.providerRoot = absolute.toRealPath();
            requireDirectoryWithoutLinks(this.providerRoot, "configureWorkspace", null);
        } catch (IOException exception) {
            throw failure(WorkspaceErrorCode.IO_FAILURE, "configureWorkspace", null);
        }
    }

    @Override
    public synchronized WorkspaceRef materialize(
            WorkspaceId workspaceId,
            ProjectVersionRef sourceVersion,
            WorkspaceLimits limits) {
        WorkspaceValues.require(workspaceId, "materialize");
        WorkspaceValues.require(sourceVersion, "materialize");
        WorkspaceValues.require(limits, "materialize");
        if (workspaces.containsKey(workspaceId)) {
            throw failure(WorkspaceErrorCode.WORKSPACE_ALREADY_EXISTS, "materialize", null);
        }

        ProjectVersionSnapshot snapshot = loadSnapshot(sourceVersion);
        WorkspaceManifestValidator.validateReference(snapshot, sourceVersion);
        WorkspaceManifestValidator.validateLimits(snapshot.files(), limits);
        WorkspaceManifestValidator.validatePaths(snapshot.files(), true);
        WorkspaceManifestValidator.validateHashes(snapshot.files());

        Path container = containerFor(workspaceId);
        if (Files.exists(container, NOFOLLOW_LINKS)) {
            throw failure(WorkspaceErrorCode.WORKSPACE_ALREADY_EXISTS, "materialize", null);
        }

        WorkspaceRef workspace = new WorkspaceRef(workspaceId, sourceVersion);
        WorkspaceState state = new WorkspaceState(
                workspace,
                container,
                container.resolve(DATA_DIRECTORY),
                container.resolve(STAGING_DIRECTORY),
                limits,
                WorkspaceManifestValidator.baseline(snapshot.files()));
        boolean complete = false;
        try {
            Files.createDirectory(container);
            Files.createDirectory(state.dataRoot());
            Files.createDirectory(state.stagingRoot());
            boolean caseSensitive = isCaseSensitive(state.stagingRoot());
            WorkspaceManifestValidator.validatePaths(snapshot.files(), caseSensitive);
            for (ProjectFileSnapshot file : WorkspaceManifestValidator.sorted(snapshot.files())) {
                byte[] content = file.content();
                Path target = secureResolve(state, file.path(), false, "materialize");
                createParents(state, file.path(), "materialize");
                materializationWriter.write(
                        target,
                        content,
                        StandardOpenOption.CREATE_NEW,
                        StandardOpenOption.WRITE);
                requireRegularFile(target, "materialize", file.path());
            }
            workspaces.put(workspaceId, state);
            complete = true;
            return workspace;
        } catch (WorkspaceException exception) {
            throw exception;
        } catch (IOException exception) {
            throw failure(WorkspaceErrorCode.IO_FAILURE, "materialize", null);
        } finally {
            if (!complete) {
                deleteTreeWithoutFollowing(container);
            }
        }
    }

    @Override
    public synchronized List<WorkspaceFileStat> list(WorkspaceRef workspace) {
        WorkspaceState state = state(workspace, "list");
        return List.copyOf(scan(state, "list").values());
    }

    @Override
    public synchronized WorkspaceFileStat stat(WorkspaceRef workspace, ProjectPath path) {
        WorkspaceState state = state(workspace, "stat");
        WorkspaceValues.require(path, "stat");
        Path target = secureResolve(state, path, true, "stat");
        requireRegularFile(target, "stat", path);
        try {
            long size = Files.size(target);
            requireReadableSize(state, path, size, "stat");
            return new WorkspaceFileStat(
                    path,
                    size,
                    WorkspaceHashes.sha256(
                            target,
                            state.limits().maxFileBytes(),
                            "stat",
                            path));
        } catch (IOException exception) {
            throw failure(WorkspaceErrorCode.IO_FAILURE, "stat", path);
        }
    }

    @Override
    public synchronized byte[] read(WorkspaceRef workspace, ProjectPath path) {
        WorkspaceState state = state(workspace, "read");
        WorkspaceValues.require(path, "read");
        Path target = secureResolve(state, path, true, "read");
        requireRegularFile(target, "read", path);
        try {
            long size = Files.size(target);
            requireReadableSize(state, path, size, "read");
            return readBoundedNoFollow(target, state.limits().maxFileBytes(), "read", path);
        } catch (WorkspaceException exception) {
            throw exception;
        } catch (IOException exception) {
            throw failure(WorkspaceErrorCode.IO_FAILURE, "read", path);
        }
    }

    @Override
    public synchronized void create(WorkspaceRef workspace, ProjectPath path, byte[] content) {
        write(workspace, path, content, false);
    }

    @Override
    public synchronized void replace(WorkspaceRef workspace, ProjectPath path, byte[] content) {
        write(workspace, path, content, true);
    }

    @Override
    public synchronized void delete(WorkspaceRef workspace, ProjectPath path) {
        WorkspaceState state = state(workspace, "delete");
        WorkspaceValues.require(path, "delete");
        Path target = secureResolve(state, path, true, "delete");
        requireRegularFile(target, "delete", path);
        try {
            Files.delete(target);
            removeEmptyParents(state, target.getParent());
        } catch (IOException exception) {
            throw failure(WorkspaceErrorCode.IO_FAILURE, "delete", path);
        }
    }

    @Override
    public synchronized void move(WorkspaceRef workspace, ProjectPath sourcePath, ProjectPath targetPath) {
        WorkspaceState state = state(workspace, "move");
        WorkspaceValues.require(sourcePath, "move");
        WorkspaceValues.require(targetPath, "move");
        if (sourcePath.equals(targetPath)) {
            return;
        }
        Path sourceFile = secureResolve(state, sourcePath, true, "move");
        requireRegularFile(sourceFile, "move", sourcePath);
        Path targetFile = secureResolve(state, targetPath, false, "move");
        if (Files.exists(targetFile, NOFOLLOW_LINKS)) {
            throw failure(WorkspaceErrorCode.PATH_ALREADY_EXISTS, "move", targetPath);
        }
        ensureAdditionalFileAllowed(state, 0, false, targetPath, "move");
        createParents(state, targetPath, "move");
        try {
            mover.move(sourceFile, targetFile, false);
            requireRegularFile(targetFile, "move", targetPath);
            removeEmptyParents(state, sourceFile.getParent());
        } catch (WorkspaceException exception) {
            throw exception;
        } catch (IOException exception) {
            throw failure(WorkspaceErrorCode.IO_FAILURE, "move", sourcePath);
        }
    }

    @Override
    public synchronized WorkspaceDiff diff(WorkspaceRef workspace, DiffId diffId, Instant createdAt) {
        WorkspaceState state = state(workspace, "diff");
        WorkspaceValues.require(diffId, "diff");
        WorkspaceValues.require(createdAt, "diff");

        Map<ProjectPath, WorkspaceFileStat> currentStats = scan(state, "diff");
        Map<ProjectPath, ContentHash> current =
                new TreeMap<>(WorkspaceDiffCalculator.pathComparator());
        currentStats.forEach((path, stat) -> current.put(path, stat.hash()));
        return new WorkspaceDiff(
                diffId,
                workspace,
                WorkspaceDiffCalculator.calculate(state.baseline(), current),
                createdAt);
    }

    @Override
    public synchronized void cleanup(WorkspaceRef workspace) {
        WorkspaceValues.require(workspace, "cleanup");
        Path expected = containerFor(workspace.id());
        WorkspaceState state = workspaces.get(workspace.id());
        if (state == null) {
            if (!Files.exists(expected, NOFOLLOW_LINKS)) {
                return;
            }
            throw failure(linkLike(expected)
                    ? WorkspaceErrorCode.LINK_ESCAPE
                    : WorkspaceErrorCode.WORKSPACE_NOT_FOUND, "cleanup", null);
        }
        requireMatchingReference(state, workspace, "cleanup");
        requireManagedState(state, "cleanup");
        rejectLinksInTree(state.container(), "cleanup");
        try {
            deleteTree(state.container());
            workspaces.remove(workspace.id());
        } catch (IOException exception) {
            throw failure(WorkspaceErrorCode.IO_FAILURE, "cleanup", null);
        }
    }

    private void write(WorkspaceRef workspace, ProjectPath path, byte[] supplied, boolean replace) {
        String operation = replace ? "replace" : "create";
        WorkspaceState state = state(workspace, operation);
        WorkspaceValues.require(path, operation);
        WorkspaceValues.require(supplied, operation);
        if (supplied.length > state.limits().maxFileBytes()) {
            throw failure(WorkspaceErrorCode.FILE_LIMIT_EXCEEDED, operation, path);
        }
        byte[] content = supplied.clone();
        Path target = secureResolve(state, path, false, operation);
        boolean exists = Files.exists(target, NOFOLLOW_LINKS);
        if (replace && !exists) {
            throw failure(WorkspaceErrorCode.PATH_NOT_FOUND, operation, path);
        }
        if (!replace && exists) {
            throw failure(WorkspaceErrorCode.PATH_ALREADY_EXISTS, operation, path);
        }
        if (exists) {
            requireRegularFile(target, operation, path);
        }
        long previousSize = exists ? fileSizeNoFollow(target, operation, path) : 0;
        ensureAdditionalFileAllowed(state, content.length - previousSize, !exists, path, operation);
        createParents(state, path, operation);

        Path temporary = stagingPath(state, path, ".tmp", operation);
        Path backup = stagingPath(state, path, ".bak", operation);
        try {
            ensureStagingAvailable(temporary, operation, path);
            ensureStagingAvailable(backup, operation, path);
            Files.write(temporary, content, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            requireRegularStagingFile(state, temporary, operation, path);
            if (exists) {
                acquirePriorFile(state, path, target, backup, operation);
            }
            try {
                requireTargetParentWithoutLinks(state, path, target, operation);
                requireRegularStagingFile(state, temporary, operation, path);
                mover.move(temporary, target, exists);
            } catch (IOException moveFailure) {
                if (exists) {
                    restorePriorFile(state, path, target, backup, operation);
                }
                throw moveFailure;
            }
            requireRegularFile(target, operation, path);
        } catch (WorkspaceException exception) {
            throw exception;
        } catch (IOException exception) {
            throw failure(WorkspaceErrorCode.IO_FAILURE, operation, path);
        } finally {
            deleteRegularIfPresent(temporary);
            deleteRegularIfPresent(backup);
        }
    }

    private ProjectVersionSnapshot loadSnapshot(ProjectVersionRef sourceVersion) {
        try {
            ProjectVersionSnapshot snapshot = source.load(sourceVersion);
            if (snapshot == null) {
                throw failure(WorkspaceErrorCode.SOURCE_FAILURE, "materialize", null);
            }
            return snapshot;
        } catch (WorkspaceException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw failure(WorkspaceErrorCode.SOURCE_FAILURE, "materialize", null);
        }
    }

    private WorkspaceState state(WorkspaceRef workspace, String operation) {
        WorkspaceValues.require(workspace, operation);
        WorkspaceState state = workspaces.get(workspace.id());
        if (state == null) {
            throw failure(WorkspaceErrorCode.WORKSPACE_NOT_FOUND, operation, null);
        }
        requireMatchingReference(state, workspace, operation);
        requireManagedState(state, operation);
        return state;
    }

    private static void requireMatchingReference(
            WorkspaceState state,
            WorkspaceRef supplied,
            String operation) {
        if (!state.workspace().equals(supplied)) {
            throw failure(WorkspaceErrorCode.WORKSPACE_REFERENCE_MISMATCH, operation, null);
        }
    }

    private void requireManagedState(WorkspaceState state, String operation) {
        if (!state.container().getParent().equals(providerRoot)
                || !state.dataRoot().getParent().equals(state.container())
                || !state.stagingRoot().getParent().equals(state.container())
                || !state.container().equals(containerFor(state.workspace().id()))) {
            throw failure(WorkspaceErrorCode.PATH_ESCAPE, operation, null);
        }
        requireDirectoryWithoutLinks(providerRoot, operation, null);
        requireDirectoryWithoutLinks(state.container(), operation, null);
        requireDirectoryWithoutLinks(state.dataRoot(), operation, null);
        requireDirectoryWithoutLinks(state.stagingRoot(), operation, null);
        try {
            if (!providerRoot.equals(providerRoot.toRealPath())
                    || !state.container().toRealPath().startsWith(providerRoot)
                    || !state.dataRoot().toRealPath().startsWith(state.container().toRealPath())
                    || !state.stagingRoot().toRealPath().startsWith(state.container().toRealPath())) {
                throw failure(WorkspaceErrorCode.PATH_ESCAPE, operation, null);
            }
        } catch (IOException exception) {
            throw failure(WorkspaceErrorCode.IO_FAILURE, operation, null);
        }
    }

    private Path secureResolve(
            WorkspaceState state,
            ProjectPath projectPath,
            boolean mustExist,
            String operation) {
        WorkspaceManifestValidator.validatePortablePath(projectPath, operation);
        Path resolved = state.dataRoot();
        String[] segments = projectPath.value().split("/");
        for (int index = 0; index < segments.length; index++) {
            resolved = resolved.resolve(segments[index]);
            if (!resolved.normalize().startsWith(state.dataRoot())) {
                throw failure(WorkspaceErrorCode.PATH_ESCAPE, operation, projectPath);
            }
            if (Files.exists(resolved, NOFOLLOW_LINKS)) {
                requireNotLink(resolved, operation, projectPath);
                if (index < segments.length - 1 && !Files.isDirectory(resolved, NOFOLLOW_LINKS)) {
                    throw failure(WorkspaceErrorCode.NOT_REGULAR_FILE, operation, projectPath);
                }
            }
        }
        if (mustExist && !Files.exists(resolved, NOFOLLOW_LINKS)) {
            throw failure(WorkspaceErrorCode.PATH_NOT_FOUND, operation, projectPath);
        }
        return resolved;
    }

    private static void createParents(
            WorkspaceState state,
            ProjectPath path,
            String operation) {
        Path current = state.dataRoot();
        String[] segments = path.value().split("/");
        for (int index = 0; index < segments.length - 1; index++) {
            current = current.resolve(segments[index]);
            if (Files.exists(current, NOFOLLOW_LINKS)) {
                requireNotLink(current, operation, path);
                if (!Files.isDirectory(current, NOFOLLOW_LINKS)) {
                    throw failure(WorkspaceErrorCode.PATH_COLLISION, operation, path);
                }
            } else {
                try {
                    Files.createDirectory(current);
                } catch (FileAlreadyExistsException exception) {
                    requireNotLink(current, operation, path);
                    if (!Files.isDirectory(current, NOFOLLOW_LINKS)) {
                        throw failure(WorkspaceErrorCode.PATH_COLLISION, operation, path);
                    }
                } catch (IOException exception) {
                    throw failure(WorkspaceErrorCode.IO_FAILURE, operation, path);
                }
            }
        }
    }

    private Map<ProjectPath, WorkspaceFileStat> scan(WorkspaceState state, String operation) {
        TreeMap<ProjectPath, WorkspaceFileStat> result =
                new TreeMap<>(WorkspaceDiffCalculator.pathComparator());
        long[] aggregate = {0};
        int[] count = {0};
        try {
            Files.walkFileTree(state.dataRoot(), new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                    if (!directory.equals(state.dataRoot()) && linkLike(directory)) {
                        throw failure(WorkspaceErrorCode.LINK_ESCAPE, operation, relative(state, directory));
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                    ProjectPath path = relative(state, file);
                    if (attributes.isSymbolicLink() || attributes.isOther() || !attributes.isRegularFile()) {
                        throw failure(WorkspaceErrorCode.LINK_ESCAPE, operation, path);
                    }
                    long size = attributes.size();
                    requireReadableSize(state, path, size, operation);
                    count[0]++;
                    if (count[0] > state.limits().maxFiles()) {
                        throw failure(WorkspaceErrorCode.FILE_COUNT_LIMIT_EXCEEDED, operation, path);
                    }
                    try {
                        aggregate[0] = Math.addExact(aggregate[0], size);
                    } catch (ArithmeticException exception) {
                        throw failure(WorkspaceErrorCode.AGGREGATE_LIMIT_EXCEEDED, operation, path);
                    }
                    if (aggregate[0] > state.limits().maxAggregateBytes()) {
                        throw failure(WorkspaceErrorCode.AGGREGATE_LIMIT_EXCEEDED, operation, path);
                    }
                    result.put(path, new WorkspaceFileStat(
                            path,
                            size,
                            WorkspaceHashes.sha256(
                                    file,
                                    state.limits().maxFileBytes(),
                                    operation,
                                    path)));
                    return FileVisitResult.CONTINUE;
                }
            });
            return result;
        } catch (WorkspaceException exception) {
            throw exception;
        } catch (IOException exception) {
            throw failure(WorkspaceErrorCode.IO_FAILURE, operation, null);
        }
    }

    private void ensureAdditionalFileAllowed(
            WorkspaceState state,
            long aggregateDelta,
            boolean newFile,
            ProjectPath path,
            String operation) {
        Collection<WorkspaceFileStat> current = scan(state, operation).values();
        if (newFile && current.size() >= state.limits().maxFiles()) {
            throw failure(WorkspaceErrorCode.FILE_COUNT_LIMIT_EXCEEDED, operation, path);
        }
        long aggregate = current.stream().mapToLong(WorkspaceFileStat::size).sum();
        if (aggregateDelta > 0 && aggregate > state.limits().maxAggregateBytes() - aggregateDelta) {
            throw failure(WorkspaceErrorCode.AGGREGATE_LIMIT_EXCEEDED, operation, path);
        }
    }

    private Path stagingPath(
            WorkspaceState state,
            ProjectPath path,
            String suffix,
            String operation) {
        requireDirectoryWithoutLinks(state.stagingRoot(), operation, path);
        return state.stagingRoot().resolve(WorkspaceHashes.sha256Text(path.value()) + suffix);
    }

    private static void ensureStagingAvailable(Path path, String operation, ProjectPath projectPath) {
        if (Files.exists(path, NOFOLLOW_LINKS)) {
            throw failure(WorkspaceErrorCode.TEMPORARY_PATH_OCCUPIED, operation, projectPath);
        }
    }

    private void acquirePriorFile(
            WorkspaceState state,
            ProjectPath path,
            Path target,
            Path backup,
            String operation) throws IOException {
        requireTargetParentWithoutLinks(state, path, target, operation);
        requireRegularFile(target, operation, path);
        byte[] priorContent =
                backupReader.read(target, state.limits().maxFileBytes(), operation, path);
        if (priorContent.length > state.limits().maxFileBytes()) {
            throw failure(WorkspaceErrorCode.FILE_LIMIT_EXCEEDED, operation, path);
        }
        requireDirectoryWithoutLinks(state.stagingRoot(), operation, path);
        ensureStagingAvailable(backup, operation, path);
        Files.write(
                backup,
                priorContent,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE);
        requireRegularStagingFile(state, backup, operation, path);
    }

    private void restorePriorFile(
            WorkspaceState state,
            ProjectPath path,
            Path target,
            Path backup,
            String operation) {
        if (!Files.exists(backup, NOFOLLOW_LINKS)) {
            return;
        }
        try {
            requireTargetParentWithoutLinks(state, path, target, operation);
            requireRegularStagingFile(state, backup, operation, path);
            /*
             * Moving with REPLACE_EXISTING replaces a final symbolic-link entry
             * instead of opening its referent. Parent components are rechecked
             * immediately above; a hostile cross-process swap after that check
             * remains a filesystem boundary this JDK-only provider cannot close.
             */
            defaultMove(backup, target, true);
            requireRegularFile(target, operation, path);
        } catch (WorkspaceException | IOException ignored) {
            // The public failure remains stable and does not expose a host path.
        }
    }

    private void requireTargetParentWithoutLinks(
            WorkspaceState state,
            ProjectPath path,
            Path target,
            String operation) {
        requireManagedState(state, operation);
        Path parent = state.dataRoot();
        String[] segments = path.value().split("/");
        for (int index = 0; index < segments.length - 1; index++) {
            parent = parent.resolve(segments[index]);
            requireNotLink(parent, operation, path);
            if (!Files.isDirectory(parent, NOFOLLOW_LINKS)) {
                throw failure(WorkspaceErrorCode.PATH_COLLISION, operation, path);
            }
        }
        if (!target.getParent().equals(parent)) {
            throw failure(WorkspaceErrorCode.PATH_ESCAPE, operation, path);
        }
    }

    private static void requireRegularStagingFile(
            WorkspaceState state,
            Path path,
            String operation,
            ProjectPath projectPath) {
        requireDirectoryWithoutLinks(state.stagingRoot(), operation, projectPath);
        if (!path.getParent().equals(state.stagingRoot())) {
            throw failure(WorkspaceErrorCode.PATH_ESCAPE, operation, projectPath);
        }
        requireRegularFile(path, operation, projectPath);
    }

    private static void removeEmptyParents(WorkspaceState state, Path start) {
        Path current = start;
        while (current != null && !current.equals(state.dataRoot())) {
            requireNotLink(current, "cleanupDirectories", null);
            try {
                Files.delete(current);
                current = current.getParent();
            } catch (DirectoryNotEmptyException exception) {
                return;
            } catch (IOException exception) {
                throw failure(WorkspaceErrorCode.IO_FAILURE, "cleanupDirectories", null);
            }
        }
    }

    private static void requireReadableSize(
            WorkspaceState state,
            ProjectPath path,
            long size,
            String operation) {
        if (size > state.limits().maxFileBytes()) {
            throw failure(WorkspaceErrorCode.FILE_LIMIT_EXCEEDED, operation, path);
        }
        if (size > Integer.MAX_VALUE) {
            throw failure(WorkspaceErrorCode.FILE_LIMIT_EXCEEDED, operation, path);
        }
    }

    private static long fileSizeNoFollow(
            Path path,
            String operation,
            ProjectPath projectPath) {
        try {
            BasicFileAttributes attributes =
                    Files.readAttributes(path, BasicFileAttributes.class, NOFOLLOW_LINKS);
            if (attributes.isSymbolicLink() || attributes.isOther()) {
                throw failure(WorkspaceErrorCode.LINK_ESCAPE, operation, projectPath);
            }
            if (!attributes.isRegularFile()) {
                throw failure(WorkspaceErrorCode.NOT_REGULAR_FILE, operation, projectPath);
            }
            return attributes.size();
        } catch (WorkspaceException exception) {
            throw exception;
        } catch (IOException exception) {
            throw failure(WorkspaceErrorCode.IO_FAILURE, operation, projectPath);
        }
    }

    private static void requireRegularFile(Path path, String operation, ProjectPath projectPath) {
        if (!Files.exists(path, NOFOLLOW_LINKS)) {
            throw failure(WorkspaceErrorCode.PATH_NOT_FOUND, operation, projectPath);
        }
        requireNotLink(path, operation, projectPath);
        if (!Files.isRegularFile(path, NOFOLLOW_LINKS)) {
            throw failure(WorkspaceErrorCode.NOT_REGULAR_FILE, operation, projectPath);
        }
    }

    private static void requireDirectoryWithoutLinks(
            Path path,
            String operation,
            ProjectPath projectPath) {
        if (linkLike(path)) {
            throw failure(WorkspaceErrorCode.LINK_ESCAPE, operation, projectPath);
        }
        if (!Files.isDirectory(path, NOFOLLOW_LINKS)) {
            throw failure(WorkspaceErrorCode.IO_FAILURE, operation, projectPath);
        }
    }

    private static void requireNotLink(Path path, String operation, ProjectPath projectPath) {
        if (linkLike(path)) {
            throw failure(WorkspaceErrorCode.LINK_ESCAPE, operation, projectPath);
        }
    }

    private static boolean linkLike(Path path) {
        if (Files.isSymbolicLink(path)) {
            return true;
        }
        try {
            BasicFileAttributes attributes =
                    Files.readAttributes(path, BasicFileAttributes.class, NOFOLLOW_LINKS);
            return attributes.isOther();
        } catch (IOException exception) {
            return false;
        }
    }

    private void rejectLinksInTree(Path container, String operation) {
        try {
            Files.walkFileTree(container, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                    if (linkLike(directory)) {
                        throw failure(WorkspaceErrorCode.LINK_ESCAPE, operation, null);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                    if (attributes.isSymbolicLink() || attributes.isOther()) {
                        throw failure(WorkspaceErrorCode.LINK_ESCAPE, operation, null);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (WorkspaceException exception) {
            throw exception;
        } catch (IOException exception) {
            throw failure(WorkspaceErrorCode.IO_FAILURE, operation, null);
        }
    }

    private static void deleteTree(Path root) throws IOException {
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes)
                    throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException exception)
                    throws IOException {
                if (exception != null) {
                    throw exception;
                }
                Files.delete(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void deleteTreeWithoutFollowing(Path root) {
        if (!Files.exists(root, NOFOLLOW_LINKS)) {
            return;
        }
        try {
            if (linkLike(root)) {
                Files.delete(root);
            } else {
                deleteTree(root);
            }
        } catch (IOException ignored) {
            // Best-effort cleanup after a failed materialization; the original failure wins.
        }
    }

    private static void deleteRegularIfPresent(Path path) {
        try {
            if (Files.isRegularFile(path, NOFOLLOW_LINKS)) {
                Files.deleteIfExists(path);
            }
        } catch (IOException ignored) {
            // A later operation will reject an occupied staging path deterministically.
        }
    }

    private Path containerFor(WorkspaceId id) {
        String directoryName = "ws-" + WorkspaceHashes.sha256Text(id.value());
        Path container = providerRoot.resolve(directoryName).normalize();
        if (!container.getParent().equals(providerRoot)) {
            throw failure(WorkspaceErrorCode.PATH_ESCAPE, "resolveWorkspace", null);
        }
        return container;
    }

    private static ProjectPath relative(WorkspaceState state, Path path) {
        Path relative = state.dataRoot().relativize(path);
        String value = relative.toString().replace(path.getFileSystem().getSeparator(), "/");
        return new ProjectPath(value);
    }

    private static boolean isCaseSensitive(Path stagingRoot) throws IOException {
        Path lower = stagingRoot.resolve(".paperagent-case-probe");
        Path upper = stagingRoot.resolve(".PAPERAGENT-CASE-PROBE");
        Files.write(lower, new byte[]{0}, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        try {
            return !Files.exists(upper, NOFOLLOW_LINKS);
        } finally {
            Files.deleteIfExists(lower);
            if (!lower.equals(upper)) {
                Files.deleteIfExists(upper);
            }
        }
    }

    static byte[] readBoundedNoFollow(
            Path path,
            long maximum,
            String operation,
            ProjectPath projectPath) {
        if (maximum > Integer.MAX_VALUE) {
            maximum = Integer.MAX_VALUE;
        }
        try (InputStream input = Files.newInputStream(
                path,
                StandardOpenOption.READ,
                NOFOLLOW_LINKS);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            long total = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (total > maximum - read) {
                    throw failure(WorkspaceErrorCode.FILE_LIMIT_EXCEEDED, operation, projectPath);
                }
                output.write(buffer, 0, read);
                total += read;
            }
            return output.toByteArray();
        } catch (WorkspaceException exception) {
            throw exception;
        } catch (UnsupportedOperationException exception) {
            throw failure(WorkspaceErrorCode.LINK_ESCAPE, operation, projectPath);
        } catch (IOException exception) {
            throw failure(
                    linkLike(path)
                            ? WorkspaceErrorCode.LINK_ESCAPE
                            : WorkspaceErrorCode.IO_FAILURE,
                    operation,
                    projectPath);
        }
    }

    private static void defaultMove(Path source, Path target, boolean replace) throws IOException {
        List<StandardCopyOption> options = new ArrayList<>();
        options.add(StandardCopyOption.ATOMIC_MOVE);
        if (replace) {
            options.add(StandardCopyOption.REPLACE_EXISTING);
        }
        try {
            Files.move(source, target, options.toArray(StandardCopyOption[]::new));
        } catch (AtomicMoveNotSupportedException exception) {
            if (replace) {
                Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
            } else {
                Files.move(source, target);
            }
        }
    }

    private static WorkspaceException failure(
            WorkspaceErrorCode code,
            String operation,
            ProjectPath projectPath) {
        return new WorkspaceException(code, operation, projectPath);
    }

    private record WorkspaceState(
            WorkspaceRef workspace,
            Path container,
            Path dataRoot,
            Path stagingRoot,
            WorkspaceLimits limits,
            Map<ProjectPath, ContentHash> baseline) {
        private WorkspaceState {
            baseline = Map.copyOf(baseline);
        }
    }
}
