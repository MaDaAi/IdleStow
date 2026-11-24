package io.github.madaai.idlestow.core.db;

import io.github.madaai.idlestow.core.db.CandidateRepository.Candidate;
import io.github.madaai.idlestow.core.db.CandidateRepository.NewCandidate;
import io.github.madaai.idlestow.core.db.CandidateRepository.PlanItem;
import io.github.madaai.idlestow.core.index.ScannedEntry;
import io.github.madaai.idlestow.core.model.Enums.CandidateStatus;
import io.github.madaai.idlestow.core.model.Enums.Method;
import io.github.madaai.idlestow.core.util.PathUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CandidateRepositoryTest {
    @TempDir Path tmp;
    Database db;
    CandidateRepository repo;
    ItemRepository items;
    long rootId;
    Path rootPath;
    long item1;
    long item2;
    long item3;
    final Clock clock = Clock.fixed(Instant.parse("2026-06-01T00:00:00Z"), ZoneOffset.UTC);

    @BeforeEach
    void setUp() throws Exception {
        db = Database.open(tmp.resolve("db/idlestow.db"));
        repo = new CandidateRepository(db, clock);
        items = new ItemRepository(db, clock);
        rootPath = tmp.resolve("root");
        rootId = new RootRepository(db, clock).insert(rootPath, "root", "VOL");
        item1 = index("a.txt");
        item2 = index("b.txt");
        item3 = index("c.txt");
    }

    @AfterEach
    void tearDown() { db.close(); }

    private long index(String name) throws Exception {
        Path p = PathUtil.normalize(rootPath.resolve(name));
        items.upsertBatch(rootId, 1, List.of(new ScannedEntry(p, name, PathUtil.key(rootPath), name, "txt", "Documents",
                false, false, null, "VOL", 100, 4096, true, 1, 1, 1, 1, 0, Set.of(), false)));
        return items.byPath(p).orElseThrow().id();
    }

    private NewCandidate nc(long item, long size, long mtime) {
        return new NewCandidate(item, Method.NATIVE, 500, "estimate", "reason", "policy", "INACTIVE", "HIGH", "",
                true, size, mtime, "VOL:1");
    }

    private Candidate cand(long item) throws Exception {
        return repo.byItem(item).orElseThrow();
    }

    @Test
    void insertsFreshCandidatesAsPending() throws Exception {
        repo.replaceForRoot(rootId, List.of(nc(item1, 100, 1), nc(item2, 100, 1)));
        assertEquals(2, repo.pendingCount());
        assertEquals(1000, repo.pendingSavings());
        assertEquals(CandidateStatus.PENDING, cand(item1).status());
    }

    @Test
    void rejectedAndDismissedSurviveWhileFileIsUnchanged() throws Exception {
        repo.replaceForRoot(rootId, List.of(nc(item1, 100, 1), nc(item2, 100, 1), nc(item3, 100, 1)));
        repo.setStatus(List.of(cand(item1).id()), CandidateStatus.REJECTED, null);
        repo.setStatus(List.of(cand(item2).id()), CandidateStatus.DISMISSED, null);
        repo.replaceForRoot(rootId, List.of(nc(item1, 100, 1), nc(item2, 100, 1), nc(item3, 100, 1)));
        assertEquals(CandidateStatus.REJECTED, cand(item1).status());
        assertEquals(CandidateStatus.DISMISSED, cand(item2).status());
        assertEquals(CandidateStatus.PENDING, cand(item3).status());
    }

    @Test
    void decisionsResetToPendingWhenSizeOrMtimeChanges() throws Exception {
        repo.replaceForRoot(rootId, List.of(nc(item1, 100, 1), nc(item2, 100, 1)));
        repo.setStatus(List.of(cand(item1).id(), cand(item2).id()), CandidateStatus.REJECTED, null);
        repo.replaceForRoot(rootId, List.of(nc(item1, 101, 1), nc(item2, 100, 2)));
        assertEquals(CandidateStatus.PENDING, cand(item1).status(), "size changed");
        assertEquals(CandidateStatus.PENDING, cand(item2).status(), "mtime changed");
        assertEquals(101, cand(item1).snapshotSize());
        assertEquals(2, cand(item2).snapshotMtime());
    }

    @Test
    void stalePendingCandidatesAreRemovedButDecisionsOfRejectedAreKept() throws Exception {
        repo.replaceForRoot(rootId, List.of(nc(item1, 100, 1), nc(item2, 100, 1)));
        repo.setStatus(List.of(cand(item2).id()), CandidateStatus.REJECTED, null);
        repo.replaceForRoot(rootId, List.of());
        assertTrue(repo.byItem(item1).isEmpty(), "stale PENDING removed");
        assertEquals(CandidateStatus.REJECTED, cand(item2).status(), "REJECTED is a user decision and is kept");
    }

    @Test
    void replaceForRootDoesNotTouchOtherRoots() throws Exception {
        Path other = tmp.resolve("other");
        long otherRoot = new RootRepository(db, clock).insert(other, "other", "VOL");
        Path p = PathUtil.normalize(other.resolve("z.txt"));
        items.upsertBatch(otherRoot, 1, List.of(new ScannedEntry(p, "z.txt", PathUtil.key(other), "z.txt", "txt", "Documents",
                false, false, null, "VOL", 100, 4096, true, 1, 1, 1, 1, 0, Set.of(), false)));
        long z = items.byPath(p).orElseThrow().id();
        repo.replaceForRoot(otherRoot, List.of(nc(z, 100, 1)));
        repo.replaceForRoot(rootId, List.of(nc(item1, 100, 1)));
        assertTrue(repo.byItem(z).isPresent());
    }

    @Test
    void postponementExpiry() throws Exception {
        long now = clock.millis();
        repo.replaceForRoot(rootId, List.of(nc(item1, 100, 1), nc(item2, 100, 1)));
        repo.setStatus(List.of(cand(item1).id()), CandidateStatus.POSTPONED, now + Duration.ofDays(1).toMillis());
        repo.setStatus(List.of(cand(item2).id()), CandidateStatus.POSTPONED, now - 1000);
        repo.replaceForRoot(rootId, List.of(nc(item1, 100, 1), nc(item2, 100, 1)));
        assertEquals(CandidateStatus.POSTPONED, cand(item1).status(), "still postponed");
        assertEquals(CandidateStatus.PENDING, cand(item2).status(), "expired postponement returns to PENDING");
    }

    @Test
    void postponedCandidateComesBackIfTheFileChanged() throws Exception {
        repo.replaceForRoot(rootId, List.of(nc(item1, 100, 1)));
        repo.setStatus(List.of(cand(item1).id()), CandidateStatus.POSTPONED, clock.millis() + 10_000_000);
        repo.replaceForRoot(rootId, List.of(nc(item1, 999, 1)));
        assertEquals(CandidateStatus.PENDING, cand(item1).status());
    }

    @Test
    void planDigestChangesWhenAnyFieldChanges() {
        List<PlanItem> base = List.of(new PlanItem(1, "p1", 10, 20, "k1"), new PlanItem(2, "p2", 30, 40, "k2"));
        String d = CandidateRepository.digest(Method.NATIVE, base);
        assertEquals(d, CandidateRepository.digest(Method.NATIVE, List.of(new PlanItem(1, "p1", 10, 20, "k1"), new PlanItem(2, "p2", 30, 40, "k2"))));
        assertNotEquals(d, CandidateRepository.digest(Method.ZIP_COPY, base), "method");
        assertNotEquals(d, CandidateRepository.digest(Method.NATIVE, List.of(base.get(0))), "item removed");
        assertNotEquals(d, CandidateRepository.digest(Method.NATIVE, List.of(base.get(1), base.get(0))), "order");
        assertNotEquals(d, CandidateRepository.digest(Method.NATIVE, List.of(new PlanItem(9, "p1", 10, 20, "k1"), base.get(1))), "item id");
        assertNotEquals(d, CandidateRepository.digest(Method.NATIVE, List.of(new PlanItem(1, "p1x", 10, 20, "k1"), base.get(1))), "path");
        assertNotEquals(d, CandidateRepository.digest(Method.NATIVE, List.of(new PlanItem(1, "p1", 11, 20, "k1"), base.get(1))), "size");
        assertNotEquals(d, CandidateRepository.digest(Method.NATIVE, List.of(new PlanItem(1, "p1", 10, 21, "k1"), base.get(1))), "mtime");
        assertNotEquals(d, CandidateRepository.digest(Method.NATIVE, List.of(new PlanItem(1, "p1", 10, 20, "k9"), base.get(1))), "key");
    }

    @Test
    void approvalIsSingleUseAndBoundToPlanContents() throws Exception {
        long plan = repo.createPlan(Method.NATIVE, "MANUAL", "ASK", 1,
                List.of(new PlanItem(item1, "p", 10, 20, "k")), 500);
        assertEquals("The plan has not been approved", repo.consumeApproval(plan));
        repo.approve(plan, Duration.ofHours(1));
        assertEquals("", repo.consumeApproval(plan));
        assertEquals("The approval was already used", repo.consumeApproval(plan));

        long plan2 = repo.createPlan(Method.NATIVE, "MANUAL", "ASK", 1, List.of(new PlanItem(item1, "p", 10, 20, "k")), 500);
        repo.approve(plan2, Duration.ofHours(1));
        db.tx(c -> Sql.update(c, "UPDATE plan_items SET snapshot_size=11 WHERE plan_id=?", plan2));
        assertEquals("The plan changed after it was approved", repo.consumeApproval(plan2));

        long plan3 = repo.createPlan(Method.NATIVE, "MANUAL", "ASK", 1, List.of(new PlanItem(item1, "p", 10, 20, "k")), 500);
        repo.approve(plan3, Duration.ofSeconds(-5));
        assertEquals("The approval has expired", repo.consumeApproval(plan3));
        assertEquals("Plan not found", repo.consumeApproval(99999));
    }
}
