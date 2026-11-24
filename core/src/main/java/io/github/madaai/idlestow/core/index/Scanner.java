package io.github.madaai.idlestow.core.index;

import io.github.madaai.idlestow.core.db.ItemRepository;
import io.github.madaai.idlestow.core.db.RootRepository;
import io.github.madaai.idlestow.core.model.MonitoredRoot;
import io.github.madaai.idlestow.core.platform.FileInspector;
import io.github.madaai.idlestow.core.platform.NativeFileState;
import io.github.madaai.idlestow.core.platform.VolumeInfo;
import io.github.madaai.idlestow.core.util.CancellationToken;
import io.github.madaai.idlestow.core.util.PathUtil;
import io.github.madaai.idlestow.core.util.Sizes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.DosFileAttributes;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Metadata-only filesystem indexer.
 *
 * <p>Safety: never follows symbolic links, junctions or other reparse points (they are recorded as
 * special entries and not descended into), never reads file contents, never descends into
 * protected tool/application directories, and tolerates inaccessible entries. Memory use is bounded:
 * entries are streamed into fixed-size database batches.
 */
public final class Scanner {
    private static final Logger LOG = LoggerFactory.getLogger(Scanner.class);
    static final int BATCH = 500;

    private final FileInspector inspector;
    private final ItemRepository items;
    private final RootRepository roots;

    public Scanner(FileInspector inspector, ItemRepository items, RootRepository roots) {
        this.inspector = inspector;
        this.items = items;
        this.roots = roots;
    }

    /** Live progress of a scan. */
    public record Progress(long rootId, long files, long dirs, long bytes, long errors, String current,
                           boolean finished, String status) { }

    public record Result(long files, long dirs, long bytes, long errors, long inserted, long changed, long moved,
                         long removed, boolean completed, String status) { }

    /**
     * Scans {@code start} (the whole root when null) and reconciles the index with what was found.
     * Reconciliation (removing vanished entries) only happens when the walk completed.
     */
    public Result scan(MonitoredRoot root, Path start, CancellationToken token, Consumer<Progress> progress)
            throws IOException, SQLException {
        Path rootPath = PathUtil.normalize(root.path());
        Path from = start == null ? rootPath : PathUtil.normalize(start);
        if (!PathUtil.isWithin(from, rootPath)) throw new IOException("Scan start is outside the monitored root");
        if (!Files.isDirectory(rootPath, LinkOption.NOFOLLOW_LINKS)) {
            roots.recordScan(root.id(), "Unavailable: folder not found or disconnected");
            throw new IOException("Monitored folder is not available: " + rootPath);
        }
        VolumeInfo volume = inspector.volumeOf(rootPath);
        roots.upsertVolume(volume);
        long generation = roots.nextGeneration(root.id());

        Walker w = new Walker(root.id(), rootPath, volume, generation, token, progress);
        boolean completed = false;
        String status;
        try {
            if (Files.isDirectory(from, LinkOption.NOFOLLOW_LINKS)) {
                Files.walkFileTree(from, EnumSet.noneOf(java.nio.file.FileVisitOption.class), Integer.MAX_VALUE, w);
            } else if (Files.exists(from, LinkOption.NOFOLLOW_LINKS)) {
                w.visitFile(from, Files.readAttributes(from, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS));
            }
            w.flush();
            completed = true;
            status = "OK";
        } catch (CancellationException e) {
            w.flushQuietly();
            status = "Cancelled";
        } catch (UncheckedSql e) {
            throw e.getCause();
        }
        long removed = 0;
        if (completed) {
            removed = items.finalizeScan(root.id(), generation, start == null ? null : PathUtil.key(from));
        }
        String summary = status + " — " + w.files.get() + " files, " + Sizes.format(w.bytes.get())
                + (w.errors.get() > 0 ? ", " + w.errors.get() + " inaccessible" : "");
        if (start == null) roots.recordScan(root.id(), summary);
        progress.accept(new Progress(root.id(), w.files.get(), w.dirs.get(), w.bytes.get(), w.errors.get(), "",
                true, summary));
        return new Result(w.files.get(), w.dirs.get(), w.bytes.get(), w.errors.get(), w.inserted, w.changed, w.moved,
                removed, completed, summary);
    }

    /** Wraps SQLException so it can cross the FileVisitor boundary. */
    static final class UncheckedSql extends RuntimeException {
        UncheckedSql(SQLException e) { super(e); }
        @Override public synchronized SQLException getCause() { return (SQLException) super.getCause(); }
    }

    private final class Walker implements FileVisitor<Path> {
        final long rootId;
        final Path rootPath;
        final VolumeInfo volume;
        final long generation;
        final CancellationToken token;
        final Consumer<Progress> progress;
        final List<ScannedEntry> batch = new ArrayList<>(BATCH);
        final AtomicLong files = new AtomicLong();
        final AtomicLong dirs = new AtomicLong();
        final AtomicLong bytes = new AtomicLong();
        final AtomicLong errors = new AtomicLong();
        long inserted, changed, moved;
        long lastProgress;

        Walker(long rootId, Path rootPath, VolumeInfo volume, long generation, CancellationToken token,
               Consumer<Progress> progress) {
            this.rootId = rootId;
            this.rootPath = rootPath;
            this.volume = volume;
            this.generation = generation;
            this.token = token;
            this.progress = progress;
        }

