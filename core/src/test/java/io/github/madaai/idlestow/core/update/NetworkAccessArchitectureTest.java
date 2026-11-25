package io.github.madaai.idlestow.core.update;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Documents and enforces the rule "only the updater may use the network": no Java source except
 * core/update/HttpsTransport.java may touch network APIs, and the Python worker may not import networking modules.
 */
class NetworkAccessArchitectureTest {
    private static final List<String> JAVA_FORBIDDEN = List.of("java.net.http", "HttpURLConnection", ".openConnection(",
            ".openStream(", "new Socket(", "SocketChannel", "DatagramSocket", "ServerSocket", "javax.net.ssl",
            "java.net.URL;");

    private static final String ALLOWED_JAVA = "core/src/main/java/io/github/madaai/idlestow/core/update/HttpsTransport.java";

    private static final Pattern PY_IMPORT = Pattern.compile("^\\s*import\\s+(.+)$");
    private static final Pattern PY_FROM = Pattern.compile("^\\s*from\\s+([\\w.]+)\\s+import\\s+(.+)$");
    private static final List<String> PY_MODULES = List.of("socket", "urllib", "http.client", "requests", "httpx",
            "ssl", "asyncio.streams", "aiohttp", "ftplib", "smtplib", "telnetlib", "xmlrpc");
    private static final List<String> ASYNCIO_NET_NAMES = List.of("open_connection", "start_server", "streams",
            "StreamReader", "StreamWriter", "open_unix_connection", "start_unix_server");

    private static Path root() {
        Path p = Path.of("..").toAbsolutePath().normalize();
        if (!Files.isDirectory(p.resolve("core")) || !Files.isDirectory(p.resolve("app"))) {
            p = Path.of("").toAbsolutePath().normalize(); // started from the project root
        }
        assertTrue(Files.isDirectory(p.resolve("core/src/main/java")), "cannot locate project root from " + p);
        return p;
    }

    private static List<Path> files(Path dir, String ext) throws IOException {
        assertTrue(Files.isDirectory(dir), "missing source tree " + dir);
        try (Stream<Path> s = Files.walk(dir)) {
            return s.filter(Files::isRegularFile).filter(p -> p.toString().endsWith(ext)).toList();
        }
    }

    private static String rel(Path root, Path p) {
        return root.relativize(p).toString().replace('\\', '/');
    }

    @Test
    void onlyHttpsTransportUsesJavaNetworkApis() throws IOException {
        Path root = root();
        List<String> violations = new ArrayList<>();
        int scanned = 0;
        for (String tree : List.of("core/src/main/java", "platform/src/main/java", "app/src/main/java")) {
            for (Path f : files(root.resolve(tree), ".java")) {
                scanned++;
                String r = rel(root, f);
                if (r.equals(ALLOWED_JAVA)) continue;
                String text = Files.readString(f, StandardCharsets.UTF_8);
                for (String bad : JAVA_FORBIDDEN) {
                    if (text.contains(bad)) violations.add(r + " contains \"" + bad + "\"");
                }
            }
        }
        assertTrue(scanned > 20, "suspiciously few Java files scanned: " + scanned);
        assertTrue(Files.isRegularFile(root.resolve(ALLOWED_JAVA)), "the allowed transport must exist");
        assertTrue(violations.isEmpty(), "Only the updater may use the network:\n" + String.join("\n", violations));
    }

    @Test
    void pythonWorkerDoesNotImportNetworkModules() throws IOException {
        Path root = root();
        List<String> violations = new ArrayList<>();
        List<Path> py = files(root.resolve("python/idlestow"), ".py");
        assertTrue(py.size() > 5, "suspiciously few Python files scanned: " + py.size());
        for (Path f : py) {
            List<String> lines = Files.readAllLines(f, StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size(); i++) {
                String bad = networkImport(lines.get(i));
                if (bad != null) violations.add(rel(root, f) + ":" + (i + 1) + " imports " + bad);
            }
        }
        assertTrue(violations.isEmpty(), "Only the updater may use the network:\n" + String.join("\n", violations));
    }

    /** Returns the offending module name if the line is a networking import, else null. */
    static String networkImport(String line) {
        String code = line.split("#", 2)[0];
        Matcher from = PY_FROM.matcher(code);
        if (from.matches()) {
            String mod = from.group(1);
            String names = from.group(2);
            if (isNetworkModule(mod)) return mod;
            if (mod.equals("http") && Pattern.compile("\\bclient\\b").matcher(names).find()) return "http.client";
            if (mod.equals("asyncio")) {
                for (String n : ASYNCIO_NET_NAMES) {
                    if (Pattern.compile("\\b" + n + "\\b").matcher(names).find()) return "asyncio." + n;
                }
            }
            return null;
        }
        Matcher imp = PY_IMPORT.matcher(code);
        if (imp.matches()) {
            for (String part : imp.group(1).split(",")) {
                String mod = part.trim().split("\\s+")[0];
                if (isNetworkModule(mod)) return mod;
            }
        }
        return null;
    }

    private static boolean isNetworkModule(String mod) {
        for (String m : PY_MODULES) {
            if (mod.equals(m) || mod.startsWith(m + ".")) return true;
        }
        return false;
    }

    @Test
    void importDetectorRecognisesNetworkImports() {
        assertNotNull(networkImport("import socket"));
        assertNotNull(networkImport("import os, socket"));
        assertNotNull(networkImport("    import urllib.request"));
        assertNotNull(networkImport("from urllib.request import urlopen"));
        assertNotNull(networkImport("import http.client as hc"));
        assertNotNull(networkImport("from http import client"));
        assertNotNull(networkImport("from asyncio import open_connection"));
        assertNotNull(networkImport("import requests"));
        assertNotNull(networkImport("import ssl  # tls"));
        assertNull(networkImport("import os"));
        assertNull(networkImport("import socketx"));
        assertNull(networkImport("# import socket"));
        assertNull(networkImport("from asyncio import sleep"));
        assertNull(networkImport("from http import HTTPStatus"));
        assertNull(networkImport("import json"));
    }
}
