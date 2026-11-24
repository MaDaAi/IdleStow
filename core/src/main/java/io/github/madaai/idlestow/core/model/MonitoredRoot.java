package io.github.madaai.idlestow.core.model;

import java.nio.file.Path;

/**
 * A folder the user explicitly authorized IdleStow to index (and, subject to policy, optimize).
 * IdleStow never acts outside these roots.
 */
public record MonitoredRoot(
        long id,
        Path path,
        String displayName,
        String volumeId,
        boolean enabled,
        boolean autonomyEnabled,
        boolean archival,
        String authorizedUtc,
        String revokedUtc,
        String lastScanUtc,
        String lastScanStatus,
        long scanGeneration) {

    /** Authorized and not revoked. Revocation takes effect immediately for new operations. */
    public boolean active() {
        return enabled && revokedUtc == null;
    }
}
