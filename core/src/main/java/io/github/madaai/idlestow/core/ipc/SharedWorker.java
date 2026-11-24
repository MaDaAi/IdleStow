package io.github.madaai.idlestow.core.ipc;

import io.github.madaai.idlestow.core.ipc.WorkerClient.ProgressListener;
import io.github.madaai.idlestow.core.model.Enums.ArchiveFormat;
import io.github.madaai.idlestow.core.model.Enums.CompressionProfile;
import io.github.madaai.idlestow.core.util.CancellationToken;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

/** Delegating worker whose {@link #close()} does nothing: the caller that created the delegate owns its lifecycle. */
public record SharedWorker(OptimizationWorker delegate) implements OptimizationWorker {
    @Override public Set<ArchiveFormat> supportedFormats() throws WorkerException { return delegate.supportedFormats(); }

    @Override
    public HashResult hash(Path f, CancellationToken t, ProgressListener p) throws WorkerException {
        return delegate.hash(f, t, p);
    }

    @Override
    public Estimate estimate(Path f, long m, CompressionProfile p) throws WorkerException {
        return delegate.estimate(f, m, p);
    }

    @Override
    public ArchiveResult createArchive(Path s, Path d, ArchiveFormat f, CompressionProfile p, String e, String x,
                                       boolean l, Map<String, Object> m, CancellationToken t, ProgressListener pl)
            throws WorkerException {
        return delegate.createArchive(s, d, f, p, e, x, l, m, t, pl);
    }

    @Override
    public VerifyResult verifyArchive(Path a, ArchiveFormat f, String e, String x, long s, CancellationToken t,
                                      ProgressListener p) throws WorkerException {
        return delegate.verifyArchive(a, f, e, x, s, t, p);
    }

    @Override
    public ExtractResult extractArchive(Path a, ArchiveFormat f, String e, Path d, String x, long s,
                                        CancellationToken t, ProgressListener p) throws WorkerException {
        return delegate.extractArchive(a, f, e, d, x, s, t, p);
    }

    @Override public SystemStatus systemStatus() throws WorkerException { return delegate.systemStatus(); }
    @Override public String status() { return delegate.status(); }
    @Override public void close() { }
}
