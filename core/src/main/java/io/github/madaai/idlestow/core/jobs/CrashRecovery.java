package io.github.madaai.idlestow.core.jobs;

import io.github.madaai.idlestow.core.db.JobRepository;
import io.github.madaai.idlestow.core.db.JobRepository.JournalEntry;
import io.github.madaai.idlestow.core.db.JobRepository.Operation;
import io.github.madaai.idlestow.core.db.RecoveryRepository;
import io.github.madaai.idlestow.core.ipc.WorkerException;
import io.github.madaai.idlestow.core.model.Enums.ArchiveFormat;
import io.github.madaai.idlestow.core.model.Enums.JobState;
import io.github.madaai.idlestow.core.model.Enums.Method;
import io.github.madaai.idlestow.core.model.Enums.OptStatus;
import io.github.madaai.idlestow.core.model.Enums.RecoveryKind;
import io.github.madaai.idlestow.core.model.Enums.RecoveryStatus;
import io.github.madaai.idlestow.core.model.IndexedItem;
import io.github.madaai.idlestow.core.platform.NativeFileState;
import io.github.madaai.idlestow.core.util.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Startup recovery for operations interrupted by a crash, power loss or forced termination.
 *
 * <p>Each unfinished operation is inspected using its journal and the actual files on disk. Safe
 * outcomes are completed or rolled back automatically; anything ambiguous is flagged for review and
 * no file is deleted unless the journal proves IdleStow created it and the original is intact.
 */
public final class CrashRecovery {
    private static final Logger LOG = LoggerFactory.getLogger(CrashRecovery.class);

    private final JobContext ctx;

    public CrashRecovery(JobContext ctx) {
        this.ctx = ctx;
    }

    public record Report(int inspected, int completed, int rolledBack, int review, List<String> notes) { }

    public Report run() throws SQLException {
        JobRepository jobs = ctx.jobs();
        List<Operation> ops = jobs.unfinishedOperations();
        int completed = 0, rolled = 0, review = 0;
        List<String> notes = new ArrayList<>();
        for (Operation op : ops) {
            String result;
            try {
                result = recover(op);
            } catch (RuntimeException | IOException e) {
                LOG.error("Recovery of operation {} failed", op.id(), e);
                result = "REVIEW";
                flagItem(op, "Automatic recovery failed: " + e.getMessage());
            }
            switch (result) {
                case "DONE" -> completed++;
                case "ROLLED_BACK" -> rolled++;
                default -> review++;
            }
            String note = op.action() + " " + op.path() + " → " + result;
            notes.add(note);
            LOG.info("Crash recovery: {}", note);
            jobs.finishOperation(op.id(), result, null, null, null, null,
                    "Interrupted; recovered at startup (" + result + ")");
        }
        // Jobs left in non-terminal states are closed out.
        for (JobRepository.Job j : jobs.jobsInStates(List.of(JobState.QUEUED, JobState.PREFLIGHT, JobState.RUNNING,
                JobState.VERIFYING, JobState.COMMITTING, JobState.PAUSED, JobState.APPROVED, JobState.CREATED))) {
            boolean anyReview = jobs.operations(j.id()).stream().anyMatch(o -> "REVIEW".equals(o.state()));
            jobs.setState(j.id(), anyReview ? JobState.RECOVERY_REQUIRED : JobState.FAILED,
                    anyReview ? "Interrupted — some items need your review in the Recovery center"
                            : "Interrupted by shutdown or crash; recovered automatically");
        }
        return new Report(ops.size(), completed, rolled, review, notes);
    }

    private String recover(Operation op) throws SQLException, IOException {
        List<JournalEntry> journal = ctx.jobs().journalOf(op.id());
        List<String> steps = journal.stream().map(JournalEntry::step).toList();
        Map<String, Object> plan = Json.read(op.snapshot());
        return switch (op.action()) {
            case "OPTIMIZE" -> op.method() == Method.NATIVE ? recoverNative(op, steps, journal)
                    : recoverArchive(op, steps, journal, plan);
            case "DECOMPRESS" -> recoverDecompress(op);
            case "RESTORE" -> recoverRestore(op, steps, plan);
            default -> steps.contains("ARCHIVE_VERIFIED") && "PURGE_RETAINED".equals(op.action())
                    ? recoverPurge(op) : "ROLLED_BACK";
        };
    }

    private static String dataOf(List<JournalEntry> journal, String step) {
        for (JournalEntry e : journal) if (e.step().equals(step)) return e.data();
        return null;
    }

