package io.github.madaai.idlestow.core.update;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.function.LongConsumer;

/** In-memory {@link UpdateTransport}: URI to bytes, no network. */
final class FakeTransport implements UpdateTransport {
    final Map<URI, byte[]> files = new HashMap<>();
    /** When true the fake never looks at the cancellation supplier (to test later cancellation points). */
    boolean ignoreCancel;

    FakeTransport put(URI uri, byte[] body) {
        files.put(uri, body);
        return this;
    }

    private byte[] body(URI uri) throws IOException {
        byte[] b = files.get(uri);
        if (b == null) throw new IOException("404 " + uri);
        return b;
    }

    @Override
    public byte[] get(URI uri, long maxBytes) throws IOException {
        byte[] b = body(uri);
        if (b.length > maxBytes) throw new IOException("too large");
        return b;
    }

    @Override
    public void download(URI uri, OutputStream out, long maxBytes, LongConsumer progress, BooleanSupplier cancelled)
            throws IOException {
        byte[] b = body(uri);
        if (!ignoreCancel && cancelled.getAsBoolean()) throw new CancellationException("cancelled");
        if (b.length > maxBytes) throw new IOException("larger than expected");
        out.write(b);
        progress.accept(b.length);
    }
}
