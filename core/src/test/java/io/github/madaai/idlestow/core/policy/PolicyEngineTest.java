package io.github.madaai.idlestow.core.policy;

import io.github.madaai.idlestow.core.db.Database;
import io.github.madaai.idlestow.core.db.RulesRepository;
import io.github.madaai.idlestow.core.model.Enums.Confidence;
import io.github.madaai.idlestow.core.model.Enums.Method;
import io.github.madaai.idlestow.core.model.MonitoredRoot;
import io.github.madaai.idlestow.core.platform.ProtectedLocations;
import io.github.madaai.idlestow.core.policy.PolicyEngine.EffectivePolicy;
import io.github.madaai.idlestow.core.policy.PolicyEngine.Protection;
import io.github.madaai.idlestow.core.policy.PolicyEngine.Restriction;
import io.github.madaai.idlestow.core.policy.PolicyRule.Scope;
import io.github.madaai.idlestow.core.settings.Settings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.time.Clock;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PolicyEngineTest {
    @TempDir Path tmp;
    Database db;
    RulesRepository rules;
    PolicyEngine engine;
    Path root;
    Path sysDir;
    Path dataDir;
    MonitoredRoot monitored;
    final Settings settings = Settings.defaults();

    @BeforeEach
    void setUp() throws Exception {
        db = Database.open(tmp.resolve("db/idlestow.db"));
        rules = new RulesRepository(db, Clock.systemUTC());
        root = tmp.resolve("Documents");
        sysDir = tmp.resolve("SystemStuff");
        dataDir = tmp.resolve("idlestow-data");
        ProtectedLocations platform = new ProtectedLocations() {
            @Override public List<Path> protectedDirectories() { return List.of(sysDir); }
            @Override public List<String> protectedDirectoryNames() { return List.of("CustomProtected"); }
        };
        engine = new PolicyEngine(rules, new ProtectedRules(platform, dataDir));
        monitored = root(7, root, true, null);
    }

    @AfterEach
    void tearDown() { db.close(); }

    static MonitoredRoot root(long id, Path path, boolean enabled, String revoked) {
        return new MonitoredRoot(id, path, "root", "VOL", enabled, false, false, "2026-01-01T00:00:00Z", revoked, null, null, 1);
    }

    static MonitoredRoot archivalRoot(long id, Path path) {
        return new MonitoredRoot(id, path, "root", "VOL", true, false, true, "2026-01-01T00:00:00Z", null, null, null, 1);
    }

    void policy(Scope scope, String target, Integer days, Boolean never, Boolean archival, String conf, String methods)
            throws Exception {
        rules.savePolicy(new PolicyRule(0, scope, target, days, never, archival, conf, methods));
        engine.reload();
    }

    EffectivePolicy eff(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return engine.effective(file, dot < 0 ? "" : name.substring(dot + 1), monitored, settings);
    }

    Protection prot(Path p) {
        return engine.protection(p, false, 100, "Documents", "VOL", monitored);
    }

    // ------------------------------------------------------------------ precedence

    @Test
    void noPoliciesFallsBackToSettingsDefaults() {
        EffectivePolicy e = eff(root.resolve("a/b.txt"));
        assertEquals(30, e.inactivityDays());
        assertFalse(e.neverOptimize());
        assertFalse(e.archival());
        assertEquals(Confidence.HIGH, e.minConfidenceAuto());
        assertEquals(EnumSet.allOf(Method.class), e.allowedMethods());
        assertTrue(e.source().startsWith("Default inactivity threshold"), e.source());
        assertTrue(e.source().contains("30 days"), e.source());
    }

    @Test
    void settingsDefaultIsUsedWhenSettingsChange() {
        Settings custom = settings.with("policy.inactivityDays", "75");
        EffectivePolicy e = engine.effective(root.resolve("x.txt"), "txt", monitored, custom);
        assertEquals(75, e.inactivityDays());
    }

    @Test
    void precedenceFileThenDirectoryThenExtensionThenRootThenGlobalThenDefault() throws Exception {
        Path file = root.resolve("a/b/f.txt");
        Path fileInA = root.resolve("a/other.txt");
        Path elsewhereTxt = root.resolve("c/g.txt");
        Path elsewhereMd = root.resolve("c/g.md");

        policy(Scope.GLOBAL, "*", 100, null, null, null, null);
        assertEquals(100, eff(elsewhereMd).inactivityDays());
        assertTrue(eff(elsewhereMd).source().startsWith("Global policy"), eff(elsewhereMd).source());

        policy(Scope.ROOT, "7", 90, null, null, null, null);
        assertEquals(90, eff(elsewhereMd).inactivityDays());
        assertTrue(eff(elsewhereMd).source().startsWith("Monitored folder policy for"), eff(elsewhereMd).source());

        policy(Scope.EXTENSION, "txt", 60, null, null, null, null);
        assertEquals(60, eff(elsewhereTxt).inactivityDays());
        assertEquals(90, eff(elsewhereMd).inactivityDays(), "other extensions still use the root policy");
        assertTrue(eff(elsewhereTxt).source().startsWith("File type policy (.txt)"), eff(elsewhereTxt).source());

        policy(Scope.DIRECTORY, root.resolve("a").toString(), 45, null, null, null, null);
        assertEquals(45, eff(fileInA).inactivityDays(), "directory beats extension");
        assertEquals(45, eff(file).inactivityDays());
        assertEquals(60, eff(elsewhereTxt).inactivityDays());
        assertTrue(eff(fileInA).source().startsWith("Folder policy for"), eff(fileInA).source());

        policy(Scope.DIRECTORY, root.resolve("a/b").toString(), 20, null, null, null, null);
        assertEquals(20, eff(file).inactivityDays(), "the deepest directory wins");
        assertEquals(45, eff(fileInA).inactivityDays());
        assertTrue(eff(file).source().contains(root.resolve("a/b").toString().toLowerCase())
                || eff(file).source().toLowerCase().contains("b"), eff(file).source());

        policy(Scope.FILE, file.toString(), 7, null, null, null, null);
        assertEquals(7, eff(file).inactivityDays(), "file beats directory");
        assertTrue(eff(file).source().startsWith("File policy"), eff(file).source());
        assertEquals(20, eff(root.resolve("a/b/sibling.txt")).inactivityDays());
    }

    @Test
    void directoryPolicyDoesNotLeakToSiblingWithCommonPrefix() throws Exception {
        policy(Scope.DIRECTORY, root.resolve("data").toString(), 11, null, null, null, null);
        assertEquals(11, eff(root.resolve("data/x.txt")).inactivityDays());
        assertEquals(30, eff(root.resolve("database/x.txt")).inactivityDays());
    }

    @Test
    void fieldsAreResolvedIndependently() throws Exception {
        policy(Scope.GLOBAL, "*", 100, null, null, "MEDIUM", "NATIVE,ZIP_COPY");
        policy(Scope.DIRECTORY, root.resolve("a").toString(), null, true, null, null, null);
        policy(Scope.FILE, root.resolve("a/f.txt").toString(), 5, null, true, null, null);

        EffectivePolicy e = eff(root.resolve("a/f.txt"));
        assertEquals(5, e.inactivityDays(), "days from FILE");
        assertTrue(e.neverOptimize(), "never from DIRECTORY");
        assertTrue(e.archival(), "archival from FILE");
        assertEquals(Confidence.MEDIUM, e.minConfidenceAuto(), "confidence from GLOBAL");
        assertEquals(Set.of(Method.NATIVE, Method.ZIP_COPY), e.allowedMethods(), "methods from GLOBAL");
        assertTrue(e.source().startsWith("File policy"), e.source());

        EffectivePolicy other = eff(root.resolve("a/g.txt"));
        assertEquals(100, other.inactivityDays());
        assertTrue(other.neverOptimize());
        assertFalse(other.archival());
    }

    @Test
    void explicitNeverOptimizeFalseAtDeeperLevelOverridesShallowTrue() throws Exception {
        policy(Scope.DIRECTORY, root.resolve("a").toString(), null, true, null, null, null);
        assertTrue(eff(root.resolve("a/f.txt")).neverOptimize());
        policy(Scope.FILE, root.resolve("a/f.txt").toString(), null, false, null, null, null);
        assertFalse(eff(root.resolve("a/f.txt")).neverOptimize(), "explicit deeper false wins");
        assertTrue(eff(root.resolve("a/h.txt")).neverOptimize());
    }

    @Test
    void explicitNeverOptimizeTrueAtDeeperLevelOverridesShallowFalse() throws Exception {
        policy(Scope.GLOBAL, "*", null, false, null, null, null);
        policy(Scope.DIRECTORY, root.resolve("a/b").toString(), null, true, null, null, null);
        assertTrue(eff(root.resolve("a/b/f.txt")).neverOptimize());
        assertFalse(eff(root.resolve("a/f.txt")).neverOptimize());
    }

    @Test
    void rootArchivalFlagIsUsedUnlessAPolicyOverridesIt() throws Exception {
        monitored = archivalRoot(7, root);
        assertTrue(eff(root.resolve("f.txt")).archival());
        policy(Scope.DIRECTORY, root.resolve("work").toString(), null, null, false, null, null);
        assertFalse(eff(root.resolve("work/f.txt")).archival());
        assertTrue(eff(root.resolve("f.txt")).archival());
    }

    @Test
    void rootPolicyOnlyAppliesToItsOwnRoot() throws Exception {
        policy(Scope.ROOT, "999", 12, null, null, null, null);
        assertEquals(30, eff(root.resolve("f.txt")).inactivityDays());
        policy(Scope.ROOT, "7", 13, null, null, null, null);
        assertEquals(13, eff(root.resolve("f.txt")).inactivityDays());
    }

    @Test
    void extensionPolicyIsCaseInsensitiveOnTheFileExtension() throws Exception {
        policy(Scope.EXTENSION, "txt", 9, null, null, null, null);
        assertEquals(9, engine.effective(root.resolve("f.TXT"), "TXT", monitored, settings).inactivityDays());
        assertEquals(30, engine.effective(root.resolve("f"), "", monitored, settings).inactivityDays());
        assertEquals(30, engine.effective(root.resolve("f"), null, monitored, settings).inactivityDays());
    }

    @Test
    void minConfidenceIsNeverBelowMediumAndGarbageFallsBackToSettings() throws Exception {
        policy(Scope.GLOBAL, "*", null, null, null, "LOW", null);
        assertEquals(Confidence.MEDIUM, eff(root.resolve("f.txt")).minConfidenceAuto());
        policy(Scope.GLOBAL, "*", null, null, null, "NONE", null);
        assertEquals(Confidence.MEDIUM, eff(root.resolve("f.txt")).minConfidenceAuto());
        policy(Scope.GLOBAL, "*", null, null, null, "wat", null);
        assertEquals(Confidence.HIGH, eff(root.resolve("f.txt")).minConfidenceAuto());
        policy(Scope.GLOBAL, "*", null, null, null, "high", null);
        assertEquals(Confidence.HIGH, eff(root.resolve("f.txt")).minConfidenceAuto());
    }

    @Test
    void allowedMethodsParsingIgnoresUnknownNamesAndFailsClosed() throws Exception {
        policy(Scope.GLOBAL, "*", null, null, null, null, " NATIVE , bogus,ZIP_COPY,,native ");
        assertEquals(Set.of(Method.NATIVE, Method.ZIP_COPY), eff(root.resolve("f.txt")).allowedMethods());

        policy(Scope.GLOBAL, "*", null, null, null, null, "totally,unknown");
        assertTrue(eff(root.resolve("f.txt")).allowedMethods().isEmpty(),
                "a policy naming only unknown methods must allow nothing, never everything");

        policy(Scope.GLOBAL, "*", null, null, null, null, "ZIP_REPLACE");
        assertEquals(Set.of(Method.ZIP_REPLACE), eff(root.resolve("f.txt")).allowedMethods());
    }

    @Test
    void policyVersionChangesWhenRulesOrRelevantSettingsChange() throws Exception {
        int v0 = eff(root.resolve("f.txt")).version();
        policy(Scope.GLOBAL, "*", 100, null, null, null, null);
        int v1 = eff(root.resolve("f.txt")).version();
        assertNotEquals(v0, v1);
        assertEquals(v1, engine.version(settings));
        assertNotEquals(v1, engine.version(settings.with("policy.inactivityDays", "31")));
        rules.addExclusion("EXTENSION", "log");
        engine.reload();
        assertNotEquals(v1, engine.version(settings));
    }

    @Test
    void savingAPolicyTwiceReplacesIt() throws Exception {
        policy(Scope.GLOBAL, "*", 100, null, null, null, null);
        policy(Scope.GLOBAL, "*", 55, null, null, null, null);
        assertEquals(1, rules.policies().size());
        assertEquals(55, eff(root.resolve("f.txt")).inactivityDays());
    }

    // ------------------------------------------------------------------ mandatory protections

    @Test
    void ordinaryFileInRootIsNotRestricted() {
        Protection p = prot(root.resolve("Uni/notes.txt"));
        assertEquals(Restriction.NONE, p.restriction());
        assertFalse(p.blocked());
    }

    @Test
    void protectedDirectoryFromPlatformAndDataDir() {
        MonitoredRoot wide = root(8, tmp, true, null);
        assertEquals(Restriction.PROTECTED, engine.protection(sysDir.resolve("a.txt"), false, 1, "Documents", "VOL", wide).restriction());
        assertEquals(Restriction.PROTECTED, engine.protection(sysDir, true, 0, "Folder", "VOL", wide).restriction());
        assertEquals(Restriction.PROTECTED, engine.protection(dataDir.resolve("idlestow.db-wal"), false, 1, "Data", "VOL", wide).restriction());
        assertEquals(Restriction.NONE, engine.protection(tmp.resolve("SystemStuffExtra/a.txt"), false, 1, "Documents", "VOL", wide).restriction(),
                "prefix trap: SystemStuffExtra is not SystemStuff");
    }

    @Test
    void userHomeAppDataIsProtectedEvenWhenNotConfigured() {
        Path appData = Path.of(System.getProperty("user.home"), "AppData", "Local", "x.txt");
        MonitoredRoot wide = root(9, Path.of(System.getProperty("user.home")), true, null);
        assertEquals(Restriction.PROTECTED, engine.protection(appData, false, 1, "Documents", "VOL", wide).restriction());
    }

    @ParameterizedTest
    @ValueSource(strings = {".git/config", "src/.git/HEAD", "node_modules/pkg/index.js", "a/node_modules/b/c.txt",
            "AppData/Local/x.txt", "APPDATA/x.txt", ".GIT/config", ".venv/lib/x.py", "customprotected/a.txt",
            "CustomProtected/sub/a.txt", ".idlestow/recovery/x", "$Recycle.Bin/x.txt", "steamapps/common/g.txt"})
    void protectedDirectoryNamesAnywhere(String rel) {
        Protection p = prot(root.resolve(rel));
        assertEquals(Restriction.PROTECTED, p.restriction(), rel);
        assertFalse(p.reason().isBlank());
    }

    @ParameterizedTest
    @ValueSource(strings = {"a.exe", "A.EXE", "lib.dll", "state.sqlite", "dump.sqlite3", "disk.vmdk", "vault.kdbx",
            "mail.pst", "mail.ost", "disk.vhdx", "x.msi", "x.sys", "x.db", "x.db-wal", "x.lnk", "e.so", "v.qcow2"})
    void protectedExtensions(String name) {
        Protection p = prot(root.resolve("docs/" + name));
        assertEquals(Restriction.PROTECTED, p.restriction(), name);
        assertTrue(p.reason().toLowerCase().contains("protected"), p.reason());
    }

    @ParameterizedTest
    @ValueSource(strings = {"pagefile.sys", "PAGEFILE.SYS", "hiberfil.sys", "swapfile.sys", "desktop.ini", "Thumbs.db",
            ".DS_Store", "NTUSER.DAT"})
    void protectedSystemFileNames(String name) {
        assertEquals(Restriction.PROTECTED, prot(root.resolve(name)).restriction(), name);
    }

    @Test
    void ordinaryExtensionsAreNotProtected() {
        for (String n : List.of("a.txt", "a.pdf", "a.docx", "a.zip", "a.tar.gz", "exe", "dll.txt", "notexe", "a.exe.txt", ".exe")) {
            assertEquals(Restriction.NONE, prot(root.resolve("docs/" + n)).restriction(), n);
        }
    }

    @Test
    void protectedExtensionRuleDoesNotApplyToDirectories() {
        assertEquals(Restriction.NONE,
                engine.protection(root.resolve("docs/folder.exe"), true, 0, "Folder", "VOL", monitored).restriction());
    }

    @Test
    void mandatoryProtectionWinsOverEverythingElse() throws Exception {
        Path exe = root.resolve("tools/setup.exe");
        rules.pin(exe);
        rules.addExclusion("EXTENSION", "exe");
        engine.reload();
        assertEquals(Restriction.PROTECTED, prot(exe).restriction(), "protection beats pin and exclusion");

        Path outsideAndProtected = tmp.resolve("elsewhere/.git/config");
        assertEquals(Restriction.PROTECTED, prot(outsideAndProtected).restriction(), "protection beats OUTSIDE_ROOT");

        MonitoredRoot revoked = root(7, root, true, "2026-02-02T00:00:00Z");
        assertEquals(Restriction.PROTECTED,
                engine.protection(exe, false, 1, "Executables", "VOL", revoked).restriction(), "protection beats REVOKED");
    }

    // ------------------------------------------------------------------ authorization boundary

    @Test
    void outsideRootAndNoRoot() {
        assertEquals(Restriction.OUTSIDE_ROOT, prot(tmp.resolve("Other/a.txt")).restriction());
        assertEquals(Restriction.OUTSIDE_ROOT, engine.protection(root.resolve("a.txt"), false, 1, "Documents", "VOL", null).restriction());
    }

    @Test
    void rootPrefixTrapIsNotInsideRoot() {
        assertEquals(Restriction.OUTSIDE_ROOT, prot(tmp.resolve("Documents2/a.txt")).restriction());
        assertEquals(Restriction.OUTSIDE_ROOT, prot(tmp.resolve("Documents-old/a.txt")).restriction());
    }

    @Test
    void traversalThatEscapesTheRootIsOutside() {
        assertEquals(Restriction.OUTSIDE_ROOT, prot(root.resolve("../Other/a.txt")).restriction());
        assertEquals(Restriction.NONE, prot(root.resolve("sub/../a.txt")).restriction());
    }

    @Test
    void revokedOrDisabledRootIsRevoked() {
        MonitoredRoot revoked = root(7, root, true, "2026-02-02T00:00:00Z");
        MonitoredRoot disabled = root(7, root, false, null);
        assertEquals(Restriction.REVOKED, engine.protection(root.resolve("a.txt"), false, 1, "Documents", "VOL", revoked).restriction());
        assertEquals(Restriction.REVOKED, engine.protection(root.resolve("a.txt"), false, 1, "Documents", "VOL", disabled).restriction());
    }

    // ------------------------------------------------------------------ pins & exclusions

    @Test
    void pinnedFileAndPinnedAncestorFolder() throws Exception {
        rules.pin(root.resolve("keep/me.txt"));
        rules.pin(root.resolve("thesis"));
        engine.reload();
        Protection file = prot(root.resolve("keep/me.txt"));
        assertEquals(Restriction.PINNED, file.restriction());
        assertEquals("Pinned by you", file.reason());
        assertEquals(Restriction.NONE, prot(root.resolve("keep/other.txt")).restriction());

        Protection inFolder = prot(root.resolve("thesis/ch1/draft.txt"));
        assertEquals(Restriction.PINNED, inFolder.restriction());
        assertTrue(inFolder.reason().startsWith("Inside pinned folder"), inFolder.reason());
        assertEquals(Restriction.NONE, prot(root.resolve("thesis2/draft.txt")).restriction(), "prefix trap on pinned folder");

        rules.unpin(root.resolve("thesis"));
        engine.reload();
        assertEquals(Restriction.NONE, prot(root.resolve("thesis/ch1/draft.txt")).restriction());
    }

    @Test
    void pinLookupIsCaseInsensitiveOnWindowsOnly() throws Exception {
        rules.pin(root.resolve("Keep/Me.txt"));
        engine.reload();
        Restriction r = prot(root.resolve("keep/me.txt")).restriction();
        assertEquals(io.github.madaai.idlestow.core.util.PathUtil.isWindows() ? Restriction.PINNED : Restriction.NONE, r);
    }

    @Test
    void pathExclusionCoversSubtree() throws Exception {
        rules.addExclusion("PATH", root.resolve("private").toString());
        engine.reload();
        assertEquals(Restriction.EXCLUDED, prot(root.resolve("private/a/b.txt")).restriction());
        assertEquals(Restriction.EXCLUDED, prot(root.resolve("private")).restriction());
        assertEquals(Restriction.NONE, prot(root.resolve("private2/b.txt")).restriction());
    }

    @Test
    void extensionExclusionNormalizesDotAndCase() throws Exception {
        rules.addExclusion("EXTENSION", ".LOG");
        engine.reload();
        assertEquals(Restriction.EXCLUDED, prot(root.resolve("a/b.log")).restriction());
        assertEquals(Restriction.EXCLUDED, prot(root.resolve("a/b.LOG")).restriction());
        assertEquals(Restriction.NONE, prot(root.resolve("a/b.logx")).restriction());
        assertEquals(Restriction.NONE,
                engine.protection(root.resolve("a/folder.log"), true, 0, "Folder", "VOL", monitored).restriction());
    }

    @Test
    void sizeExclusions() throws Exception {
        rules.addExclusion("MIN_SIZE", "1000");
        rules.addExclusion("MAX_SIZE", "1000000");
        engine.reload();
        Path f = root.resolve("a.txt");
        assertEquals(Restriction.EXCLUDED, engine.protection(f, false, 999, "Documents", "VOL", monitored).restriction());
        assertEquals(Restriction.NONE, engine.protection(f, false, 1000, "Documents", "VOL", monitored).restriction());
        assertEquals(Restriction.NONE, engine.protection(f, false, 1_000_000, "Documents", "VOL", monitored).restriction());
        assertEquals(Restriction.EXCLUDED, engine.protection(f, false, 1_000_001, "Documents", "VOL", monitored).restriction());
        assertEquals(Restriction.NONE, engine.protection(root.resolve("dir"), true, 5, "Folder", "VOL", monitored).restriction(),
                "size rules do not apply to directories");
    }

    @Test
    void categoryAndVolumeExclusions() throws Exception {
        rules.addExclusion("CATEGORY", "Video");
        rules.addExclusion("VOLUME", "OTHERVOL");
        engine.reload();
        Path f = root.resolve("a.mp4");
        assertEquals(Restriction.EXCLUDED, engine.protection(f, false, 5, "video", "VOL", monitored).restriction());
        assertEquals(Restriction.NONE, engine.protection(f, false, 5, "Documents", "VOL", monitored).restriction());
        assertEquals(Restriction.EXCLUDED, engine.protection(f, false, 5, "Documents", "othervol", monitored).restriction());
    }

    @Test
    void globExclusionsMatchNameAndFullPath() throws Exception {
        rules.addExclusion("GLOB", "*.tmp");
        rules.addExclusion("GLOB", "**secret**");
        engine.reload();
        assertEquals(Restriction.EXCLUDED, prot(root.resolve("a/b/c.tmp")).restriction());
        assertEquals(Restriction.EXCLUDED, prot(root.resolve("a/mysecretfile.txt")).restriction());
        assertEquals(Restriction.EXCLUDED, prot(root.resolve("secretdir/x.txt")).restriction());
        assertEquals(Restriction.NONE, prot(root.resolve("a/b/c.txt")).restriction());
    }

    @Test
    void invalidGlobPatternIsRejectedWhenAdded() throws Exception {
        // An invalid pattern would silently exclude nothing, so it is refused up front with an error.
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> rules.addExclusion("GLOB", "[unclosed"));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> rules.addExclusion("GLOB", "{a,b"));
        engine.reload();
        assertEquals(Restriction.NONE, prot(root.resolve("a.txt")).restriction());
        assertEquals(0, rules.exclusions().size());
    }

    @Test
    void pinBeatsExclusion() throws Exception {
        rules.pin(root.resolve("a.txt"));
        rules.addExclusion("EXTENSION", "txt");
        engine.reload();
        assertEquals(Restriction.PINNED, prot(root.resolve("a.txt")).restriction());
        assertEquals(Restriction.EXCLUDED, prot(root.resolve("b.txt")).restriction());
    }

    // ------------------------------------------------------------------ ProtectedRules misc

    @Test
    void idleStowArchiveRecognition() {
        assertTrue(ProtectedRules.isIdleStowArchive("notes.txt.idlestow.zip"));
        assertTrue(ProtectedRules.isIdleStowArchive("NOTES.IDLESTOW.TAR.ZST"));
        assertTrue(ProtectedRules.isIdleStowArchive("notes.txt.idlestow-tmp-abc123"));
        assertFalse(ProtectedRules.isIdleStowArchive("notes.zip"));
        assertFalse(ProtectedRules.isIdleStowArchive("idlestow.zip.txt"));
        assertFalse(ProtectedRules.isIdleStowArchive(""));
    }

    @Test
    void cloudSyncFolderDetection() {
        ProtectedRules pr = engine.protectedRules();
        assertFalse(pr.inCloudSyncFolder(root.resolve("a.txt")));
        assertFalse(pr.inCloudSyncFolder(tmp));
        String home = System.getProperty("user.home");
        assertTrue(pr.inCloudSyncFolder(Path.of(home, "Dropbox", "a.txt")));
        assertTrue(pr.inCloudSyncFolder(Path.of(home, "Google Drive", "x", "a.txt")));
        assertFalse(pr.inCloudSyncFolder(Path.of(home, "DropboxBackup", "a.txt")), "prefix trap");
        assertNotNull(pr);
    }
}
