package io.github.madaai.idlestow.core.ipc;

import io.github.madaai.idlestow.core.ipc.WorkerClient.ProgressListener;
import io.github.madaai.idlestow.core.model.Enums.ArchiveFormat;
import io.github.madaai.idlestow.core.model.Enums.CompressionProfile;
import io.github.madaai.idlestow.core.util.CancellationToken;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

/**
 * Used when no Python runtime/worker could be located. Every operation fails with WORKER_UNAVAILABLE,
 * so IdleStow degrades to read-only indexing without pretending that verification happened.
 */
public final class UnavailableWorker implements OptimizationWorker {
    private final String reason;

    public UnavailableWorker(String reason) {
        this.reason = reason;
    }

    private WorkerException fail() {
        return new WorkerException(WorkerException.WORKER_UNAVAILABLE, reason, false);
    }

    @Override public Set<ArchiveFormat> supportedFormats() throws WorkerException { throw fail(); }
    @Override public HashResult hash(Path f, CancellationToken t, ProgressListener p) throws WorkerException { throw fail(); }
    @Override public Estimate estimate(Path f, long m, CompressionProfile p) throws WorkerException { throw fail(); }

    @Override
    public ArchiveResult createArchive(Path s, Path d, ArchiveFormat f, CompressionProfile p, String e, String x,
                                       boolean l, Map<String, Object> m, CancellationToken t, ProgressListener pl)
            throws WorkerException {
        throw fail();
    }

    @Override
    public VerifyResult verifyArchive(Path a, ArchiveFormat f, String e, String x, long s, CancellationToken t,
                                      ProgressListener p) throws WorkerException {
        throw fail();
    }

    @Override
    public ExtractResult extractArchive(Path a, ArchiveFormat f, String e, Path d, String x, long s,
                                        CancellationToken t, ProgressListener p) throws WorkerException {
        throw fail();
    }

    @Override public SystemStatus systemStatus() throws WorkerException { throw fail(); }
    @Override public String status() { return "Unavailable: " + reason; }
    @Override public void close() { }
}
