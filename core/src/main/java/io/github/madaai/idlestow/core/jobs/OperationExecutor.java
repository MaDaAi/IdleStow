package io.github.madaai.idlestow.core.jobs;

import io.github.madaai.idlestow.core.db.HistoryRepository;
import io.github.madaai.idlestow.core.db.RecoveryRepository;
import io.github.madaai.idlestow.core.ipc.OptimizationWorker;
import io.github.madaai.idlestow.core.ipc.WorkerClient.ProgressListener;
import io.github.madaai.idlestow.core.ipc.WorkerException;
import io.github.madaai.idlestow.core.jobs.Authorizer.NotAuthorizedException;
import io.github.madaai.idlestow.core.model.Enums.ArchiveFormat;
import io.github.madaai.idlestow.core.model.Enums.Method;
import io.github.madaai.idlestow.core.model.Enums.OptStatus;
import io.github.madaai.idlestow.core.model.Enums.Origin;
import io.github.madaai.idlestow.core.model.Enums.RecoveryKind;
import io.github.madaai.idlestow.core.model.Enums.RecoveryStatus;
import io.github.madaai.idlestow.core.model.IndexedItem;
import io.github.madaai.idlestow.core.model.MonitoredRoot;
import io.github.madaai.idlestow.core.platform.FileKey;
import io.github.madaai.idlestow.core.platform.NativeCompression;
import io.github.madaai.idlestow.core.platform.NativeFileState;
import io.github.madaai.idlestow.core.platform.VolumeInfo;
import io.github.madaai.idlestow.core.settings.Settings;
import io.github.madaai.idlestow.core.util.CancellationToken;
import io.github.madaai.idlestow.core.util.Json;
import io.github.madaai.idlestow.core.util.PathUtil;
import io.github.madaai.idlestow.core.util.Sizes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CancellationException;

/**
 * Executes single-file operations as recoverable state machines (docs/architecture/recovery-state-machine.md).
 *
 * <p>Invariants enforced here:
 * <ul>
 *   <li>Authorization, identity (stable file key), size and modification time are re-validated immediately
 *       before every mutation.</li>
 *   <li>Every step is journaled durably before the corresponding filesystem change.</li>
 *   <li>A success is only recorded after verification of the actual result (compression state, SHA-256,
 *       archive decompression).</li>
 *   <li>The sole verified copy of user data is never deleted: originals replaced by archives are retained
 *       (moved, not deleted) until the retention period ends and the archive is re-verified.</li>
 *   <li>Outputs are created exclusively; nothing is ever silently overwritten.</li>
 * </ul>
 */
public final class OperationExecutor {
    private static final Logger LOG = LoggerFactory.getLogger(OperationExecutor.class);

    public static final String RETAINED_DIR = ".idlestow";

    private final JobContext ctx;

    public OperationExecutor(JobContext ctx) {
        this.ctx = ctx;
    }

    /** Thrown for preflight/validation failures that are safe (nothing was changed). */
    static final class SafeFailure extends Exception {
        final boolean conflict;

        SafeFailure(String message) {
            this(message, false);
        }

        SafeFailure(String message, boolean conflict) {
            super(message);
            this.conflict = conflict;
        }
    }

    // =====================================================================================================
    // Optimize
    // =====================================================================================================

    public OpOutcome optimize(long jobId, IndexedItem item, Method method, Origin origin, CancellationToken token,
                              ProgressListener progress) {
        Settings s = ctx.settings().get();
        String uuid = UUID.randomUUID().toString();
        Path path = item.path();
        Path dir = path.getParent();
        ArchiveFormat fmt = method.format();
        Map<String, Object> plan = Json.map("uuid", uuid, "itemId", item.id(), "fileKey", item.fileKey(),
                "size", item.logicalSize(), "modifiedMs", item.modifiedMs(), "origin", origin.name(),
                "mode", s.mode().name());
        if (fmt != null) {
            plan.put("archive", dir.resolve(item.name() + fmt.suffix()).toString());
            plan.put("tmp", dir.resolve("." + item.name() + ".idlestow-tmp-" + uuid.substring(0, 8) + fmt.suffix())
                    .toString());
            plan.put("format", fmt.wire());
        }
        long opId;
        try {
            MonitoredRoot root = ctx.roots().byId(item.rootId()).orElse(null);
            if (root != null && method.replacesOriginal()) {
                plan.put("retained", root.path().resolve(RETAINED_DIR).resolve("retained").resolve(uuid)
                        .resolve(item.name()).toString());
            }
            opId = ctx.jobs().createOperation(jobId, uuid, item.id(), path.toString(),
                    root == null ? null : root.path().toString(), "OPTIMIZE", method,
                    ctx.policy().version(s), Json.write(plan));
        } catch (SQLException e) {
            return OpOutcome.failed("Database error: " + e.getMessage());
        }
        try {
            OpOutcome o = method == Method.NATIVE
                    ? nativeCompress(opId, item, origin, token, progress)
                    : archive(opId, item, method, origin, plan, token, progress);
            return o;
        } catch (SafeFailure f) {
            finishQuietly(opId, f.conflict ? "FAILED" : "FAILED", f.getMessage());
            historyFailure(opId, item, "OPTIMIZE", method, origin, f.getMessage());
            return new OpOutcome(f.conflict ? OpOutcome.Result.CONFLICT : OpOutcome.Result.FAILED, f.getMessage(), 0, 0);
        } catch (CancellationException e) {
            finishQuietly(opId, "CANCELLED", "Cancelled before any change was committed");
            return new OpOutcome(OpOutcome.Result.CANCELLED, "Cancelled", 0, 0);
        }
    }

    // ------------------------------------------------------------------------------------------- native

