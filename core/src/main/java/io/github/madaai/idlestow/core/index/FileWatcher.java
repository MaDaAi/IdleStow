package io.github.madaai.idlestow.core.index;

import io.github.madaai.idlestow.core.util.PathUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Filesystem change monitoring for monitored roots.
 *
 * <p>Changes are collected into a bounded dirty set and delivered in debounced batches. Watcher
 * overflow, an oversized dirty set, or a directory limit being reached triggers a full
 * reconciliation scan of the root instead of silently losing changes. On Windows a single
 * recursive watch per root is used; elsewhere directories are registered individually up to a limit.
 */
public final class FileWatcher implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(FileWatcher.class);
    private static final int MAX_DIRTY = 5_000;
    private static final int MAX_DIRS_PER_ROOT = 20_000;

    /** Receives batches: changed paths for a root, or {@code fullRescan=true} on overflow. */
    public interface Sink {
        void changed(long rootId, Set<Path> paths, boolean fullRescan);
    }

    private final Sink sink;
    private final WatchService service;
    private final Map<WatchKey, Watched> keys = new ConcurrentHashMap<>();
    private final Map<Long, Set<Path>> dirty = new ConcurrentHashMap<>();
    private final Set<Long> overflowed = ConcurrentHashMap.newKeySet();
    private final ScheduledExecutorService flusher;
    private final Thread loop;
    private volatile boolean closed;

    private record Watched(long rootId, Path dir) { }

    public FileWatcher(Sink sink, long debounceMillis) throws IOException {
        this.sink = sink;
        this.service = FileSystems.getDefault().newWatchService();
        this.flusher = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "idlestow-watch-flush");
            t.setDaemon(true);
            return t;
        });
        flusher.scheduleWithFixedDelay(this::flush, debounceMillis, debounceMillis, TimeUnit.MILLISECONDS);
        this.loop = new Thread(this::run, "idlestow-watcher");
        loop.setDaemon(true);
        loop.start();
    }

    @SuppressWarnings("unchecked")
    public void watch(long rootId, Path root) {
        try {
            if (PathUtil.isWindows()) {
                WatchEvent.Modifier tree = fileTreeModifier();
                WatchKey k = tree != null
                        ? root.register(service, new WatchEvent.Kind[]{StandardWatchEventKinds.ENTRY_CREATE,
                        StandardWatchEventKinds.ENTRY_DELETE, StandardWatchEventKinds.ENTRY_MODIFY}, tree)
                        : register(root);
                keys.put(k, new Watched(rootId, root));
                return;
            }
            int[] count = {0};
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    if (attrs.isSymbolicLink() || attrs.isOther()) return FileVisitResult.SKIP_SUBTREE;
                    if (++count[0] > MAX_DIRS_PER_ROOT) {
                        LOG.info("Directory watch limit reached for {}; relying on scheduled reconciliation", root);
                        return FileVisitResult.TERMINATE;
                    }
                    keys.put(register(dir), new Watched(rootId, dir));
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            LOG.warn("Cannot watch {}: {} — scheduled reconciliation will still detect changes", root, e.toString());
        }
    }

    private WatchKey register(Path dir) throws IOException {
        return dir.register(service, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_DELETE,
                StandardWatchEventKinds.ENTRY_MODIFY);
    }

    /** {@code com.sun.nio.file.ExtendedWatchEventModifier.FILE_TREE}, looked up reflectively (JDK-specific). */
    private static WatchEvent.Modifier fileTreeModifier() {
        try {
            Class<?> c = Class.forName("com.sun.nio.file.ExtendedWatchEventModifier");
            for (Object o : c.getEnumConstants()) {
                if (o.toString().equals("FILE_TREE")) return (WatchEvent.Modifier) o;
            }
        } catch (ClassNotFoundException | ClassCastException e) {
            LOG.debug("FILE_TREE modifier unavailable");
        }
        return null;
    }

    public void unwatch(long rootId) {
        keys.entrySet().removeIf(e -> {
            if (e.getValue().rootId() == rootId) {
                e.getKey().cancel();
                return true;
            }
            return false;
        });
        dirty.remove(rootId);
    }

    private void run() {
        while (!closed) {
            WatchKey key;
            try {
                key = service.take();
            } catch (InterruptedException | ClosedWatchServiceException e) {
                return;
            }
            Watched w = keys.get(key);
            if (w != null) {
                for (WatchEvent<?> ev : key.pollEvents()) {
                    if (ev.kind() == StandardWatchEventKinds.OVERFLOW) {
                        overflowed.add(w.rootId());
                        continue;
                    }
                    Path rel = (Path) ev.context();
                    Path full = w.dir().resolve(rel);
                    if (isNoise(full)) continue;
                    Set<Path> set = dirty.computeIfAbsent(w.rootId(), k -> ConcurrentHashMap.newKeySet());
                    if (set.size() >= MAX_DIRTY) overflowed.add(w.rootId());
                    else set.add(full);
                    if (!PathUtil.isWindows() && ev.kind() == StandardWatchEventKinds.ENTRY_CREATE
                            && Files.isDirectory(full)) {
                        try {
                            keys.put(register(full), new Watched(w.rootId(), full));
                        } catch (IOException ignored) {
                            overflowed.add(w.rootId());
                        }
                    }
                }
            }
            if (!key.reset()) keys.remove(key);
        }
    }

    /** IdleStow's own temporary files and recovery store are not user activity. */
    private static boolean isNoise(Path p) {
        String s = p.toString();
        return s.contains(".idlestow-tmp-") || s.contains("\\.idlestow\\") || s.contains("/.idlestow/")
                || s.endsWith(".idlestow");
    }

    private void flush() {
        try {
            for (Long root : new HashSet<>(overflowed)) {
                overflowed.remove(root);
                dirty.remove(root);
                sink.changed(root, Set.of(), true);
            }
            for (Map.Entry<Long, Set<Path>> e : dirty.entrySet()) {
                Set<Path> batch = new HashSet<>(e.getValue());
                if (batch.isEmpty()) continue;
                e.getValue().removeAll(batch);
                sink.changed(e.getKey(), batch, false);
            }
        } catch (RuntimeException e) {
            LOG.warn("Change delivery failed", e);
        }
    }

    @Override
    public void close() {
        closed = true;
        flusher.shutdownNow();
        try {
            service.close();
        } catch (IOException ignored) {
            // closing
        }
        loop.interrupt();
    }
}
