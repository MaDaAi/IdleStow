package io.github.madaai.idlestow.core.jobs;

import io.github.madaai.idlestow.core.db.RootRepository;
import io.github.madaai.idlestow.core.model.MonitoredRoot;
import io.github.madaai.idlestow.core.policy.PolicyEngine;
import io.github.madaai.idlestow.core.policy.PolicyEngine.Protection;
import io.github.madaai.idlestow.core.util.PathUtil;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.sql.SQLException;

/**
 * Final authorization gate, evaluated immediately before every filesystem mutation.
 *
 * <p>Always re-reads the monitored root from the database so that revocation takes effect for
 * the next file even inside a running job. Rejects any path whose components include a symbolic
 * link, junction or other reparse point (no link escapes), and any mandatorily protected,
 * pinned or excluded path. Fails closed on any error.
 */
public final class Authorizer {

    private final RootRepository roots;
    private final PolicyEngine policy;

    public Authorizer(RootRepository roots, PolicyEngine policy) {
        this.roots = roots;
        this.policy = policy;
    }

    /** Thrown when a mutation is not authorized. Never retried automatically. */
    public static final class NotAuthorizedException extends Exception {
        public NotAuthorizedException(String message) {
            super(message);
        }
    }

    /**
     * Verifies that {@code target} may be mutated under root {@code rootId}.
     *
     * @param category  file category (for exclusions)
     * @return the fresh root
     */
    public MonitoredRoot authorize(long rootId, Path target, long size, String category, String volumeId)
            throws NotAuthorizedException {
        MonitoredRoot root;
        try {
            root = roots.byId(rootId).orElse(null);
        } catch (SQLException e) {
            throw new NotAuthorizedException("Cannot verify authorization (database error): " + e.getMessage());
        }
        if (root == null) throw new NotAuthorizedException("The monitored folder is no longer authorized");
        if (!root.active()) throw new NotAuthorizedException("Authorization for " + root.path() + " was revoked");
        Path rootPath = PathUtil.normalize(root.path());
        if (!Files.isDirectory(rootPath)) {
            throw new NotAuthorizedException("The monitored folder " + rootPath + " is not available");
        }
        Path p = PathUtil.normalize(target);
        if (!PathUtil.isWithin(p, rootPath) || p.equals(rootPath)) {
            throw new NotAuthorizedException("Path is outside the authorized folder");
        }
        Protection prot = policy.protection(p, false, size, category, volumeId, root);
        if (prot.blocked()) throw new NotAuthorizedException(prot.reason());
        verifyNoLinks(rootPath, p);
        return root;
    }

    /**
     * Ensures that the resolved location of {@code p}'s parent equals the lexical location under the
     * resolved root (so no intermediate component is a link/junction) and that {@code p} itself is
     * not a link or reparse point.
     */
    static void verifyNoLinks(Path rootPath, Path p) throws NotAuthorizedException {
        try {
            Path realRoot = rootPath.toRealPath();
            Path rel = rootPath.relativize(p);
            Path expectedParent = rel.getParent() == null ? realRoot : realRoot.resolve(rel.getParent());
            Path realParent = p.getParent().toRealPath();
            boolean same = PathUtil.isWindows()
                    ? realParent.toString().equalsIgnoreCase(expectedParent.normalize().toString())
                    : realParent.equals(expectedParent.normalize());
            if (!same) {
                throw new NotAuthorizedException("A folder in the path is a link or junction; refusing to follow it");
            }
            if (Files.exists(p, LinkOption.NOFOLLOW_LINKS)) {
                BasicFileAttributes a = Files.readAttributes(p, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (a.isSymbolicLink() || a.isOther()) {
                    throw new NotAuthorizedException("The file is a link or reparse point; refusing to modify it");
                }
            }
        } catch (IOException e) {
            throw new NotAuthorizedException("Cannot resolve path safely: " + e.getMessage());
        }
    }
}