    private OpOutcome nativeCompress(long opId, IndexedItem item, Origin origin, CancellationToken token,
                                     ProgressListener progress) throws SafeFailure {
        NativeCompression nc = ctx.platform().nativeCompression();
        Path path = item.path();
        Preflight pf = preflight(item, Method.NATIVE);
        if (pf.state.compressed()) throw new SafeFailure("The file is already compressed");
        journal(opId, "PREFLIGHT_OK", Json.write(stateMap(pf.state, pf.attrs)));

        String sha = hash(item, token, progress);
        journal(opId, "HASHED", Json.write(Json.map("sha256", sha)));
        token.checkpoint();

        Preflight again = preflight(item, Method.NATIVE); // revalidate right before mutation
        journal(opId, "MUTATING", null);
        ctx.faults().at("NATIVE_BEFORE_MUTATION");
        try {
            nc.setCompressed(path, again.key, true);
        } catch (NativeCompression.FileLockedException e) {
            throw new SafeFailure("The file is in use by another program");
        } catch (NativeCompression.FileIdentityMismatchException e) {
            throw new SafeFailure("The file was replaced during the operation (identity changed)");
        } catch (IOException e) {
            throw new SafeFailure("The operating system refused compression: " + e.getMessage());
        }
        journal(opId, "MUTATED", null);
        ctx.faults().at("NATIVE_AFTER_MUTATION");

        // Verify actual result.
        NativeFileState after;
        String sha2;
        try {
            after = ctx.platform().fileInspector().inspect(path);
            sha2 = hash(item, null, progress);
        } catch (IOException | SafeFailure e) {
            return revertNative(opId, item, again.key, "Verification failed: " + e.getMessage());
        }
        if (!after.compressed()) {
            return revertNative(opId, item, again.key, "The compression state did not change");
        }
        if (!sha.equals(sha2)) {
            return revertNative(opId, item, again.key, "Content hash changed after compression");
        }
        journal(opId, "VERIFIED", Json.write(Json.map("sha256", sha2, "allocated", after.allocatedSize())));

        long before = pf.state.allocatedSize();
        long savings = before - after.allocatedSize();
        long minUseful = Math.max(pf.volume.clusterSize() > 0 ? pf.volume.clusterSize() : 4096, 1);
        if (!after.allocationExact() || savings < minUseful) {
            // No measurable benefit: undo and remember, so the unchanged file is not retried.
            try {
                nc.setCompressed(path, again.key, false);
                NativeFileState back = ctx.platform().fileInspector().inspect(path);
                if (back.compressed()) throw new IOException("still compressed");
                String sha3 = hash(item, null, progress);
                if (!sha.equals(sha3)) throw new IOException("hash changed during revert");
                ctx.items().updateMeasured(item.id(), item.logicalSize(), back.allocatedSize(), back.allocationExact(),
                        false, item.modifiedMs(), item.accessedMs());
                ctx.items().setStatus(item.id(), OptStatus.NO_BENEFIT, opIdOrNull(opId),
                        "Native compression saved " + Sizes.format(Math.max(0, savings)) + " — not worth keeping");
                ctx.jobs().finishOperation(opId, "NO_BENEFIT", null, Json.write(stateMap(back, null)),
                        "sha256 verified", null, null);
                ctx.candidates().markDoneForItem(item.id());
                ctx.history().add(new HistoryRepository.Entry(0, opId, null, path.toString(), item.fileKey(),
                        "OPTIMIZE", Method.NATIVE.name(), origin.name(), ctx.settings().get().mode().name(), null,
                        item.logicalSize(), before, back.allocatedSize(), null, 0L, "sha256 verified", "n/a",
                        "NO_BENEFIT", null));
                return new OpOutcome(OpOutcome.Result.NO_BENEFIT, "No meaningful savings; compression reverted", 0,
                        item.logicalSize());
            } catch (IOException | SQLException | SafeFailure e) {
                return markReview(opId, item, "Could not revert a no-benefit compression: " + e.getMessage());
            }
        }

        // Commit.
        journal(opId, "COMMITTING", null);
        ctx.faults().at("NATIVE_BEFORE_COMMIT");
        try {
            ctx.items().updateMeasured(item.id(), item.logicalSize(), after.allocatedSize(), true, true,
                    item.modifiedMs(), item.accessedMs());
            ctx.items().setStatus(item.id(), OptStatus.NATIVE_COMPRESSED, opId, null);
            ctx.recovery().insert(new RecoveryRepository.Record(0, uuidOf(opId), item.id(), rootPathOf(item),
                    path.toString(), item.fileKey(), RecoveryKind.NATIVE, RecoveryStatus.ACTIVE, null, null, null,
                    null, null, null, sha, item.logicalSize(), item.modifiedMs(), null, null, null,
                    ctx.clock().instant().toString(), "Transparent compression — reversible by decompressing"));
            ctx.candidates().markDoneForItem(item.id());
            ctx.history().add(new HistoryRepository.Entry(0, opId, null, path.toString(), item.fileKey(), "OPTIMIZE",
                    Method.NATIVE.name(), origin.name(), ctx.settings().get().mode().name(), null, item.logicalSize(),
                    before, after.allocatedSize(), estimateFor(item, Method.NATIVE), savings,
                    "Compression state verified; SHA-256 unchanged", "Reversible (decompress)", "SUCCEEDED", null));
            ctx.jobs().finishOperation(opId, "DONE", Json.write(stateMap(pf.state, pf.attrs)),
                    Json.write(stateMap(after, null)), "sha256=" + sha, "NATIVE", null);
        } catch (SQLException e) {
            // Filesystem change is verified and harmless; the journal lets startup recovery finish the record.
            return new OpOutcome(OpOutcome.Result.RECOVERY_REQUIRED,
                    "Compressed and verified, but recording failed: " + e.getMessage(), savings, item.logicalSize());
        }
        return OpOutcome.ok("Compressed; saved " + Sizes.format(savings), savings, item.logicalSize());
    }

