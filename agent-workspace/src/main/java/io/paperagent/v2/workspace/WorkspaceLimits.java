package io.paperagent.v2.workspace;

/**
 * Explicit limits supplied by the caller for one workspace.
 */
public record WorkspaceLimits(long maxFileBytes, long maxAggregateBytes, int maxFiles) {
    public WorkspaceLimits {
        if (maxFileBytes < 0 || maxAggregateBytes < 0 || maxFiles < 0) {
            throw new WorkspaceException(WorkspaceErrorCode.INVALID_LIMIT, "configureLimits");
        }
    }
}
