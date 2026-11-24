package io.github.madaai.idlestow.core.db;

import io.github.madaai.idlestow.core.db.RulesRepository.Exclusion;
import io.github.madaai.idlestow.core.index.ScannedEntry;
import io.github.madaai.idlestow.core.model.IndexedItem;
import io.github.madaai.idlestow.core.policy.PolicyRule;
import io.github.madaai.idlestow.core.util.PathUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqlLikeAndInjectionTest {
    static long scalar(Database db, String sql, Object... args) throws Exception {
        return db.read(c -> Sql.scalarLong(c, sql, args));
    }

    static final String INJECTION = "x'); DROP TABLE indexed_items;--";

    @TempDir Path tmp;
    Database db;
    ItemRepository items;
    RootRepository roots;
    RulesRepository rules;
    Queries queries;
    Path rootPath;
    long rootId;

    @BeforeEach
    void setUp() throws Exception {
        db = Database.open(tmp.resolve("data/idlestow.db"));
        Clock clock = Clock.systemUTC();
        items = new ItemRepository(db, clock);
        roots = new RootRepository(db, clock);
        rules = new RulesRepository(db, clock);
        queries = new Queries(db);
        rootPath = tmp.resolve("root");
        rootId = roots.insert(rootPath, "root", "VOL");
    }

    @AfterEach
    void tearDown() { db.close(); }

    private ScannedEntry entry(Path p, boolean dir) {
        Path np = PathUtil.normalize(p);
        String name = np.getFileName().toString();
        Path parent = np.getParent();
        return new ScannedEntry(np, PathUtil.normalize(rootPath).relativize(np).toString(),
                PathUtil.key(parent), name, dir ? "" : PathUtil.extension(name), "Other", dir, false, null, "VOL",
                dir ? 0 : 10, 4096, true, 1, 1, 1, 1, 0, Set.of(), false);
    }

    private void index(Path... files) throws Exception {
        List<ScannedEntry> batch = new ArrayList<>();
        for (Path f : files) batch.add(entry(f, false));
        items.upsertBatch(rootId, 1, batch);
    }

    private Set<String> names(List<IndexedItem> l) {
        return l.stream().map(IndexedItem::name).collect(Collectors.toCollection(TreeSet::new));
    }

    // ---------------------------------------------------------------- likeEscape

    @Test
    void likeEscapeEscapesEveryWildcardAndTheEscapeCharacter() {
        assertEquals("", Sql.likeEscape(""));
        assertEquals("plain", Sql.likeEscape("plain"));
        assertEquals("100\\%", Sql.likeEscape("100%"));
        assertEquals("a\\_b", Sql.likeEscape("a_b"));
        assertEquals("a\\\\b", Sql.likeEscape("a\\b"));
        assertEquals("\\\\\\%\\_", Sql.likeEscape("\\%_"));
    }

    @Test
    void likeEscapeMakesSqliteMatchLiterally() throws Exception {
        for (String literal : new String[]{"50%", "a_b", "back\\slash", "%", "_", "\\", "%_\\%_\\", "it's"}) {
            String esc = Sql.likeEscape(literal);
            boolean self = db.read(c -> Sql.scalarLong(c, "SELECT ? LIKE ? ESCAPE '\\'", literal, esc) == 1);
            assertTrue(self, "'" + literal + "' must match itself");
        }
        // wildcards must not act as wildcards once escaped
        assertEquals(0L, scalar(db, "SELECT 'abc' LIKE ? ESCAPE '\\'", Sql.likeEscape("%")));
        assertEquals(0L, scalar(db, "SELECT 'abc' LIKE ? ESCAPE '\\'", Sql.likeEscape("a_c")));
        assertEquals(0L, scalar(db, "SELECT 'a\\b' LIKE ? ESCAPE '\\'", Sql.likeEscape("a\\\\b")));
    }

    @Test
    void subtreeQueryTreatsPercentAndUnderscoreInFolderNamesLiterally() throws Exception {
        Path pct = rootPath.resolve("50%");
        Path other = rootPath.resolve("50X");          // matches '50%' only if % were a wildcard
        Path us = rootPath.resolve("a_b");
        Path usOther = rootPath.resolve("aXb");        // matches 'a_b' only if _ were a wildcard
        index(pct.resolve("in-pct.txt"), other.resolve("in-other.txt"), us.resolve("in-us.txt"),
                usOther.resolve("in-usother.txt"));

        assertEquals(Set.of("in-pct.txt"), names(items.descendantsPage(pct, 0, 100)));
        assertEquals(Set.of("in-other.txt"), names(items.descendantsPage(other, 0, 100)));
        assertEquals(Set.of("in-us.txt"), names(items.descendantsPage(us, 0, 100)));
        assertEquals(Set.of("in-usother.txt"), names(items.descendantsPage(usOther, 0, 100)));
    }

    @Test
    void subtreeQueryDoesNotConfuseSiblingWithCommonPrefix() throws Exception {
        index(rootPath.resolve("data/a.txt"), rootPath.resolve("database/b.txt"));
        assertEquals(Set.of("a.txt"), names(items.descendantsPage(rootPath.resolve("data"), 0, 100)));
    }

    @Test
    void markRemovedAndFinalizeScanDoNotOverReachOnWildcardNames() throws Exception {
        Path pct = rootPath.resolve("50%");
        Path other = rootPath.resolve("50X");
        index(pct.resolve("a.txt"), other.resolve("b.txt"));
        items.markRemoved(pct);
        assertTrue(items.byPath(pct.resolve("a.txt")).isEmpty());
        assertTrue(items.byPath(other.resolve("b.txt")).isPresent(), "sibling must not be removed by a '%' in the folder name");

        Path us = rootPath.resolve("u_v");
        Path usOther = rootPath.resolve("uXv");
        index(us.resolve("c.txt"), usOther.resolve("d.txt"));
        // generation 2 sees nothing under u_v: a subtree finalize must only delete under u_v
        items.finalizeScan(rootId, 2, PathUtil.key(us));
        assertTrue(items.byPath(us.resolve("c.txt")).isEmpty());
        assertTrue(items.byPath(usOther.resolve("d.txt")).isPresent(), "'_' must not match 'X' in finalizeScan");
    }

    @Test
    void nameSearchTreatsWildcardsLiterally() throws Exception {
        index(rootPath.resolve("100%.txt"), rootPath.resolve("1000.txt"), rootPath.resolve("a_b.txt"),
                rootPath.resolve("aXb.txt"), rootPath.resolve("plain.txt"));
        assertEquals(List.of("100%.txt"), search("%"));
        assertEquals(List.of("a_b.txt"), search("_"));
        assertEquals(List.of("a_b.txt"), search("a_b"));
        assertEquals(List.of("100%.txt"), search("100%"));
        assertEquals(5, search(".txt").size());
    }

    @Test
    void searchWithBackslashAndQuoteCharactersDoesNotBreakTheQuery() throws Exception {
        index(rootPath.resolve("it's.txt"), rootPath.resolve("plain.txt"));
        assertEquals(List.of("it's.txt"), search("it's"));
        assertEquals(List.of(), search("\\"));
        assertEquals(List.of(), search(INJECTION));
        assertEquals(2L, scalar(db, "SELECT COUNT(*) FROM indexed_items WHERE is_dir=0"));
    }

    private List<String> search(String text) throws Exception {
        return queries.list(rootPath, text, Queries.Filter.ALL, Queries.SortField.NAME, true, 0, 100).stream()
                .map(r -> r.item().name()).toList();
    }

    // ---------------------------------------------------------------- injection

    @Test
    void injectionStringsInRulesAreStoredAndReadBackLiterally() throws Exception {
        rules.addExclusion("GLOB", INJECTION);
        rules.addExclusion("CATEGORY", INJECTION);
        rules.addExclusion("VOLUME", INJECTION);
        rules.addExclusion("EXTENSION", INJECTION);
        rules.addExclusion("PATH", INJECTION);
        List<Exclusion> ex = rules.exclusions();
        assertEquals(5, ex.size());
        assertTrue(ex.stream().anyMatch(e -> e.kind().equals("GLOB") && e.value().equals(INJECTION)));
        assertTrue(ex.stream().anyMatch(e -> e.kind().equals("CATEGORY") && e.value().equals(INJECTION)));
        assertTrue(ex.stream().anyMatch(e -> e.kind().equals("VOLUME") && e.value().equals(INJECTION)));
        assertTrue(ex.stream().anyMatch(e -> e.kind().equals("EXTENSION") && e.value().equals(INJECTION.toLowerCase())));
        assertTrue(ex.stream().anyMatch(e -> e.kind().equals("PATH") && e.value().endsWith(INJECTION)));
        // the schema survived
        assertEquals(0L, scalar(db, "SELECT COUNT(*) FROM indexed_items"));
        assertEquals(1, db.currentVersion());
    }

    @Test
    void injectionInPinsAndPoliciesIsLiteral() throws Exception {
        Path evil = rootPath.resolve(INJECTION);
        rules.pin(evil);
        assertEquals(1, rules.pins().size());
        assertEquals(PathUtil.normalize(evil), rules.pins().get(0).path());
        rules.savePolicy(new PolicyRule(0, PolicyRule.Scope.EXTENSION, INJECTION, 5, null, null, INJECTION, INJECTION));
        PolicyRule saved = rules.policies().get(0);
        assertEquals(INJECTION, saved.target());
        assertEquals(INJECTION, saved.allowedMethods());
        rules.unpin(evil);
        assertTrue(rules.pins().isEmpty());
        assertEquals(0L, scalar(db, "SELECT COUNT(*) FROM indexed_items"));
    }

    @Test
    void injectionInFileNamesIsStoredAndQueriedLiterally() throws Exception {
        Path evil = rootPath.resolve(INJECTION + ".txt");
        Path good = rootPath.resolve("good.txt");
        index(evil, good);
        IndexedItem got = items.byPath(evil).orElseThrow();
        assertEquals(INJECTION + ".txt", got.name());
        assertEquals(2L, items.countFiles());
        assertEquals(List.of(INJECTION + ".txt"), search("drop table"));
        items.markRemoved(evil);
        assertTrue(items.byPath(evil).isEmpty());
        assertTrue(items.byPath(good).isPresent());
        assertEquals(1L, items.countFiles());
    }

    @Test
    void injectionInRootNamesIsLiteral() throws Exception {
        long id = roots.insert(tmp.resolve("other"), INJECTION, "VOL");
        assertEquals(INJECTION, roots.byId(id).orElseThrow().displayName());
        assertEquals(2, roots.all().size());
    }

    @Test
    void invalidExclusionInputIsRejected() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> rules.addExclusion("BOGUS", "x"));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> rules.addExclusion("GLOB", "  "));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> rules.addExclusion("GLOB", null));
        org.junit.jupiter.api.Assertions.assertThrows(NumberFormatException.class, () -> rules.addExclusion("MIN_SIZE", "12; DROP"));
    }
}