    private OpOutcome revertNative(long opId, IndexedItem item, FileKey key, String reason) {
        try {
            ctx.platform().nativeCompression().setCompressed(item.path(), key, false);
            finishQuietly(opId, "ROLLED_BACK", reason + " (compression reverted)");
            historyFailure(opId, item, "OPTIMIZE", Method.NATIVE, Origin.MANUAL, reason);
            return OpOutcome.failed(reason + "; compression reverted");
        } catch (IOException e) {
            return markReview(opId, item, reason + "; revert also failed: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------------------------------- archive

    private OpOutcome archive(long opId, IndexedItem item, Method method, Origin origin, Map<String, Object> plan,
                              CancellationToken token, ProgressListener progress) throws SafeFailure {
        Settings s = ctx.settings().get();
        ArchiveFormat fmt = method.format();
        Path path = item.path();
        Path archive = Path.of(Json.str(plan, "archive"));
        Path tmp = Path.of(Json.str(plan, "tmp"));
        boolean replace = method.replacesOriginal();
        Path retained = replace ? Path.of(Json.str(plan, "retained")) : null;

        try {
            if (!ctx.worker().supportedFormats().contains(fmt)) {
                throw new SafeFailure("The " + fmt.wire() + " format is not available in this installation");
            }
        } catch (WorkerException e) {
            throw new SafeFailure("The compression worker is unavailable: " + e.getMessage());
        }
        Preflight pf = preflight(item, method);
        if (Files.exists(archive, LinkOption.NOFOLLOW_LINKS)) {
            throw new SafeFailure("An archive already exists at " + archive + " — refusing to overwrite it", true);
        }
        if (item.logicalSize() > s.maxTempBytes()) {
            throw new SafeFailure("The temporary archive could need up to " + Sizes.format(item.logicalSize())
                    + ", more than the configured temporary space limit of " + Sizes.format(s.maxTempBytes()));
        }
        requireFreeSpace(path.getParent(), item.logicalSize(), "the temporary archive");
        if (replace) {
            if (pf.root == null) throw new SafeFailure("Authorization root unavailable");
            try {
                VolumeInfo rootVol = ctx.platform().fileInspector().volumeOf(pf.root.path());
                if (!rootVol.volumeId().equals(pf.volume.volumeId())) {
                    throw new SafeFailure("The file is on a different volume than its monitored folder;"
                            + " archive replacement is not supported there");
                }
            } catch (IOException e) {
                throw new SafeFailure("Cannot determine volume: " + e.getMessage());
            }
        }
        journal(opId, "PREFLIGHT_OK", Json.write(stateMap(pf.state, pf.attrs)));

        String sha = hash(item, token, progress);
        journal(opId, "HASHED", Json.write(Json.map("sha256", sha)));
        token.checkpoint();

        // Create the archive in a temporary file (exclusive create; worker verifies source hash while streaming).
        journal(opId, "ARCHIVE_TMP_CREATING", Json.write(Json.map("tmp", tmp.toString())));
        ctx.faults().at("ARCHIVE_BEFORE_TMP");
        long start = ctx.clock().millis();
        OptimizationWorker.ArchiveResult ar;
        try {
            ar = ctx.worker().createArchive(path, tmp, fmt, s.profile(), item.name(), sha, true,
                    Json.map("idlestow_operation", uuidOf(opId), "original_path", path.toString(),
                            "original_modified_ms", item.modifiedMs(), "method", method.name()), token, progress);
        } catch (WorkerException e) {
            deleteOwnTemp(tmp);
            if ("NO_BENEFIT".equals(e.code())) {
                noBenefit(opId, item, method, origin, "Archive would not be smaller than the original");
                return new OpOutcome(OpOutcome.Result.NO_BENEFIT, "Archive would not be smaller", 0, item.logicalSize());
            }
            if ("CANCELLED".equals(e.code())) throw new CancellationException();
            throw new SafeFailure("Archive creation failed: " + e.getMessage());
        } finally {
            recordSelfRead(item, start);
        }
        ctx.faults().at("ARCHIVE_AFTER_TMP");

        // Independent verification: full decompression compared with the original hash.
        try {
            OptimizationWorker.VerifyResult vr = ctx.worker().verifyArchive(tmp, fmt, item.name(), sha,
                    item.logicalSize(), token, progress);
            if (!vr.ok() || !sha.equals(vr.sha256()) || vr.size() != item.logicalSize()) {
                deleteOwnTemp(tmp);
                throw new SafeFailure("Archive verification failed (content mismatch)");
            }
        } catch (WorkerException e) {
            deleteOwnTemp(tmp);
            if ("CANCELLED".equals(e.code())) throw new CancellationException();
            throw new SafeFailure("Archive verification failed: " + e.getMessage());
        }
        long archiveSize = ar.archiveSize();
        journal(opId, "ARCHIVE_VERIFIED", Json.write(Json.map("sha256", sha, "archiveSize", archiveSize)));
        ctx.faults().at("ARCHIVE_AFTER_VERIFY");

        long savingsIfReplaced = Math.max(0, pf.state.allocatedSize()) - archiveSize;
        if (replace && savingsIfReplaced < Math.max(4096, s.minSavingsBytes() / 4)) {
            deleteOwnTemp(tmp);
            noBenefit(opId, item, method, origin, "Archive saves only " + Sizes.format(Math.max(0, savingsIfReplaced)));
            return new OpOutcome(OpOutcome.Result.NO_BENEFIT, "Savings too small", 0, item.logicalSize());
        }

        // Write the independent recovery manifest before any visible change.
        Path manifest = ctx.manifestDir().resolve(uuidOf(opId) + ".json");
        Map<String, Object> man = Json.map("format_version", 1, "operation", uuidOf(opId), "method", method.name(),
                "original_path", path.toString(), "archive_path", archive.toString(), "archive_format", fmt.wire(),
                "entry_name", item.name(), "sha256", sha, "size", item.logicalSize(),
                "modified_ms", item.modifiedMs(), "retained_original", retained == null ? null : retained.toString(),
                "created_utc", ctx.clock().instant().toString(),
                "how_to_recover", "The archive is a standard " + fmt.wire() + " file. Extract '" + item.name()
                        + "' with any archive tool and compare its SHA-256 with the value above.");
        try {
            writeManifest(manifest, man);
            if (retained != null) writeManifest(retained.getParent().resolve("manifest.json"), man);
        } catch (IOException e) {
            deleteOwnTemp(tmp);
            throw new SafeFailure("Cannot write recovery manifest: " + e.getMessage());
        }
        journal(opId, "MANIFEST_WRITTEN", Json.write(Json.map("manifest", manifest.toString())));

        // Revalidate the source right before the visible change.
        Preflight again;
        try {
            again = preflight(item, method);
        } catch (SafeFailure f) {
            deleteOwnTemp(tmp);
            throw f;
        }
        token.checkpoint();
        try {
            SafeMove.renameNoReplace(tmp, archive);
        } catch (FileAlreadyExistsException e) {
            deleteOwnTemp(tmp);
            throw new SafeFailure("An archive appeared at " + archive + " — refusing to overwrite it", true);
        } catch (IOException e) {
            deleteOwnTemp(tmp);
            throw new SafeFailure("Cannot place the archive: " + e.getMessage());
        }
        journal(opId, "ARCHIVE_PLACED", Json.write(Json.map("archive", archive.toString())));
        ctx.faults().at("ARCHIVE_AFTER_PLACE");

        if (!replace) {
            return commitArchive(opId, item, method, origin, sha, archive, archiveSize, null, manifest, pf);
        }

        // Replace: move (never delete) the original into the retention store on the same volume.
        try {
            Files.createDirectories(retained.getParent());
            if (!again.key.equals(currentKey(path))) throw new SafeFailure("The file changed identity");
            SafeMove.renameNoReplace(path, retained);
        } catch (SafeFailure | IOException e) {
            // Original still in place; the verified archive remains as a compressed copy.
            String msg = "Could not move the original into recovery storage: " + e.getMessage()
                    + ". The original is untouched; the verified archive was kept as a compressed copy.";
            commitArchive(opId, item, copyMethodFor(fmt), origin, sha, archive, archiveSize, null, manifest, pf);
            return OpOutcome.failed(msg);
        }
        journal(opId, "ORIGINAL_RETAINED", Json.write(Json.map("retained", retained.toString())));
        ctx.faults().at("ARCHIVE_AFTER_RETAIN");
        return commitArchive(opId, item, method, origin, sha, archive, archiveSize, retained, manifest, pf);
    }

    private static Method copyMethodFor(ArchiveFormat fmt) {
        return fmt == ArchiveFormat.TAR_ZST ? Method.TZST_COPY : Method.ZIP_COPY;
    }

    private OpOutcome commitArchive(long opId, IndexedItem item, Method method, Origin origin, String sha,
                                    Path archive, long archiveSize, Path retained, Path manifest, Preflight pf) {
        Settings s = ctx.settings().get();
        boolean replace = retained != null;
        try {
            journal(opId, "COMMITTING", null);
            String retainUntil = replace ? ctx.clock().instant().plus(Duration.ofDays(s.retentionDays())).toString() : null;
            ctx.recovery().insert(new RecoveryRepository.Record(0, uuidOf(opId), item.id(), rootPathOf(item),
                    item.path().toString(), item.fileKey(),
                    replace ? RecoveryKind.ARCHIVE_REPLACE : RecoveryKind.ARCHIVE_COPY, RecoveryStatus.ACTIVE,
                    archive.toString(), method.format().wire(), item.name(), archiveSize,
                    replace ? retained.toString() : null, replace ? item.logicalSize() : null, sha, item.logicalSize(),
                    item.modifiedMs(), manifest.toString(), null, retainUntil, ctx.clock().instant().toString(),
                    replace ? "Original retained until " + retainUntil + "; space is reclaimed after that"
                            : "Compressed copy; the original is untouched (no space reclaimed)"));
            long savingsNow;
            if (replace) {
                ctx.items().markArchivedOriginal(item.id(), opId);
                savingsNow = 0; // nothing reclaimed while the original is retained
            } else {
                ctx.items().setStatus(item.id(), OptStatus.ARCHIVE_COPY, opId, null);
                savingsNow = 0;
            }
            ctx.candidates().markDoneForItem(item.id());
            ctx.history().add(new HistoryRepository.Entry(0, opId, null, item.path().toString(), item.fileKey(),
                    "OPTIMIZE", method.name(), origin.name(), s.mode().name(), null, item.logicalSize(),
                    pf.state.allocatedSize(), archiveSize, estimateFor(item, method), savingsNow,
                    "Archive fully decompressed and SHA-256 verified",
                    replace ? "Original retained for " + s.retentionDays() + " days; restorable from archive"
                            : "Original untouched", "SUCCEEDED", null));
            ctx.jobs().finishOperation(opId, "DONE", null, Json.write(Json.map("archive", archive.toString(),
                    "archiveSize", archiveSize)), "sha256=" + sha, replace ? "ARCHIVE_REPLACE" : "ARCHIVE_COPY", null);
        } catch (SQLException | SafeFailure e) {
            return new OpOutcome(OpOutcome.Result.RECOVERY_REQUIRED,
                    "Archive verified but recording failed: " + e.getMessage(), 0, item.logicalSize());
        }
        String msg = replace
                ? "Archived and verified. The original is retained for " + s.retentionDays()
                + " days; " + Sizes.format(Math.max(0, pf.state.allocatedSize() - archiveSize))
                + " will be reclaimed after retention ends."
                : "Compressed copy created and verified (" + Sizes.format(archiveSize) + "); the original is unchanged.";
        return OpOutcome.ok(msg, 0, item.logicalSize());
    }

    private void noBenefit(long opId, IndexedItem item, Method method, Origin origin, String why) {
        try {
            ctx.items().setStatus(item.id(), OptStatus.NO_BENEFIT, opId, why);
            ctx.candidates().markDoneForItem(item.id());
            ctx.jobs().finishOperation(opId, "NO_BENEFIT", null, null, null, null, why);
            ctx.history().add(new HistoryRepository.Entry(0, opId, null, item.path().toString(), item.fileKey(),
                    "OPTIMIZE", method.name(), origin.name(), ctx.settings().get().mode().name(), null,
                    item.logicalSize(), null, null, null, 0L, null, "n/a", "NO_BENEFIT", why));
        } catch (SQLException e) {
            LOG.warn("Cannot record no-benefit outcome", e);
        }
    }

    // =====================================================================================================
    // Restore / decompress / purge / delete copy / verify
    // =====================================================================================================

    /** Reverses native compression for one file. */
    public OpOutcome decompress(long jobId, IndexedItem item, Origin origin, CancellationToken token,
                                ProgressListener progress) {
        String uuid = UUID.randomUUID().toString();
        long opId;
        try {
            opId = ctx.jobs().createOperation(jobId, uuid, item.id(), item.path().toString(), rootPathOf(item),
                    "DECOMPRESS", Method.NATIVE, ctx.policy().version(ctx.settings().get()),
                    Json.write(Json.map("uuid", uuid, "itemId", item.id())));
        } catch (SQLException e) {
            return OpOutcome.failed("Database error: " + e.getMessage());
        }
        try {
            NativeFileState st = identity(item);
            if (!st.compressed()) throw new SafeFailure("The file is not compressed");
            verifyNoLinksForItem(item);
            long logicalRounded = Sizes.roundUp(item.logicalSize(), clusterOf(item));
            requireFreeSpace(item.path().getParent(), Math.max(0, logicalRounded - st.allocatedSize()), "decompression");
            journal(opId, "PREFLIGHT_OK", Json.write(stateMap(st, null)));
            String sha = hash(item, token, progress);
            journal(opId, "MUTATING", Json.write(Json.map("sha256", sha)));
            try {
                ctx.platform().nativeCompression().setCompressed(item.path(), FileKey.parse(item.fileKey()), false);
            } catch (NativeCompression.FileLockedException e) {
                throw new SafeFailure("The file is in use by another program");
            } catch (IOException e) {
                throw new SafeFailure("Decompression failed: " + e.getMessage());
            }
            NativeFileState after = ctx.platform().fileInspector().inspect(item.path());
            String sha2 = hash(item, null, progress);
            if (after.compressed() || !sha.equals(sha2)) {
                return markReview(opId, item, "Decompression could not be verified");
            }
            ctx.items().updateMeasured(item.id(), item.logicalSize(), after.allocatedSize(), after.allocationExact(),
                    false, item.modifiedMs(), item.accessedMs());
            ctx.items().setStatus(item.id(), OptStatus.NONE, opId, null);
            ctx.recovery().currentForItem(item.id()).ifPresent(r -> {
                try {
                    if (r.kind() == RecoveryKind.NATIVE) ctx.recovery().setStatus(r.id(), RecoveryStatus.RESTORED, "Decompressed");
                } catch (SQLException e) {
                    LOG.warn("Cannot update recovery record", e);
                }
            });
            long freed = st.allocatedSize() - after.allocatedSize();
            ctx.history().add(new HistoryRepository.Entry(0, opId, null, item.path().toString(), item.fileKey(),
                    "DECOMPRESS", Method.NATIVE.name(), origin.name(), ctx.settings().get().mode().name(), null,
                    item.logicalSize(), st.allocatedSize(), after.allocatedSize(), null, -freed,
                    "SHA-256 unchanged", "n/a", "RESTORED", null));
            ctx.jobs().finishOperation(opId, "DONE", null, Json.write(stateMap(after, null)), "sha256=" + sha, null, null);
            return new OpOutcome(OpOutcome.Result.SUCCEEDED, "Decompressed and verified", 0, item.logicalSize());
        } catch (SafeFailure f) {
            finishQuietly(opId, "FAILED", f.getMessage());
            return OpOutcome.failed(f.getMessage());
        } catch (CancellationException e) {
            finishQuietly(opId, "CANCELLED", "Cancelled");
            return new OpOutcome(OpOutcome.Result.CANCELLED, "Cancelled", 0, 0);
        } catch (IOException | SQLException e) {
            finishQuietly(opId, "FAILED", e.getMessage());
            return OpOutcome.failed(e.getMessage());
        }
    }

    /**
     * Restores an archived file. {@code destination} null means the original path. An existing file at the
     * destination is never overwritten: the outcome is CONFLICT and the user can choose another location/name.
     */
    public OpOutcome restore(long jobId, RecoveryRepository.Record rec, Path destination, CancellationToken token,
                             ProgressListener progress) {
        String uuid = UUID.randomUUID().toString();
        boolean inPlace = destination == null;
        Path dest = inPlace ? Path.of(rec.originalPath()) : PathUtil.normalize(destination);
        Path tmp = dest.getParent().resolve("." + dest.getFileName() + ".idlestow-tmp-" + uuid.substring(0, 8) + ".restore");
        long opId;
        try {
            opId = ctx.jobs().createOperation(jobId, uuid, rec.itemId(), dest.toString(), rec.rootPath(), "RESTORE",
                    null, ctx.policy().version(ctx.settings().get()), Json.write(Json.map("uuid", uuid,
                            "recoveryId", rec.id(), "dest", dest.toString(), "tmp", tmp.toString(),
                            "sha256", rec.sha256(), "inPlace", inPlace)));
        } catch (SQLException e) {
            return OpOutcome.failed("Database error: " + e.getMessage());
        }
        try {
            if (rec.kind() == RecoveryKind.NATIVE) throw new SafeFailure("Use decompress for native compression");
            if (rec.status() == RecoveryStatus.RESTORED || rec.status() == RecoveryStatus.REMOVED) {
                throw new SafeFailure("This item was already restored or removed");
            }
            if (rec.kind() == RecoveryKind.ARCHIVE_COPY && inPlace) {
                throw new SafeFailure("The original still exists; choose another folder to extract the copy to", true);
            }
            if (inPlace) {
                // In-place restore writes inside the monitored folder → it must still be authorized.
                if (rec.itemId() == null) throw new SafeFailure("Original location is unknown");
                IndexedItem it = ctx.items().byId(rec.itemId()).orElseThrow(() -> new SafeFailure(
                        "The original location is no longer indexed; restore to another folder"));
                try {
                    ctx.authorizer().authorize(it.rootId(), dest, rec.size() == null ? 0 : rec.size(), it.category(),
                            it.volumeId());
                } catch (NotAuthorizedException e) {
                    throw new SafeFailure("Cannot restore in place: " + e.getMessage()
                            + ". You can restore to another folder instead.");
                }
            }
            if (!Files.isDirectory(dest.getParent())) throw new SafeFailure("Destination folder does not exist: "
                    + dest.getParent());
            if (Files.exists(dest, LinkOption.NOFOLLOW_LINKS)) {
                throw new SafeFailure("A file already exists at " + dest
                        + ". It will not be overwritten — choose another location or name.", true);
            }
            Path archive = rec.archivePath() == null ? null : Path.of(rec.archivePath());
            Path retained = rec.retainedOriginalPath() == null ? null : Path.of(rec.retainedOriginalPath());
            long size = rec.size() == null ? 0 : rec.size();
            requireFreeSpace(dest.getParent(), size, "the restored file");
            journal(opId, "PREFLIGHT_OK", null);

            validateRecordPaths(rec);
            boolean usedRetained = false;
            boolean retainedCopied = false;
            boolean retainedMismatch = false;
            if (retained != null && Files.isRegularFile(retained, LinkOption.NOFOLLOW_LINKS)) {
                OptimizationWorker.HashResult h = Files.size(retained) == size
                        ? ctx.worker().hash(retained, token, progress) : null;
                if (h != null && h.sha256().equals(rec.sha256())) {
                    journal(opId, "RETAINED_MOVING", null);
                    ctx.faults().at("RESTORE_BEFORE_MOVE");
                    try {
                        SafeMove.renameNoReplace(retained, dest);
                    } catch (SafeMove.CrossVolumeException e) {
                        // other volume: copy to a temp sibling of the destination, then rename without replacing
                        Files.copy(retained, tmp, StandardCopyOption.COPY_ATTRIBUTES);
                        try (FileChannel ch = FileChannel.open(tmp, StandardOpenOption.WRITE)) {
                            ch.force(true);
                        }
                        placeNoReplace(tmp, dest);
                        retainedCopied = true;
                    } catch (FileAlreadyExistsException e) {
                        throw new SafeFailure("A file appeared at " + dest + " and was not overwritten", true);
                    }
                    usedRetained = true;
                } else {
                    // The retained file is not the recorded original (e.g. edited by the user): never delete it.
                    retainedMismatch = true;
                }
            }
            if (!usedRetained) {
                if (archive == null || !Files.isRegularFile(archive, LinkOption.NOFOLLOW_LINKS)) {
                    markRecord(rec, RecoveryStatus.MISSING, "Archive missing");
                    throw new SafeFailure("The archive is missing and no verified retained copy exists."
                            + " IdleStow cannot recover this file without a valid backup.");
                }
                journal(opId, "RESTORE_TMP_CREATING", null);
                ctx.faults().at("RESTORE_BEFORE_EXTRACT");
                try {
                    ctx.worker().extractArchive(archive, ArchiveFormat.fromWire(rec.archiveFormat()), rec.entryName(),
                            tmp, rec.sha256(), size, token, progress);
                } catch (WorkerException e) {
                    deleteOwnTemp(tmp);
                    if ("CANCELLED".equals(e.code())) throw new CancellationException();
                    if ("INTEGRITY_FAILURE".equals(e.code()) || "LIMIT_EXCEEDED".equals(e.code())
                            || "UNSAFE_PATH".equals(e.code())) {
                        markRecord(rec, RecoveryStatus.DAMAGED, "Integrity verification failed: " + e.getMessage());
                        throw new SafeFailure("Integrity verification failed: the archive is damaged. IdleStow cannot"
                                + " recover this file without a valid backup.");
                    }
                    throw new SafeFailure("Extraction failed: " + e.getMessage());
                }
                if (rec.modifiedMs() != null && rec.modifiedMs() > 0) {
                    Files.setLastModifiedTime(tmp, FileTime.fromMillis(rec.modifiedMs()));
                }
                placeNoReplace(tmp, dest);
            }
            journal(opId, "RESTORED_PLACED", Json.write(Json.map("dest", dest.toString(), "fromRetained", usedRetained)));
            ctx.faults().at("RESTORE_AFTER_PLACE");

            OptimizationWorker.HashResult check = ctx.worker().hash(dest, token, progress);
            if (!check.sha256().equals(rec.sha256())) {
                return markReviewRecord(opId, rec, "Restored file does not match the recorded hash");
            }
            journal(opId, "VERIFIED", null);

            // Clean up IdleStow-created artifacts only after the restored file is verified.
            if (archive != null && rec.kind() == RecoveryKind.ARCHIVE_REPLACE) Files.deleteIfExists(archive);
            if (retained != null && retainedCopied) Files.deleteIfExists(retained); // verified identical copy
            if (retained != null && !retainedMismatch) {
                Files.deleteIfExists(retained.getParent().resolve("manifest.json"));
                deleteEmptyRetentionDir(retained.getParent());
            }

            ctx.recovery().setStatus(rec.id(), RecoveryStatus.RESTORED,
                    "Restored to " + dest + (usedRetained ? " from the retained original" : " from the archive")
                            + (retainedMismatch ? ". The retained file at " + retained + " differs from the recorded"
                            + " original (it may have been edited) and was kept untouched." : ""));
            if (rec.itemId() != null && rec.kind() == RecoveryKind.ARCHIVE_REPLACE) {
                if (inPlace) ctx.items().markRestored(rec.itemId());
                else ctx.items().markRemoved(Path.of(rec.originalPath()));
            }
            ctx.history().add(new HistoryRepository.Entry(0, opId, null, dest.toString(), rec.fileKey(), "RESTORE",
                    rec.archiveFormat(), Origin.MANUAL.name(), ctx.settings().get().mode().name(), null, size, null, null,
                    null, null, "SHA-256 of restored file verified", "n/a", "RESTORED", null));
            ctx.jobs().finishOperation(opId, "DONE", null, Json.write(Json.map("dest", dest.toString())),
                    "sha256=" + rec.sha256(), null, null);
            return OpOutcome.ok("Restored and verified at " + dest, 0, size);
        } catch (SafeFailure f) {
            finishQuietly(opId, "FAILED", f.getMessage());
            return new OpOutcome(f.conflict ? OpOutcome.Result.CONFLICT : OpOutcome.Result.FAILED, f.getMessage(), 0, 0);
        } catch (CancellationException e) {
            deleteOwnTemp(tmp);
            finishQuietly(opId, "CANCELLED", "Cancelled");
            return new OpOutcome(OpOutcome.Result.CANCELLED, "Cancelled", 0, 0);
        } catch (IOException | SQLException e) {
            deleteOwnTemp(tmp);
            finishQuietly(opId, "FAILED", e.getMessage());
            return OpOutcome.failed(e.getMessage());
        }
    }

    /**
     * Removes the retained original of an archive replacement after re-verifying the archive.
     * This is the step that actually reclaims space.
     */
    public OpOutcome purgeRetained(long jobId, RecoveryRepository.Record rec, CancellationToken token,
                                   ProgressListener progress) {
        String uuid = UUID.randomUUID().toString();
        long opId;
        try {
            opId = ctx.jobs().createOperation(jobId, uuid, rec.itemId(), rec.originalPath(), rec.rootPath(),
                    "PURGE_RETAINED", null, ctx.policy().version(ctx.settings().get()),
                    Json.write(Json.map("uuid", uuid, "recoveryId", rec.id())));
        } catch (SQLException e) {
            return OpOutcome.failed("Database error: " + e.getMessage());
        }
        try {
            if (rec.kind() != RecoveryKind.ARCHIVE_REPLACE || rec.retainedOriginalPath() == null) {
                throw new SafeFailure("Nothing is retained for this item");
            }
            validateRecordPaths(rec);
            requireActiveRootFor(rec);
            Path archive = Path.of(rec.archivePath());
            Path retained = Path.of(rec.retainedOriginalPath());
            if (!Files.isRegularFile(archive, LinkOption.NOFOLLOW_LINKS)) {
                markRecord(rec, RecoveryStatus.MISSING, "Archive missing; retained original kept");
                throw new SafeFailure("The archive is missing — the retained original is the only copy and is kept");
            }
            OptimizationWorker.VerifyResult vr;
            try {
                vr = ctx.worker().verifyArchive(archive, ArchiveFormat.fromWire(rec.archiveFormat()), rec.entryName(),
                        rec.sha256(), rec.size(), token, progress);
            } catch (WorkerException e) {
                if ("CANCELLED".equals(e.code())) throw new CancellationException();
                markRecord(rec, RecoveryStatus.DAMAGED, "Archive failed verification; retained original kept");
                throw new SafeFailure("The archive failed verification — the retained original is kept: " + e.getMessage());
            }
            if (!vr.ok() || !rec.sha256().equals(vr.sha256())) {
                markRecord(rec, RecoveryStatus.DAMAGED, "Archive failed verification; retained original kept");
                throw new SafeFailure("The archive failed verification — the retained original is kept");
            }
            ctx.recovery().verified(rec.id());
            journal(opId, "ARCHIVE_VERIFIED", null);
            long freed = Files.exists(retained, LinkOption.NOFOLLOW_LINKS) ? Files.size(retained) : 0;
            ctx.faults().at("PURGE_BEFORE_DELETE");
            Files.deleteIfExists(retained);
            Path manifestCopy = retained.getParent().resolve("manifest.json");
            Files.deleteIfExists(manifestCopy);
            deleteEmptyRetentionDir(retained.getParent());
            ctx.recovery().retainedPurged(rec.id());
            ctx.history().add(new HistoryRepository.Entry(0, opId, null, rec.originalPath(), rec.fileKey(),
                    "RECLAIM", rec.archiveFormat(), Origin.MANUAL.name(), ctx.settings().get().mode().name(), null,
                    rec.size(), freed + (rec.archiveSize() == null ? 0 : rec.archiveSize()), rec.archiveSize(), null,
                    Math.max(0, freed - (rec.archiveSize() == null ? 0 : rec.archiveSize())),
                    "Archive re-verified before removing the retained original",
                    "Recoverable from the verified archive only (no independent backup)", "SUCCEEDED", null));
            ctx.jobs().finishOperation(opId, "DONE", null, null, "archive verified", null, null);
            return OpOutcome.ok("Retained original removed; " + Sizes.format(freed) + " freed", freed, freed);
        } catch (SafeFailure f) {
            finishQuietly(opId, "FAILED", f.getMessage());
            return OpOutcome.failed(f.getMessage());
        } catch (CancellationException e) {
            finishQuietly(opId, "CANCELLED", "Cancelled");
            return new OpOutcome(OpOutcome.Result.CANCELLED, "Cancelled", 0, 0);
        } catch (IOException | SQLException e) {
            finishQuietly(opId, "FAILED", e.getMessage());
            return OpOutcome.failed(e.getMessage());
        }
    }

    /** Deletes an IdleStow-created compressed copy (the original is untouched). */
    public OpOutcome deleteCopy(long jobId, RecoveryRepository.Record rec) {
        try {
            if (rec.kind() != RecoveryKind.ARCHIVE_COPY) return OpOutcome.failed("Not a compressed copy");
            Path original = Path.of(rec.originalPath());
            if (!Files.isRegularFile(original, LinkOption.NOFOLLOW_LINKS)) {
                return OpOutcome.failed("The original file is missing; the compressed copy is kept because it may be"
                        + " the only copy");
            }
            try {
                validateRecordPaths(rec);
            } catch (SafeFailure f) {
                return OpOutcome.failed(f.getMessage());
            }
            // The copy may be the only record of an earlier version: delete it only while the original is unchanged.
            boolean unchanged = rec.size() != null && Files.size(original) == rec.size()
                    && (rec.modifiedMs() == null || Files.getLastModifiedTime(original).toMillis() == rec.modifiedMs());
            if (!unchanged) {
                return OpOutcome.failed("The original changed since the compressed copy was made; the copy is kept"
                        + " because it is the only copy of the earlier version");
            }
            if (rec.archivePath() != null) Files.deleteIfExists(Path.of(rec.archivePath()));
            ctx.recovery().setStatus(rec.id(), RecoveryStatus.REMOVED, "Compressed copy deleted by user");
            if (rec.itemId() != null) ctx.items().setStatus(rec.itemId(), OptStatus.NONE, null, null);
            return OpOutcome.ok("Compressed copy deleted", 0, 0);
        } catch (IOException | SQLException e) {
            return OpOutcome.failed(e.getMessage());
        }
    }

    /** Verifies archive integrity (full decompression) and updates the record. */
    public OpOutcome verifyRecord(RecoveryRepository.Record rec, CancellationToken token, ProgressListener progress) {
        try {
            if (rec.kind() == RecoveryKind.NATIVE) {
                IndexedItem it = rec.itemId() == null ? null : ctx.items().byId(rec.itemId()).orElse(null);
                if (it == null || !Files.isRegularFile(it.path())) return OpOutcome.failed("File not found");
                OptimizationWorker.HashResult h = ctx.worker().hash(it.path(), token, progress);
                boolean compressed = ctx.platform().nativeCompression().isCompressed(it.path());
                return OpOutcome.ok("File readable; SHA-256 " + h.sha256().substring(0, 16) + "…; "
                        + (compressed ? "compressed" : "not compressed"), 0, h.size());
            }
            Path archive = Path.of(rec.archivePath());
            if (!Files.isRegularFile(archive, LinkOption.NOFOLLOW_LINKS)) {
                markRecord(rec, RecoveryStatus.MISSING, "Archive missing");
                return OpOutcome.failed("Archive missing");
            }
            OptimizationWorker.VerifyResult vr = ctx.worker().verifyArchive(archive,
                    ArchiveFormat.fromWire(rec.archiveFormat()), rec.entryName(), rec.sha256(), rec.size(), token,
                    progress);
            if (vr.ok() && rec.sha256().equals(vr.sha256())) {
                ctx.recovery().verified(rec.id());
                if (rec.status() == RecoveryStatus.DAMAGED || rec.status() == RecoveryStatus.MISSING) {
                    ctx.recovery().setStatus(rec.id(), RecoveryStatus.ACTIVE, "Re-verified");
                }
                return OpOutcome.ok("Archive verified: content matches the original SHA-256", 0, rec.size());
            }
            markRecord(rec, RecoveryStatus.DAMAGED, "Verification failed");
            return OpOutcome.failed("Verification failed");
        } catch (WorkerException e) {
            if (!"CANCELLED".equals(e.code()) && !e.retryable()) {
                markRecord(rec, RecoveryStatus.DAMAGED, "Verification failed: " + e.getMessage());
            }
            return OpOutcome.failed(e.getMessage());
        } catch (IOException | SQLException e) {
            return OpOutcome.failed(e.getMessage());
        }
    }

    // =====================================================================================================
    // Helpers
    // =====================================================================================================

    private record Preflight(MonitoredRoot root, NativeFileState state, VolumeInfo volume, FileKey key,
                             BasicFileAttributes attrs) { }

    /** Full preflight for a mutation: authorization, links, identity, unchanged content, eligibility, locks. */
    private Preflight preflight(IndexedItem item, Method method) throws SafeFailure {
        Path path = item.path();
        MonitoredRoot root;
        try {
            root = ctx.authorizer().authorize(item.rootId(), path, item.logicalSize(), item.category(), item.volumeId());
        } catch (NotAuthorizedException e) {
            throw new SafeFailure("Not authorized: " + e.getMessage());
        }
        BasicFileAttributes a;
        NativeFileState st;
        VolumeInfo vol;
        try {
            a = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            st = ctx.platform().fileInspector().inspect(path);
            vol = ctx.platform().fileInspector().volumeOf(path);
        } catch (IOException e) {
            throw new SafeFailure("Cannot read the file: " + e.getMessage());
        }
        if (!a.isRegularFile()) throw new SafeFailure("Not a regular file");
        if (st.reparsePoint() || st.cloudPlaceholder() || st.offline()) {
            throw new SafeFailure("Cloud placeholder, offline or reparse-point files are never modified");
        }
        if (st.fileKey() == null || item.fileKey() == null) throw new SafeFailure("File identity cannot be verified");
        if (!st.fileKey().asString().equals(item.fileKey())) {
            throw new SafeFailure("The file was replaced since it was indexed (identity changed); rescan first");
        }
        if (a.size() != item.logicalSize() || a.lastModifiedTime().toMillis() != item.modifiedMs()) {
            throw new SafeFailure("The file changed since it was indexed; it was left untouched");
        }
        if (st.encrypted()) throw new SafeFailure("Encrypted files are not optimized");
        if (st.sparse()) throw new SafeFailure("Sparse files are not optimized");
        if (vol.readOnly()) throw new SafeFailure("The volume is read-only");
        if (method == Method.NATIVE) {
            if (!ctx.platform().nativeCompression().implemented() || !ctx.platform().nativeCompression().supportedOn(vol)) {
                throw new SafeFailure("Native compression is not supported on this volume: "
                        + vol.nativeCompressionNote());
            }
        }
        if (method.replacesOriginal()) {
            if (st.linkCount() != 1) throw new SafeFailure("Files with multiple hard links cannot be replaced by an archive");
            if (st.alternateStreams() != 0) {
                throw new SafeFailure(st.alternateStreams() < 0
                        ? "Alternate data streams cannot be checked on this platform; replacement refused"
                        : "The file has alternate data streams that an archive cannot preserve");
            }
            if (st.system() || item.has(IndexedItem.ATTR_SYSTEM)) throw new SafeFailure("System files are not replaced");
            if (ctx.policy().protectedRules().inCloudSyncFolder(path)) {
                throw new SafeFailure("Files in cloud-synchronized folders are never replaced by archives");
            }
        }
        Boolean locked = ctx.platform().fileInspector().isLockedByOtherProcess(path);
        if (Boolean.TRUE.equals(locked)) throw new SafeFailure("The file is open in another program");
        return new Preflight(root, st, vol, st.fileKey(), a);
    }

    private NativeFileState identity(IndexedItem item) throws SafeFailure {
        try {
            ctx.authorizer().authorize(item.rootId(), item.path(), item.logicalSize(), item.category(), item.volumeId());
        } catch (NotAuthorizedException e) {
            // Reverting an optimization is allowed even for pinned/excluded files, but never outside authorization.
            if (!e.getMessage().startsWith("Pinned") && !e.getMessage().startsWith("Inside pinned")
                    && !e.getMessage().startsWith("Excluded")) {
                throw new SafeFailure("Not authorized: " + e.getMessage());
            }
        }
        try {
            NativeFileState st = ctx.platform().fileInspector().inspect(item.path());
            if (st.fileKey() == null || !st.fileKey().asString().equals(item.fileKey())) {
                throw new SafeFailure("The file was replaced since it was indexed; rescan first");
            }
            return st;
        } catch (IOException e) {
            throw new SafeFailure("Cannot read the file: " + e.getMessage());
        }
    }

    private void verifyNoLinksForItem(IndexedItem item) throws SafeFailure {
        try {
            MonitoredRoot r = ctx.roots().byId(item.rootId()).orElseThrow(() -> new SafeFailure("Root missing"));
            Authorizer.verifyNoLinks(PathUtil.normalize(r.path()), PathUtil.normalize(item.path()));
        } catch (NotAuthorizedException e) {
            throw new SafeFailure(e.getMessage());
        } catch (SQLException e) {
            throw new SafeFailure("Database error: " + e.getMessage());
        }
    }

    private FileKey currentKey(Path p) throws IOException {
        NativeFileState s = ctx.platform().fileInspector().inspect(p);
        if (s.fileKey() == null) throw new IOException("identity unavailable");
        return s.fileKey();
    }

    private String hash(IndexedItem item, CancellationToken token, ProgressListener progress) throws SafeFailure {
        long start = ctx.clock().millis();
        try {
            OptimizationWorker.HashResult h = ctx.worker().hash(item.path(), token, progress);
            if (h.size() != item.logicalSize()) throw new SafeFailure("The file size changed while reading it");
            return h.sha256();
        } catch (WorkerException e) {
            if ("CANCELLED".equals(e.code())) throw new CancellationException();
            throw new SafeFailure("Cannot hash the file: " + e.getMessage());
        } finally {
            recordSelfRead(item, start);
        }
    }

    private void recordSelfRead(IndexedItem item, long startMs) {
        try {
            ctx.items().recordSelfRead(item.id(), startMs, ctx.clock().millis());
        } catch (SQLException e) {
            LOG.debug("Cannot record self read", e);
        }
    }

    private void requireFreeSpace(Path dir, long needed, String what) throws SafeFailure {
        try {
            long usable = Files.getFileStore(dir).getUsableSpace();
            long reserve = ctx.settings().get().minFreeReserveBytes();
            if (usable - needed < reserve) {
                throw new SafeFailure("Insufficient free space for " + what + ": " + Sizes.format(usable)
                        + " available, " + Sizes.format(needed) + " needed plus the " + Sizes.format(reserve)
                        + " safety reserve");
            }
        } catch (IOException e) {
            throw new SafeFailure("Cannot determine free space: " + e.getMessage());
        }
    }

    /** Destructive recovery-store operations require the folder to still be authorized. */
    private void requireActiveRootFor(RecoveryRepository.Record rec) throws SafeFailure {
        try {
            if (rec.rootPath() == null) throw new SafeFailure("The monitored folder of this record is unknown");
            boolean active = ctx.roots().active().stream()
                    .anyMatch(r -> PathUtil.key(r.path()).equals(PathUtil.key(Path.of(rec.rootPath()))));
            if (!active) {
                throw new SafeFailure("Authorization for " + rec.rootPath() + " was revoked; nothing is deleted there");
            }
        } catch (SQLException e) {
            throw new SafeFailure("Cannot verify authorization: " + e.getMessage());
        }
    }

    /** Renames an IdleStow temp file into place; never replaces an existing file (the temp is removed on failure). */
    private static void placeNoReplace(Path tmp, Path dest) throws IOException, SafeFailure {
        try {
            SafeMove.renameNoReplace(tmp, dest);
        } catch (FileAlreadyExistsException e) {
            deleteOwnTemp(tmp);
            throw new SafeFailure("A file appeared at " + dest + " and was not overwritten", true);
        } catch (IOException e) {
            deleteOwnTemp(tmp);
            throw e;
        }
    }

    /**
     * Refuses to act on recovery records whose paths are not the ones IdleStow itself creates
     * (protects against damaged or tampered records pointing deletions at arbitrary files).
     */
    static void validateRecordPaths(RecoveryRepository.Record rec) throws SafeFailure {
        Path original = PathUtil.normalize(Path.of(rec.originalPath()));
        if (rec.archivePath() != null) {
            Path a = PathUtil.normalize(Path.of(rec.archivePath()));
            String an = a.getFileName().toString();
            boolean nameOk = an.equals(original.getFileName() + ".idlestow.zip")
                    || an.equals(original.getFileName() + ".idlestow.tar.zst");
            if (!nameOk || a.getParent() == null || !PathUtil.key(a.getParent()).equals(PathUtil.key(original.getParent()))
                    || PathUtil.key(a).equals(PathUtil.key(original))) {
                throw new SafeFailure("The recovery record's archive path is not an IdleStow archive location; refusing");
            }
        }
        if (rec.retainedOriginalPath() != null) {
            Path r = PathUtil.normalize(Path.of(rec.retainedOriginalPath()));
            boolean ok = rec.rootPath() != null
                    && PathUtil.isWithin(r, Path.of(rec.rootPath()).resolve(RETAINED_DIR).resolve("retained"))
                    && r.getFileName().toString().equals(original.getFileName().toString());
            if (!ok) throw new SafeFailure("The recovery record's retained path is not in IdleStow's retention store; refusing");
        }
    }

    /** Deletes a temporary file only if its name proves it was created by IdleStow. */
    static void deleteOwnTemp(Path tmp) {
        if (tmp == null || tmp.getFileName() == null || !tmp.getFileName().toString().contains(".idlestow-tmp-")) return;
        try {
            Files.deleteIfExists(tmp);
        } catch (IOException e) {
            LOG.warn("Could not delete temporary file {}", tmp, e);
        }
    }

    private static void deleteEmptyRetentionDir(Path dir) {
        if (dir == null || !dir.toString().contains(RETAINED_DIR)) return;
        try (var s = Files.list(dir)) {
            if (s.findAny().isEmpty()) Files.delete(dir);
        } catch (IOException ignored) {
            // leave it
        }
    }

    static void writeManifest(Path file, Map<String, Object> manifest) throws IOException {
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, Json.pretty(manifest), StandardCharsets.UTF_8);
        try (FileChannel ch = FileChannel.open(tmp, StandardOpenOption.WRITE)) {
            ch.force(true);
        }
        Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    private static Map<String, Object> stateMap(NativeFileState s, BasicFileAttributes a) {
        Map<String, Object> m = Json.map("fileKey", s.fileKey() == null ? null : s.fileKey().asString(),
                "allocated", s.allocatedSize(), "compressed", s.compressed(), "links", s.linkCount(),
                "streams", s.alternateStreams());
        if (a != null) {
            m.put("size", a.size());
            m.put("modifiedMs", a.lastModifiedTime().toMillis());
        }
        return m;
    }

    private long clusterOf(IndexedItem item) {
        try {
            return ctx.roots().volume(item.volumeId()).map(VolumeInfo::clusterSize).orElse(4096L);
        } catch (SQLException e) {
            return 4096;
        }
    }

    private Long estimateFor(IndexedItem item, Method method) {
        IndexedItem.Estimate e = item.estimate();
        if (e == null || !e.validFor(item.logicalSize(), item.modifiedMs())) return null;
        return switch (method) {
            case NATIVE -> e.ntfsSavings();
            case ZIP_COPY, ZIP_REPLACE -> e.zipSavings();
            case TZST_COPY, TZST_REPLACE -> e.zstdSavings();
        };
    }

    private void journal(long opId, String step, String data) throws SafeFailure {
        try {
            ctx.jobs().journal(opId, step, data);
        } catch (SQLException e) {
            throw new SafeFailure("Cannot write the operation journal: " + e.getMessage());
        }
    }

    private void finishQuietly(long opId, String state, String error) {
        try {
            ctx.jobs().finishOperation(opId, state, null, null, null, null, error);
        } catch (SQLException e) {
            LOG.warn("Cannot finish operation {}", opId, e);
        }
    }

    private void historyFailure(long opId, IndexedItem item, String action, Method method, Origin origin, String msg) {
        try {
            ctx.history().add(new HistoryRepository.Entry(0, opId, null, item.path().toString(), item.fileKey(), action,
                    method == null ? null : method.name(), origin.name(), ctx.settings().get().mode().name(), null,
                    item.logicalSize(), null, null, null, null, null, "Original unchanged", "FAILED", msg));
        } catch (SQLException e) {
            LOG.warn("Cannot record history", e);
        }
    }

    private OpOutcome markReview(long opId, IndexedItem item, String msg) {
        try {
            ctx.items().setStatus(item.id(), OptStatus.RECOVERY_REQUIRED, opId, msg);
        } catch (SQLException e) {
            LOG.error("Cannot flag recovery-required item {}", item.path(), e);
        }
        finishQuietly(opId, "REVIEW", msg);
        return new OpOutcome(OpOutcome.Result.RECOVERY_REQUIRED, msg, 0, 0);
    }

    private OpOutcome markReviewRecord(long opId, RecoveryRepository.Record rec, String msg) {
        markRecord(rec, RecoveryStatus.REVIEW, msg);
        finishQuietly(opId, "REVIEW", msg);
        return new OpOutcome(OpOutcome.Result.RECOVERY_REQUIRED, msg, 0, 0);
    }

    private void markRecord(RecoveryRepository.Record rec, RecoveryStatus st, String note) {
        try {
            ctx.recovery().setStatus(rec.id(), st, note);
        } catch (SQLException e) {
            LOG.error("Cannot update recovery record {}", rec.id(), e);
        }
    }

    private String uuidOf(long opId) {
        try {
            return ctx.jobs().operation(opId).map(o -> o.uuid()).orElse("op-" + opId);
        } catch (SQLException e) {
            return "op-" + opId;
        }
    }

    private static Long opIdOrNull(long opId) {
        return opId > 0 ? opId : null;
    }

    private String rootPathOf(IndexedItem item) {
        try {
            return ctx.roots().byId(item.rootId()).map(r -> r.path().toString()).orElse(null);
        } catch (SQLException e) {
            return null;
        }
    }
}