        @Override
        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
            token.checkpoint();
            boolean link = attrs.isSymbolicLink() || attrs.isOther();
            add(entry(dir, attrs, true, link));
            dirs.incrementAndGet();
            if (!dir.equals(rootPath) && link) {
                // Junction / mount point / symlinked directory: never followed.
                return FileVisitResult.SKIP_SUBTREE;
            }
            String name = dir.getFileName() == null ? "" : dir.getFileName().toString().toLowerCase(Locale.ROOT);
            if (!dir.equals(rootPath) && isSkippedDirName(name)) {
                return FileVisitResult.SKIP_SUBTREE;
            }
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
            token.checkpoint();
            boolean special = !attrs.isRegularFile();
            add(entry(file, attrs, false, special));
            files.incrementAndGet();
            bytes.addAndGet(attrs.size());
            long now = System.nanoTime();
            if (now - lastProgress > 200_000_000L) {
                lastProgress = now;
                progress.accept(new Progress(rootId, files.get(), dirs.get(), bytes.get(), errors.get(),
                        file.toString(), false, "Scanning"));
            }
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFileFailed(Path file, IOException exc) {
            errors.incrementAndGet();
            LOG.debug("Cannot access {}: {}", file, exc.toString());
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult postVisitDirectory(Path dir, IOException exc) {
            if (exc != null) {
                errors.incrementAndGet();
                LOG.debug("Directory listing failed for {}: {}", dir, exc.toString());
            }
            return FileVisitResult.CONTINUE;
        }

        ScannedEntry entry(Path p, BasicFileAttributes a, boolean directory, boolean special) {
            Path np = PathUtil.normalize(p);
            String name = np.equals(rootPath) ? displayName(np) : String.valueOf(np.getFileName());
            String ext = directory ? "" : PathUtil.extension(name);
            String rel = rootPath.relativize(np).toString();
            String parentKey = np.equals(rootPath) || np.getParent() == null ? null : PathUtil.key(np.getParent());
            Set<String> attrs = new LinkedHashSet<>();
            String fileKey = null;
            long physical = -1;
            boolean exact = false;
            int links = -1;
            int streams = -1;
            boolean compressed = false;
            if (!directory) {
                try {
                    NativeFileState s = inspector.inspect(np);
                    if (s.fileKey() != null) fileKey = s.fileKey().asString();
                    physical = s.allocatedSize();
                    exact = s.allocationExact();
                    links = s.linkCount();
                    streams = s.alternateStreams();
                    compressed = s.compressed();
                    if (s.compressed()) attrs.add("compressed");
                    if (s.encrypted()) attrs.add("encrypted");
                    if (s.sparse()) attrs.add("sparse");
                    if (s.reparsePoint()) attrs.add("reparse");
                    if (s.cloudPlaceholder()) attrs.add("cloud");
                    if (s.offline()) attrs.add("offline");
                    if (s.system()) attrs.add("system");
                    special = special || s.reparsePoint() || s.cloudPlaceholder();
                } catch (IOException e) {
                    errors.incrementAndGet();
                    LOG.debug("Inspect failed for {}: {}", np, e.toString());
                }
            } else if (special) {
                attrs.add("reparse");
            }
            if (PathUtil.isWindows()) {
                try {
                    DosFileAttributes dos = Files.readAttributes(np, DosFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                    if (dos.isReadOnly() && !directory) attrs.add("readonly");
                    if (dos.isSystem()) attrs.add("system");
                } catch (IOException | UnsupportedOperationException ignored) {
                    // DOS attributes are optional metadata
                }
            }
            long created = a.creationTime() == null ? 0 : a.creationTime().toMillis();
            long modified = a.lastModifiedTime() == null ? 0 : a.lastModifiedTime().toMillis();
            long accessed = a.lastAccessTime() == null ? 0 : a.lastAccessTime().toMillis();
            return new ScannedEntry(np, rel, parentKey, name, ext, FileCategories.of(ext, directory), directory,
                    special, fileKey, volume.volumeId(), directory ? 0 : a.size(), physical, exact, created, modified,
                    accessed, links, streams, attrs, compressed);
        }

        void add(ScannedEntry e) {
            batch.add(e);
            if (batch.size() >= BATCH) flush();
        }

        void flush() {
            if (batch.isEmpty()) return;
            try {
                ItemRepository.UpsertStats st = items.upsertBatch(rootId, generation, batch);
                inserted += st.inserted();
                changed += st.changed();
                moved += st.moved();
            } catch (SQLException e) {
                throw new UncheckedSql(e);
            }
            batch.clear();
        }

        void flushQuietly() {
            try {
                flush();
            } catch (UncheckedSql e) {
                LOG.warn("Failed to persist the final scan batch", e.getCause());
            }
        }
    }

    private static String displayName(Path p) {
        return p.getFileName() == null ? p.toString() : p.getFileName().toString();
    }

    /** Tool and application directories that are recorded but never descended into. */
    static boolean isSkippedDirName(String lowerName) {
        return SKIPPED_DIRS.contains(lowerName);
    }

    private static final Set<String> SKIPPED_DIRS = Set.of(".git", ".svn", ".hg", "node_modules", ".venv", "venv",
            "__pycache__", ".idea", ".gradle", ".m2", ".tox", ".mypy_cache", ".pytest_cache", ".idlestow",
            "$recycle.bin", "system volume information", ".cache", "appdata");
}