    // ---------------------------------------------------------------- native

    private String recoverNative(Operation op, List<String> steps, List<JournalEntry> journal) throws SQLException,
            IOException {
        if (!steps.contains("MUTATING")) return "ROLLED_BACK"; // nothing was changed
        IndexedItem item = op.itemId() == null ? null : ctx.items().byId(op.itemId()).orElse(null);
        Path p = Path.of(op.path());
        if (item == null || !Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) {
            flagItem(op, "File missing after an interrupted compression");
            return "REVIEW";
        }
        String sha = Json.str(Json.read(dataOf(journal, "HASHED")), "sha256");
        NativeFileState st = ctx.platform().fileInspector().inspect(p);
        if (!st.compressed()) {
            return "ROLLED_BACK"; // compression never took effect
        }
        String now;
        try {
            now = ctx.worker().hash(p, null, null).sha256();
        } catch (WorkerException e) {
            flagItem(op, "Cannot verify content after interrupted compression: " + e.getMessage());
            return "REVIEW";
        }
        if (sha == null || !sha.equals(now)) {
            flagItem(op, "Content could not be verified after an interrupted compression");
            return "REVIEW";
        }
        // Compressed and verified: complete the record.
        ctx.items().updateMeasured(item.id(), item.logicalSize(), st.allocatedSize(), st.allocationExact(), true,
                item.modifiedMs(), item.accessedMs());
        ctx.items().setStatus(item.id(), OptStatus.NATIVE_COMPRESSED, op.id(), null);
        if (ctx.recovery().currentForItem(item.id()).isEmpty()) {
            ctx.recovery().insert(new RecoveryRepository.Record(0, op.uuid(), item.id(), op.rootPath(), op.path(),
                    item.fileKey(), RecoveryKind.NATIVE, RecoveryStatus.ACTIVE, null, null, null, null, null, null, sha,
                    item.logicalSize(), item.modifiedMs(), null, null, null, null,
                    "Completed by crash recovery after verification"));
        }
        return "DONE";
    }

    private String recoverDecompress(Operation op) throws SQLException, IOException {
        IndexedItem item = op.itemId() == null ? null : ctx.items().byId(op.itemId()).orElse(null);
        Path p = Path.of(op.path());
        if (item == null || !Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) return "REVIEW";
        NativeFileState st = ctx.platform().fileInspector().inspect(p);
        ctx.items().updateMeasured(item.id(), item.logicalSize(), st.allocatedSize(), st.allocationExact(),
                st.compressed(), item.modifiedMs(), item.accessedMs());
        ctx.items().setStatus(item.id(), st.compressed() ? OptStatus.NATIVE_COMPRESSED : OptStatus.NONE, op.id(), null);
        return st.compressed() ? "ROLLED_BACK" : "DONE";
    }

    // ---------------------------------------------------------------- archive

    private String recoverArchive(Operation op, List<String> steps, List<JournalEntry> journal,
                                  Map<String, Object> plan) throws SQLException, IOException {
        Path original = Path.of(op.path());
        Path tmp = plan.get("tmp") == null ? null : Path.of(Json.str(plan, "tmp"));
        Path archive = plan.get("archive") == null ? null : Path.of(Json.str(plan, "archive"));
        Path retained = plan.get("retained") == null ? null : Path.of(Json.str(plan, "retained"));
        String sha = Json.str(Json.read(dataOf(journal, "HASHED")), "sha256");
        long size = Json.num(plan, "size", -1);
        boolean originalPresent = Files.isRegularFile(original, LinkOption.NOFOLLOW_LINKS);

        if (!steps.contains("ARCHIVE_PLACED")) {
            // Original untouched. Remove only our own temporary output.
            if (originalPresent) {
                OperationExecutor.deleteOwnTemp(tmp);
                return "ROLLED_BACK";
            }
            flagItem(op, "Original missing although the archive was never placed");
            return "REVIEW";
        }
        boolean archivePresent = archive != null && Files.isRegularFile(archive, LinkOption.NOFOLLOW_LINKS);
        boolean archiveValid = archivePresent && sha != null && verify(archive, plan, sha, size);
        IndexedItem item = op.itemId() == null ? null : ctx.items().byId(op.itemId()).orElse(null);
        ArchiveFormat fmt = ArchiveFormat.fromWire(Json.str(plan, "format"));

        if (originalPresent) {
            // Archive placed, original still in place: both copies exist — keep the archive as a compressed copy.
            if (archiveValid && item != null) {
                ctx.recovery().insert(record(op, item, RecoveryKind.ARCHIVE_COPY, archive, fmt, sha, size, null,
                        "Interrupted archive operation; recorded as a verified compressed copy (original untouched)"));
                ctx.items().setStatus(item.id(), OptStatus.ARCHIVE_COPY, op.id(), null);
                return "DONE";
            }
            flagItem(op, "Interrupted archive operation left an unverified archive next to the intact original");
            return "REVIEW";
        }
        // Original no longer at its path: it must be in the retention store.
        boolean retainedPresent = retained != null && Files.isRegularFile(retained, LinkOption.NOFOLLOW_LINKS);
        if (retainedPresent && archiveValid && item != null) {
            ctx.recovery().insert(record(op, item, RecoveryKind.ARCHIVE_REPLACE, archive, fmt, sha, size, retained,
                    "Completed by crash recovery after verifying the archive; original retained"));
            ctx.items().markArchivedOriginal(item.id(), op.id());
            return "DONE";
        }
        flagItem(op, "Interrupted archive replacement could not be verified automatically"
                + (retainedPresent ? "; the original is safe in " + retained : ""));
        return "REVIEW";
    }

