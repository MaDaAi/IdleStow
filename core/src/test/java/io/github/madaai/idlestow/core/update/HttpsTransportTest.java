package io.github.madaai.idlestow.core.update;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.net.URI;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Only the pure URL policy; no connection is ever opened. */
class HttpsTransportTest {
    @ParameterizedTest
    @ValueSource(strings = {
            "https://api.github.com/repos/MaDaAi/IdleStow/releases/latest",
            "https://github.com/MaDaAi/IdleStow/releases/download/v1.0.0/x.zip",
            "https://objects.githubusercontent.com/abc?x=1",
            "https://release-assets.githubusercontent.com/abc",
            "HTTPS://GitHub.com/MaDaAi/IdleStow"})
    void acceptsGitHubHttps(String u) {
        assertDoesNotThrow(() -> HttpsTransport.requireAllowed(URI.create(u)));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://github.com/MaDaAi/IdleStow",
            "https://evil.example/x",
            "https://github.com.evil.example/x",
            "https://api.github.com.evil.example/x",
            "https://evilgithub.com/x",
            "https://user@github.com/x",
            "https://user:pw@github.com/x",
            "ftp://github.com/x",
            "file:///C:/x",
            "/relative/path"})
    void rejectsEverythingElse(String u) {
        assertThrows(IOException.class, () -> HttpsTransport.requireAllowed(URI.create(u)));
    }

    @Test
    void rejectsNull() {
        assertThrows(IOException.class, () -> HttpsTransport.requireAllowed(null));
    }

    @Test
    void allowedHostsAreExactlyTheGitHubOnes() {
        assertEquals(java.util.Set.of("api.github.com", "github.com", "objects.githubusercontent.com",
                "release-assets.githubusercontent.com"), HttpsTransport.ALLOWED_HOSTS);
    }
}
