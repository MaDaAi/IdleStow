package io.github.madaai.idlestow.core.jobs;

import io.github.madaai.idlestow.core.db.Database;
import io.github.madaai.idlestow.core.db.RootRepository;
import io.github.madaai.idlestow.core.db.RulesRepository;
import io.github.madaai.idlestow.core.jobs.Authorizer.NotAuthorizedException;
import io.github.madaai.idlestow.core.model.MonitoredRoot;
import io.github.madaai.idlestow.core.platform.ProtectedLocations;
import io.github.madaai.idlestow.core.policy.PolicyEngine;
import io.github.madaai.idlestow.core.policy.ProtectedRules;
import io.github.madaai.idlestow.core.testsupport.LinkSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AuthorizerTest {
    @TempDir Path tmp;
    Database db;
    RootRepository roots;
    RulesRepository rules;
    PolicyEngine policy;
    Authorizer authorizer;
    Path root;
    Path outside;
    long rootId;

    @BeforeEach
    void setUp() throws Exception {
        db = Database.open(tmp.resolve("db/idlestow.db"));
        roots = new RootRepository(db, Clock.systemUTC());
        rules = new RulesRepository(db, Clock.systemUTC());
        ProtectedLocations none = new ProtectedLocations() {
            @Override public List<Path> protectedDirectories() { return List.of(); }
            @Override public List<String> protectedDirectoryNames() { return List.of(); }
        };
        policy = new PolicyEngine(rules, new ProtectedRules(none, tmp.resolve("data")));
        authorizer = new Authorizer(roots, policy);
        root = Files.createDirectories(tmp.resolve("Documents"));
        outside = Files.createDirectories(tmp.resolve("Outside"));
        rootId = roots.insert(root, "Documents", "VOL");
    }

    @AfterEach
    void tearDown() { db.close(); }

    private Path file(Path dir, String rel) throws Exception {
        Path f = dir.resolve(rel);
        Files.createDirectories(f.getParent());
        Files.writeString(f, "hello");
        return f;
    }

    private MonitoredRoot ok(Path p) throws Exception {
        return authorizer.authorize(rootId, p, 5, "Documents", "VOL");
    }

    private NotAuthorizedException denied(Path p) {
        return assertThrows(NotAuthorizedException.class, () -> authorizer.authorize(rootId, p, 5, "Documents", "VOL"));
    }

    @Test
    void fileInsideRootIsAuthorized() throws Exception {
        MonitoredRoot r = ok(file(root, "a/b/notes.txt"));
        assertEquals(rootId, r.id());
        ok(file(root, "top.txt"));
    }

    @Test
    void nonexistentFileInExistingFolderIsAuthorizedByBoundaryChecks() throws Exception {
        Files.createDirectories(root.resolve("a"));
        ok(root.resolve("a/new-output.txt"));
    }

    @Test
    void pathOutsideRootIsRejected() throws Exception {
        denied(file(outside, "x.txt"));
    }

    @Test
    void siblingWithCommonPrefixIsRejected() throws Exception {
        denied(file(tmp.resolve("Documents2"), "x.txt"));
        denied(file(tmp.resolve("Documents-old"), "x.txt"));
    }

    @Test
    void rootItselfIsRejected() {
        denied(root);
        denied(root.resolve("."));
        denied(root.resolve("sub/.."));
    }

    @Test
    void dotDotTraversalIsNormalizedAndRejected() throws Exception {
        file(outside, "victim.txt");
        denied(root.resolve("../Outside/victim.txt"));
        denied(root.resolve("a/../../Outside/victim.txt"));
        // traversal that stays inside is fine
        file(root, "inner/f.txt");
        ok(root.resolve("other/../inner/f.txt"));
    }

    @Test
    void unknownRootIsRejected() throws Exception {
        Path f = file(root, "a.txt");
        assertThrows(NotAuthorizedException.class, () -> authorizer.authorize(rootId + 100, f, 5, "Documents", "VOL"));
    }

    @Test
    void revokedRootIsRejectedImmediately() throws Exception {
        Path f = file(root, "a.txt");
        ok(f);
        roots.revoke(rootId);
        assertThrows(NotAuthorizedException.class, () -> ok(f));
        roots.reauthorize(rootId);
        ok(f);
    }

    @Test
    void removedRootIsRejected() throws Exception {
        Path f = file(root, "a.txt");
        roots.delete(rootId);
        assertThrows(NotAuthorizedException.class, () -> ok(f));
    }

    @Test
    void missingRootFolderIsRejected() throws Exception {
        Path f = file(root, "a.txt");
        Files.delete(f);
        Files.delete(root);
        assertThrows(NotAuthorizedException.class, () -> ok(f));
    }

    @Test
    void protectedFileIsRejected() throws Exception {
        for (String n : List.of("app.exe", "state.sqlite", ".git/config", "node_modules/x/y.js", "pagefile.sys")) {
            denied(file(root, n));
        }
    }

    @Test
    void pinnedFileAndFileInPinnedFolderAreRejected() throws Exception {
        Path pinned = file(root, "keep/me.txt");
        Path inPinnedFolder = file(root, "thesis/ch1.txt");
        Path free = file(root, "free.txt");
        rules.pin(pinned);
        rules.pin(root.resolve("thesis"));
        policy.reload();
        denied(pinned);
        denied(inPinnedFolder);
        ok(free);
    }

    @Test
    void excludedFileIsRejected() throws Exception {
        Path f = file(root, "debug.log");
        rules.addExclusion("EXTENSION", "log");
        policy.reload();
        denied(f);
    }

    @Test
    void fileReachedThroughADirectoryLinkIsRejected() throws Exception {
        Path target = Files.createDirectories(outside.resolve("secret-dir"));
        Path victim = file(target, "victim.txt");
        Path link = root.resolve("link");
        Assumptions.assumeTrue(LinkSupport.createDirLink(link, target), "cannot create a symlink or junction here");

        Path viaLink = link.resolve("victim.txt");
        assertEquals("hello", Files.readString(viaLink), "sanity: the link really reaches the outside file");
        NotAuthorizedException ex = denied(viaLink);
        assertEquals(true, ex.getMessage().toLowerCase().contains("link"), ex.getMessage());
        assertEquals("hello", Files.readString(victim));
    }

    @Test
    void fileReachedThroughANestedDirectoryLinkIsRejected() throws Exception {
        Path target = Files.createDirectories(outside.resolve("t"));
        file(target, "deep/er/victim.txt");
        Files.createDirectories(root.resolve("a/b"));
        Path link = root.resolve("a/b/link");
        Assumptions.assumeTrue(LinkSupport.createDirLink(link, target), "cannot create a symlink or junction here");
        denied(link.resolve("deep/er/victim.txt"));
    }

    @Test
    void directoryLinkInsideRootPointingInsideRootIsAlsoRejected() throws Exception {
        Path real = Files.createDirectories(root.resolve("real"));
        file(real, "f.txt");
        Path link = root.resolve("alias");
        Assumptions.assumeTrue(LinkSupport.createDirLink(link, real), "cannot create a symlink or junction here");
        denied(link.resolve("f.txt"));
        ok(real.resolve("f.txt"));
    }

    @Test
    void theFileItselfBeingASymlinkIsRejected() throws Exception {
        Path target = file(outside, "victim.txt");
        Path link = root.resolve("shortcut.txt");
        Assumptions.assumeTrue(LinkSupport.createFileSymlink(link, target),
                "file symlinks need a privilege not available here");
        NotAuthorizedException ex = denied(link);
        assertEquals(true, ex.getMessage().toLowerCase().contains("link"), ex.getMessage());
    }

    @Test
    void theTargetBeingADirectoryLinkIsRejected() throws Exception {
        Path target = Files.createDirectories(outside.resolve("dir"));
        Path link = root.resolve("dirlink");
        Assumptions.assumeTrue(LinkSupport.createDirLink(link, target), "cannot create a symlink or junction here");
        denied(link);
    }
}
