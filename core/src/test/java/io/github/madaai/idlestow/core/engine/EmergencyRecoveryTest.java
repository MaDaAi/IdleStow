package io.github.madaai.idlestow.core.engine;

import io.github.madaai.idlestow.core.db.Queries;
import io.github.madaai.idlestow.core.db.RecoveryRepository;
import io.github.madaai.idlestow.core.ipc.OptimizationWorker;
import io.github.madaai.idlestow.core.model.Enums.JobState;
import io.github.madaai.idlestow.core.model.Enums.Method;
import io.github.madaai.idlestow.core.model.Enums.Origin;
import io.github.madaai.idlestow.core.model.IndexedItem;
import io.github.madaai.idlestow.core.model.MonitoredRoot;
import io.github.madaai.idlestow.core.testsupport.FakePlatform;
import io.github.madaai.idlestow.core.testsupport.TestEnv;
import io.github.madaai.idlestow.core.util.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Verifies the documented emergency recovery procedure (docs/user-guide/recovery.md): a replaced file can be
 * recovered from its archive with a generic ZIP reader and the independent JSON manifest alone, after the
 * IdleStow database is deleted.
 */
class EmergencyRecoveryTest {

    @TempDir Path tmp;

    @Test
    void archiveAndManifestAloneRecoverTheFile() throws Exception {
        OptimizationWorker worker = TestEnv.worker();
        Path docs = tmp.resolve("docs");
        Path f = TestEnv.textFile(docs.resolve("report.txt"), 500_000, 100);
        String sha = TestEnv.sha256(f);
        RecoveryRepository.Record rec;
        try (IdleStowEngine e = TestEnv.engine(tmp.resolve("data"), new FakePlatform(), worker, null)) {
            TestEnv.testSettings(e);
            MonitoredRoot r = e.addRoot(docs);
            e.scan(r.id()).get();
            IndexedItem it = e.queries().list(docs, null, Queries.Filter.ALL, Queries.SortField.NAME, true, 0, 10)
                    .stream().map(Queries.Row::item).filter(x -> !x.directory()).findFirst().orElseThrow();
            long job = e.execute(e.preview(List.of(it.id()), Method.ZIP_REPLACE), List.of(it.id()), Origin.MANUAL, true);
            assertEquals(JobState.COMPLETED, TestEnv.awaitJob(e, job).state());
            rec = e.recoveryRecords().currentForItem(it.id()).orElseThrow();
        }
        // "Disaster": the database is gone.
        Files.delete(tmp.resolve("data").resolve("idlestow.db"));

        Map<String, Object> manifest = Json.readFile(Path.of(rec.manifestPath()));
        Path archive = Path.of(Json.str(manifest, "archive_path"));
        String entry = Json.str(manifest, "entry_name");
        Path out = tmp.resolve("recovered").resolve(entry);
        Files.createDirectories(out.getParent());
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            ZipEntry ze = zip.getEntry(entry);
            assertNotNull(ze, "data entry present");
            assertNotNull(zip.getEntry(".idlestow-manifest.json"), "embedded manifest present");
            try (InputStream in = zip.getInputStream(ze)) {
                Files.copy(in, out);
            }
        }
        String recovered = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(out)));
        assertEquals(Json.str(manifest, "sha256"), recovered, "matches the manifest checksum");
        assertEquals(sha, recovered, "identical to the original content");
    }
}
