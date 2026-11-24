package io.github.madaai.idlestow.core.ipc;

import io.github.madaai.idlestow.core.ipc.WorkerClient.ProgressListener;
import io.github.madaai.idlestow.core.model.Enums.ArchiveFormat;
import io.github.madaai.idlestow.core.model.Enums.CompressionProfile;
import io.github.madaai.idlestow.core.util.CancellationToken;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

/** Typed operations offered by the Python optimization worker. */
public interface OptimizationWorker extends AutoCloseable {

    record HashResult(String sha256, long size) { }

    record Estimate(long fileSize, long sampledBytes, boolean fullRead, String detectedFormat, double entropy,
                    double zipRatio, Double zstdRatio, double ntfsRatio, long zipSavings, Long zstdSavings,
                    long ntfsSavings, String basis) { }

    record ArchiveResult(Path archive, long archiveSize, long sourceSize, String sourceSha256, String entryName) { }

    record VerifyResult(boolean ok, String sha256, long size) { }

    record ExtractResult(Path dest, long size, String sha256) { }

    record SystemStatus(Double cpuPercent, Double batteryPercent, Boolean plugged, String source) { }

    Set<ArchiveFormat> supportedFormats() throws WorkerException;

    HashResult hash(Path file, CancellationToken token, ProgressListener progress) throws WorkerException;

    Estimate estimate(Path file, long maxSampleBytes, CompressionProfile profile) throws WorkerException;

    ArchiveResult createArchive(Path source, Path dest, ArchiveFormat format, CompressionProfile profile,
                                String entryName, String expectedSha256, boolean failIfLarger,
                                Map<String, Object> manifest, CancellationToken token, ProgressListener progress)
            throws WorkerException;

    VerifyResult verifyArchive(Path archive, ArchiveFormat format, String entryName, String expectedSha256,
                               long expectedSize, CancellationToken token, ProgressListener progress)
            throws WorkerException;

    ExtractResult extractArchive(Path archive, ArchiveFormat format, String entryName, Path dest,
                                 String expectedSha256, long expectedSize, CancellationToken token,
                                 ProgressListener progress) throws WorkerException;

    SystemStatus systemStatus() throws WorkerException;

    String status();

    @Override
    void close();
}
