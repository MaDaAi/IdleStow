package io.github.madaai.idlestow.core.update;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.function.LongConsumer;

/**
 * HTTPS-only transport for the updater (this is the only class in IdleStow that opens network connections; an
 * architecture test enforces that). It sends no cookies, no credentials and no personal data: just a generic
 * User-Agent with the app version, which GitHub's API requires. Every request and every redirect target must be
 * HTTPS on a GitHub host; the default JDK certificate validation applies.
 */
public final class HttpsTransport implements UpdateTransport {
    /** Hosts that serve GitHub release metadata and release files (downloads redirect to the asset hosts). */
    static final Set<String> ALLOWED_HOSTS = Set.of("api.github.com", "github.com",
            "objects.githubusercontent.com", "release-assets.githubusercontent.com");

    private final HttpClient client;
    private final String userAgent;

    public HttpsTransport(String appVersion) {
        this.client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL) // never follows HTTPS -> HTTP
                .connectTimeout(Duration.ofSeconds(15))
                .build();
        this.userAgent = "IdleStow-Updater/" + appVersion;
    }

    static void requireAllowed(URI uri) throws IOException {
        if (uri == null || !"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                || !ALLOWED_HOSTS.contains(uri.getHost().toLowerCase(Locale.ROOT)) || uri.getUserInfo() != null) {
            throw new IOException("Refusing to contact " + uri + ": updates only come from GitHub over HTTPS");
        }
    }

    private HttpRequest request(URI uri, Duration timeout) {
        return HttpRequest.newBuilder(uri)
                .timeout(timeout)
                .header("User-Agent", userAgent)
                .header("Accept", uri.getHost().equals("api.github.com") ? "application/vnd.github+json"
                        : "application/octet-stream")
                .GET().build();
    }

    @Override
    public byte[] get(URI uri, long maxBytes) throws IOException {
        requireAllowed(uri);
        try {
            HttpResponse<InputStream> r = client.send(request(uri, Duration.ofSeconds(30)),
                    HttpResponse.BodyHandlers.ofInputStream());
            requireAllowed(r.uri());
            try (InputStream in = r.body()) {
                if (r.statusCode() != 200) throw new IOException("GitHub answered HTTP " + r.statusCode());
                byte[] body = in.readNBytes((int) Math.min(Integer.MAX_VALUE - 8, maxBytes + 1));
                if (body.length > maxBytes) throw new IOException("Response from " + uri.getHost() + " is too large");
                return body;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted", e);
        }
    }

    @Override
    public void download(URI uri, OutputStream out, long maxBytes, LongConsumer progress, BooleanSupplier cancelled)
            throws IOException {
        requireAllowed(uri);
        try {
            HttpResponse<InputStream> r = client.send(request(uri, Duration.ofMinutes(30)),
                    HttpResponse.BodyHandlers.ofInputStream());
            requireAllowed(r.uri());
            try (InputStream in = r.body()) {
                if (r.statusCode() != 200) throw new IOException("Download failed: HTTP " + r.statusCode());
                byte[] buf = new byte[1 << 16];
                long total = 0;
                int n;
                while ((n = in.read(buf)) >= 0) {
                    if (cancelled.getAsBoolean()) throw new CancellationException("Download cancelled");
                    total += n;
                    if (total > maxBytes) throw new IOException("Download is larger than expected");
                    out.write(buf, 0, n);
                    progress.accept(total);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted", e);
        }
    }
}
