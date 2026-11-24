package io.github.madaai.idlestow.core.ipc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.madaai.idlestow.core.ipc.WorkerClient.ProgressListener;
import io.github.madaai.idlestow.core.model.Enums.ArchiveFormat;
import io.github.madaai.idlestow.core.model.Enums.CompressionProfile;
import io.github.madaai.idlestow.core.util.CancellationToken;

import java.nio.file.Path;
import java.time.Duration;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** {@link OptimizationWorker} backed by the persistent Python process. */
public final class PythonWorker implements OptimizationWorker {
    private static final Duration QUICK = Duration.ofSeconds(30);
    private static final Duration LONG = Duration.ofSeconds(120);

    private final WorkerClient client;

    public PythonWorker(WorkerClient client) {
        this.client = client;
    }

    @Override
    public Set<ArchiveFormat> supportedFormats() throws WorkerException {
        JsonNode caps = client.capabilities();
        Set<ArchiveFormat> out = EnumSet.noneOf(ArchiveFormat.class);
        for (JsonNode f : caps.path("formats")) {
            try {
                out.add(ArchiveFormat.fromWire(f.asText()));
            } catch (IllegalArgumentException ignored) {
                // unknown future format
            }
        }
        return out;
    }

    /** Runs a long call, propagating cancellation from the token while waiting. */
    private JsonNode longCall(String op, ObjectNode p, CancellationToken token, ProgressListener progress)
            throws WorkerException {
        WorkerClient.Call call = client.submit(op, p, progress);
        if (token == null) return call.await(LONG);
        Thread watcher = Thread.ofVirtual().start(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                if (token.isCancelled()) {
                    call.cancel();
                    return;
                }
                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    return;
                }
            }
        });
        try {
            return call.await(LONG);
        } finally {
            watcher.interrupt();
        }
    }

    @Override
    public HashResult hash(Path file, CancellationToken token, ProgressListener progress) throws WorkerException {
        ObjectNode p = client.params().put("path", file.toAbsolutePath().toString());
        JsonNode r = longCall("hash_file", p, token, progress);
        return new HashResult(r.path("sha256").asText(), r.path("size").asLong());
    }

    @Override
    public Estimate estimate(Path file, long maxSampleBytes, CompressionProfile profile) throws WorkerException {
        ObjectNode p = client.params()
                .put("path", file.toAbsolutePath().toString())
                .put("max_sample_bytes", maxSampleBytes)
                .put("level", level(profile));
        JsonNode r = client.request("estimate_compressibility", p, null, LONG);
        return new Estimate(r.path("file_size").asLong(), r.path("sampled_bytes").asLong(),
                r.path("full_read").asBoolean(), r.path("detected_format").asText("unknown"),
                r.path("entropy_bits_per_byte").asDouble(), r.path("zip_ratio").asDouble(1.0),
                r.path("zstd_ratio").isNumber() ? r.path("zstd_ratio").asDouble() : null,
                r.path("ntfs_ratio").asDouble(1.0), r.path("estimated_zip_savings").asLong(),
                r.path("estimated_zstd_savings").isNumber() ? r.path("estimated_zstd_savings").asLong() : null,
                r.path("estimated_ntfs_savings").asLong(), r.path("basis").asText("sampled"));
    }

    @Override
    public ArchiveResult createArchive(Path source, Path dest, ArchiveFormat format, CompressionProfile profile,
                                       String entryName, String expectedSha256, boolean failIfLarger,
                                       Map<String, Object> manifest, CancellationToken token,
                                       ProgressListener progress) throws WorkerException {
        ObjectNode p = client.params()
                .put("source", source.toAbsolutePath().toString())
                .put("dest", dest.toAbsolutePath().toString())
                .put("format", format.wire())
                .put("level", level(profile))
                .put("entry_name", entryName)
                .put("fail_if_larger", failIfLarger);
        if (expectedSha256 != null) p.put("expected_sha256", expectedSha256);
        if (manifest != null) p.set("manifest", client.json().valueToTree(manifest));
        JsonNode r = longCall("create_archive", p, token, progress);
        return new ArchiveResult(Path.of(r.path("archive").asText()), r.path("archive_size").asLong(),
                r.path("source_size").asLong(), r.path("source_sha256").asText(), r.path("entry_name").asText());
    }

    @Override
    public VerifyResult verifyArchive(Path archive, ArchiveFormat format, String entryName, String expectedSha256,
                                      long expectedSize, CancellationToken token, ProgressListener progress)
            throws WorkerException {
        ObjectNode p = client.params()
                .put("archive", archive.toAbsolutePath().toString())
                .put("format", format.wire())
                .put("entry_name", entryName)
                .put("expected_sha256", expectedSha256)
                .put("expected_size", expectedSize);
        JsonNode r = longCall("verify_archive", p, token, progress);
        return new VerifyResult(r.path("ok").asBoolean(false), r.path("sha256").asText(), r.path("size").asLong());
    }

    @Override
    public ExtractResult extractArchive(Path archive, ArchiveFormat format, String entryName, Path dest,
                                        String expectedSha256, long expectedSize, CancellationToken token,
                                        ProgressListener progress) throws WorkerException {
        ObjectNode p = client.params()
                .put("archive", archive.toAbsolutePath().toString())
                .put("format", format.wire())
                .put("entry_name", entryName)
                .put("dest", dest.toAbsolutePath().toString())
                .put("expected_sha256", expectedSha256)
                .put("expected_size", expectedSize);
        JsonNode r = longCall("extract_archive", p, token, progress);
        return new ExtractResult(Path.of(r.path("dest").asText()), r.path("size").asLong(), r.path("sha256").asText());
    }

    @Override
    public SystemStatus systemStatus() throws WorkerException {
        JsonNode r = client.request("system_status", null, null, QUICK);
        JsonNode b = r.path("battery");
        return new SystemStatus(r.path("cpu_percent").isNumber() ? r.path("cpu_percent").asDouble() : null,
                b.isObject() ? b.path("percent").asDouble() : null,
                b.isObject() ? b.path("plugged").asBoolean() : null, r.path("source").asText("none"));
    }

    @Override
    public String status() {
        return client.status();
    }

    private static String level(CompressionProfile p) {
        return p == CompressionProfile.MAXIMUM ? "maximum" : p.name().toLowerCase(Locale.ROOT);
    }

    @Override
    public void close() {
        client.close();
    }
}
