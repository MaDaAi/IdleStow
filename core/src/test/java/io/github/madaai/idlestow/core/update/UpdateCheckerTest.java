package io.github.madaai.idlestow.core.update;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class UpdateCheckerTest {
    static final Version CURRENT = new Version(1, 0, 0);

    static String url(String tag, String name) {
        return "https://github.com/MaDaAi/IdleStow/releases/download/" + tag + "/" + name;
    }

    static String json(String tag, boolean draft, boolean pre, String assets) {
        return "{\"tag_name\":\"" + tag + "\",\"draft\":" + draft + ",\"prerelease\":" + pre
                + ",\"body\":\"notes\",\"published_at\":\"2026-01-01T00:00:00Z\",\"assets\":[" + assets + "]}";
    }

    static String asset(String name, String url, long size) {
        return "{\"name\":\"" + name + "\",\"size\":" + size + ",\"browser_download_url\":\"" + url + "\"}";
    }

    static String goodAssets(String tag, long size) {
        Version v = Version.parse(tag).orElseThrow();
        String zip = UpdateChecker.zipName(v);
        return asset(zip, url(tag, zip), size) + "," + asset("SHA256SUMS.txt", url(tag, "SHA256SUMS.txt"), 100);
    }

    static Optional<UpdateChecker.Release> parse(String json) throws IOException {
        return UpdateChecker.parse(json.getBytes(StandardCharsets.UTF_8), CURRENT);
    }

    @Test
    void validReleaseIsReturned() throws IOException {
        UpdateChecker.Release r = parse(json("v1.1.0", false, false, goodAssets("v1.1.0", 1234))).orElseThrow();
        assertEquals(new Version(1, 1, 0), r.version());
        assertEquals("v1.1.0", r.tag());
        assertEquals("IdleStow-1.1.0-windows-x64-portable.zip", r.zipName());
        assertEquals(URI.create(url("v1.1.0", "IdleStow-1.1.0-windows-x64-portable.zip")), r.zipUrl());
        assertEquals(URI.create(url("v1.1.0", "SHA256SUMS.txt")), r.sumsUrl());
        assertEquals(1234, r.zipSize());
        assertEquals("https://github.com/MaDaAi/IdleStow/releases/tag/v1.1.0", r.pageUrl());
        assertEquals("notes", r.notes());
    }

    @Test
    void sameOrOlderVersionIsEmpty() throws IOException {
        assertTrue(parse(json("v1.0.0", false, false, goodAssets("v1.0.0", 10))).isEmpty());
        assertTrue(parse(json("v0.9.9", false, false, goodAssets("v0.9.9", 10))).isEmpty());
    }

    @Test
    void sameVersionIsEmptyEvenWithoutAssets() throws IOException {
        assertTrue(parse(json("v1.0.0", false, false, "")).isEmpty());
    }

    @Test
    void draftOrPrereleaseIsEmpty() throws IOException {
        assertTrue(parse(json("v2.0.0", true, false, goodAssets("v2.0.0", 10))).isEmpty());
        assertTrue(parse(json("v2.0.0", false, true, goodAssets("v2.0.0", 10))).isEmpty());
    }

    @Test
    void assetOnAnotherRepoOrHostIsRejected() {
        String zip = "IdleStow-1.1.0-windows-x64-portable.zip";
        String good = asset("SHA256SUMS.txt", url("v1.1.0", "SHA256SUMS.txt"), 1);
        for (String bad : new String[]{
                "https://github.com/Evil/IdleStow/releases/download/v1.1.0/" + zip,
                "https://github.com/MaDaAi/Other/releases/download/v1.1.0/" + zip,
                "https://evil.example/MaDaAi/IdleStow/releases/download/v1.1.0/" + zip,
                "http://github.com/MaDaAi/IdleStow/releases/download/v1.1.0/" + zip,
                "https://github.com/MaDaAi/IdleStow/releases/download/v9.9.9/" + zip}) {
            String j = json("v1.1.0", false, false, asset(zip, bad, 10) + "," + good);
            assertThrows(IOException.class, () -> parse(j), bad);
        }
        String goodZip = asset(zip, url("v1.1.0", zip), 10);
        String j = json("v1.1.0", false, false, goodZip + ","
                + asset("SHA256SUMS.txt", "https://evil.example/SHA256SUMS.txt", 1));
        assertThrows(IOException.class, () -> parse(j));
    }

    @Test
    void missingSumsOrZipIsRejected() {
        String zip = UpdateChecker.zipName(new Version(1, 1, 0));
        String onlyZip = json("v1.1.0", false, false, asset(zip, url("v1.1.0", zip), 10));
        assertThrows(IOException.class, () -> parse(onlyZip));
        String onlySums = json("v1.1.0", false, false, asset("SHA256SUMS.txt", url("v1.1.0", "SHA256SUMS.txt"), 10));
        assertThrows(IOException.class, () -> parse(onlySums));
        assertThrows(IOException.class, () -> parse(json("v1.1.0", false, false, "")));
    }

    @Test
    void wrongZipNameIsRejected() {
        String wrong = "IdleStow-1.2.0-windows-x64-portable.zip";
        String j = json("v1.1.0", false, false, asset(wrong, url("v1.1.0", wrong), 10) + ","
                + asset("SHA256SUMS.txt", url("v1.1.0", "SHA256SUMS.txt"), 1));
        assertThrows(IOException.class, () -> parse(j));
    }

    @Test
    void badSizesAreRejected() throws IOException {
        assertThrows(IOException.class, () -> parse(json("v1.1.0", false, false, goodAssets("v1.1.0", 0))));
        assertThrows(IOException.class, () -> parse(json("v1.1.0", false, false, goodAssets("v1.1.0", -5))));
        assertThrows(IOException.class, () -> parse(json("v1.1.0", false, false,
                goodAssets("v1.1.0", UpdateChecker.MAX_ZIP_BYTES + 1))));
        assertTrue(parse(json("v1.1.0", false, false, goodAssets("v1.1.0", UpdateChecker.MAX_ZIP_BYTES)))
                .isPresent());
        assertTrue(parse(json("v1.1.0", false, false, goodAssets("v1.1.0", 1))).isPresent());
    }

    @Test
    void nonVersionTagIsRejected() {
        for (String tag : new String[]{"latest", "v1.2", "v1.2.3-rc1", "1.2.3.4", ""}) {
            String j = json(tag, false, false, "");
            assertThrows(IOException.class, () -> parse(j), tag);
        }
        assertThrows(IOException.class, () -> parse("{\"draft\":false,\"assets\":[]}"));
    }

    @Test
    void malformedJsonIsIOException() {
        for (String bad : new String[]{"not json", "{\"tag_name\":", "[]", "\"x\"", "42", "null", ""}) {
            assertThrows(IOException.class, () -> parse(bad), bad);
        }
    }

    @Test
    void longNotesAreTruncated() throws IOException {
        String notes = "x".repeat(50_000);
        String j = "{\"tag_name\":\"v1.1.0\",\"body\":\"" + notes + "\",\"assets\":[" + goodAssets("v1.1.0", 10) + "]}";
        assertEquals(20_000, parse(j).orElseThrow().notes().length());
    }

    @Test
    void checkUsesTransportAndLatestUri() throws IOException {
        FakeTransport t = new FakeTransport().put(UpdateChecker.LATEST,
                json("v1.5.0", false, false, goodAssets("v1.5.0", 10)).getBytes(StandardCharsets.UTF_8));
        assertEquals(new Version(1, 5, 0), new UpdateChecker(t).check(CURRENT).orElseThrow().version());
        assertTrue(new UpdateChecker(t).check(new Version(1, 5, 0)).isEmpty());
        assertEquals("https://api.github.com/repos/MaDaAi/IdleStow/releases/latest", UpdateChecker.LATEST.toString());
    }

    @Test
    void checkPropagatesTransportFailure() {
        assertThrows(IOException.class, () -> new UpdateChecker(new FakeTransport()).check(CURRENT));
    }
}