    private RecoveryRepository.Record record(Operation op, IndexedItem item, RecoveryKind kind, Path archive,
                                             ArchiveFormat fmt, String sha, long size, Path retained, String note)
            throws IOException {
        return new RecoveryRepository.Record(0, op.uuid(), item.id(), op.rootPath(), op.path(), item.fileKey(), kind,
                RecoveryStatus.ACTIVE, archive.toString(), fmt.wire(), item.name(), Files.size(archive),
                retained == null ? null : retained.toString(), retained == null ? null : size, sha, size,
                item.modifiedMs(), ctx.manifestDir().resolve(op.uuid() + ".json").toString(), null,
                retained == null ? null : ctx.clock().instant().plus(java.time.Duration.ofDays(
                        ctx.settings().get().retentionDays())).toString(), null, note);
    }

    private boolean verify(Path archive, Map<String, Object> plan, String sha, long size) {
        try {
            String entry = Path.of(Json.str(plan, "archive")).getFileName().toString();
            ArchiveFormat fmt = ArchiveFormat.fromWire(Json.str(plan, "format"));
            entry = entry.substring(0, entry.length() - fmt.suffix().length());
            return ctx.worker().verifyArchive(archive, fmt, entry, sha, size, null, null).ok();
        } catch (WorkerException | RuntimeException e) {
            return false;
        }
    }

    // ---------------------------------------------------------------- restore / purge

    private String recoverRestore(Operation op, List<String> steps, Map<String, Object> plan) throws SQLException,
            IOException {
        Path dest = Path.of(Json.str(plan, "dest"));
        Path tmp = plan.get("tmp") == null ? null : Path.of(Json.str(plan, "tmp"));
        String sha = Json.str(plan, "sha256");
        long recId = Json.num(plan, "recoveryId", -1);
        if (!steps.contains("RESTORED_PLACED") && !steps.contains("RETAINED_MOVING")) {
            OperationExecutor.deleteOwnTemp(tmp);
            return "ROLLED_BACK";
        }
        if (Files.isRegularFile(dest, LinkOption.NOFOLLOW_LINKS)) {
            try {
                if (sha != null && sha.equals(ctx.worker().hash(dest, null, null).sha256())) {
                    if (recId > 0) {
                        ctx.recovery().setStatus(recId, RecoveryStatus.REVIEW, "Restored to " + dest
                                + " before an interruption; the archive was kept — remove it once you have checked the file");
                    }
                    return "REVIEW";
                }
            } catch (WorkerException e) {
                LOG.warn("Cannot verify restored file {}", dest, e);
            }
        }
        if (recId > 0) ctx.recovery().setStatus(recId, RecoveryStatus.REVIEW, "Restore interrupted; please review");
        return "REVIEW";
    }

    private String recoverPurge(Operation op) {
        return "ROLLED_BACK";
    }

    private void flagItem(Operation op, String message) {
        try {
            if (op.itemId() != null) ctx.items().setStatus(op.itemId(), OptStatus.RECOVERY_REQUIRED, op.id(), message);
        } catch (SQLException e) {
            LOG.error("Cannot flag item for review", e);
        }
    }
}
