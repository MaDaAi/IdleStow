package io.github.madaai.idlestow.core.update;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.madaai.idlestow.core.util.Json;

import java.io.IOException;
import java.net.URI;
import java.util.Optional;

/**
 * Asks GitHub for the latest published release of IdleStow (drafts and pre-releases are never offered) and
 * validates everything it is told before trusting it: the tag must be a plain version, and the two required files
 * must be attached to that exact release of the official repository.
 */
public final class UpdateChecker {
    public static final String OWNER = "MaDaAi";
    public static final String REPO = "IdleStow";
    static final URI LATEST = URI.create("https://api.github.com/repos/" + OWNER + "/" + REPO + "/releases/latest");
    static final long MAX_API_BYTES = 2L << 20;
    /** The portable zip is ~80 MB; anything far larger is not a real IdleStow release. */
    static final long MAX_ZIP_BYTES = 600L << 20;
    public static final String SUMS_NAME = "SHA256SUMS.txt";

    /** A release that is newer than the running version, with the files needed to install it. */
    public record Release(Version version, String tag, String pageUrl, String notes, String publishedAt,
                          String zipName, URI zipUrl, long zipSize, URI sumsUrl) { }

    private final UpdateTransport transport;

    public UpdateChecker(UpdateTransport transport) {
        this.transport = transport;
    }

    public static String zipName(Version v) {
        return "IdleStow-" + v + "-windows-x64-portable.zip";
    }

    /** One small HTTPS request. Empty when the running version is current (or newer). */
    public Optional<Release> check(Version current) throws IOException {
        return parse(transport.get(LATEST, MAX_API_BYTES), current);
    }

    static Optional<Release> parse(byte[] body, Version current) throws IOException {
        JsonNode root;
        try {
            root = Json.MAPPER.readTree(body);
        } catch (IOException e) {
            throw new IOException("GitHub sent an unreadable answer", e);
        }
        if (root == null || !root.isObject()) throw new IOException("GitHub sent an unexpected answer");
        if (root.path("draft").asBoolean(false) || root.path("prerelease").asBoolean(false)) return Optional.empty();
        String tag = root.path("tag_name").asText("");
        Version v = Version.parse(tag).orElseThrow(() -> new IOException("Unexpected release tag " + tag));
        if (!v.newerThan(current)) return Optional.empty();

        String zipName = zipName(v);
        String prefix = "https://github.com/" + OWNER + "/" + REPO + "/releases/download/" + tag + "/";
        URI zipUrl = null;
        URI sumsUrl = null;
        long zipSize = -1;
        for (JsonNode a : root.path("assets")) {
            String name = a.path("name").asText("");
            String url = a.path("browser_download_url").asText("");
            if (name.equals(zipName) && url.equals(prefix + zipName)) {
                zipUrl = URI.create(url);
                zipSize = a.path("size").asLong(-1);
            } else if (name.equals(SUMS_NAME) && url.equals(prefix + SUMS_NAME)) {
                sumsUrl = URI.create(url);
            }
        }
        if (zipUrl == null || sumsUrl == null) {
            throw new IOException("Release " + tag + " is missing " + (zipUrl == null ? zipName : SUMS_NAME));
        }
        if (zipSize <= 0 || zipSize > MAX_ZIP_BYTES) throw new IOException("Release " + tag + " has an unexpected size");
        String page = "https://github.com/" + OWNER + "/" + REPO + "/releases/tag/" + tag;
        String notes = root.path("body").asText("");
        if (notes.length() > 20_000) notes = notes.substring(0, 20_000);
        return Optional.of(new Release(v, tag, page, notes, root.path("published_at").asText(""), zipName, zipUrl,
                zipSize, sumsUrl));
    }
}
