package io.github.madaai.idlestow.core.update;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.util.function.BooleanSupplier;
import java.util.function.LongConsumer;

/**
 * The only way IdleStow talks to the network. Used solely by the updater: one request to read the latest release,
 * and, only after the user chooses to install it, the downloads of that release's files. Tests substitute a fake.
 */
public interface UpdateTransport {

    /** Reads a small response body (at most {@code maxBytes}). */
    byte[] get(URI uri, long maxBytes) throws IOException;

    /**
     * Streams a response body into {@code out} (at most {@code maxBytes}), reporting bytes received and stopping
     * with {@link java.util.concurrent.CancellationException} when {@code cancelled} becomes true.
     */
    void download(URI uri, OutputStream out, long maxBytes, LongConsumer progress, BooleanSupplier cancelled)
            throws IOException;
}
