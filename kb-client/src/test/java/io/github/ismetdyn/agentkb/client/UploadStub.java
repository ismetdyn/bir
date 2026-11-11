package io.github.ismetdyn.agentkb.client;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.IntConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * A loopback stand-in for the server's upload protocol (ADR-0025 decision 1, docs/CLIENTS.md §1):
 * {@code PUT /api/v1/uploads/{sha}} with Upload-Length answers the bytes held so far, {@code PATCH}
 * appends at Upload-Offset, {@code POST /api/v1/documents} checks the sha256 of what arrived. It
 * keeps its state across kb runs, as the server does, so a second run shows whether kb resumes from
 * the server's offset. It can drop the connection in the middle of one PATCH, run a hook before a
 * PATCH (to change the file under kb) and refuse a large file without git-lfs at PUT. {@link #file}
 * writes a test file of random bytes.
 */
final class UploadStub implements AutoCloseable {

    static final String UPLOADS = "/api/v1/uploads/";
    static final String DOCUMENTS = "/api/v1/documents";
    static final String BLOB_DIR = "/home/kbtest/kb/blobs/";

    private static final Pattern SHA256 = Pattern.compile("\"sha256\"\\s*:\\s*\"([0-9a-f]{64})\"");
    private static final int NO_BODY = -1;

    /** One request: method, path, its upload headers (null if not sent) and its body. */
    record Request(
            String method,
            String path,
            @Nullable String uploadLength,
            @Nullable String uploadOffset,
            @Nullable String contentType,
            byte[] body) {

        String text() {
            return new String(body, StandardCharsets.UTF_8);
        }
    }

    private final HttpServer server;
    private final List<Request> requests = new CopyOnWriteArrayList<>();
    private final Map<String, ByteArrayOutputStream> staged = new HashMap<>();
    private final Map<String, Long> lengths = new HashMap<>();
    private final Set<String> stored = new HashSet<>();
    private final int dropAtPatch;
    private final IntConsumer beforePatch;
    private final long lfsThreshold;
    private int patches;

    /** A stub that takes every upload. */
    UploadStub() throws IOException {
        this(0, n -> {}, Long.MAX_VALUE);
    }

    /**
     * @param dropAtPatch the PATCH (from 1, over the stub's life) that keeps half its body, then
     *     drops the connection; 0 for none
     * @param beforePatch gets the number of each PATCH before the stub reads it
     * @param lfsThreshold a PUT with an Upload-Length above it is refused with 422: no git-lfs
     */
    UploadStub(int dropAtPatch, IntConsumer beforePatch, long lfsThreshold) throws IOException {
        this.dropAtPatch = dropAtPatch;
        this.beforePatch = beforePatch;
        this.lfsThreshold = lfsThreshold;
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    /** The requests of {@code method} so far, in order. */
    List<Request> requests(String method) {
        return requests.stream().filter(r -> r.method().equals(method)).toList();
    }

    KbProcess.Result kb(Path workDir, String... args) {
        return KbProcess.run(
                "http://127.0.0.1:" + server.getAddress().getPort(), "", workDir, args);
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** {@code dir/name} holding {@code size} random bytes, the same for the same size. */
    static Path file(Path dir, String name, int size) throws IOException {
        byte[] bytes = new byte[size];
        new Random(size).nextBytes(bytes);
        return Files.write(dir.resolve(name), bytes);
    }

    private synchronized void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getRawPath();
            if ("PATCH".equals(method)) {
                beforePatch.accept(++patches);
            }
            byte[] body = exchange.getRequestBody().readAllBytes();
            requests.add(
                    new Request(
                            method,
                            path,
                            exchange.getRequestHeaders().getFirst("Upload-Length"),
                            exchange.getRequestHeaders().getFirst("Upload-Offset"),
                            exchange.getRequestHeaders().getFirst("Content-Type"),
                            body));
            if (path.startsWith(UPLOADS) && "PUT".equals(method)) {
                start(exchange, path.substring(UPLOADS.length()));
            } else if (path.startsWith(UPLOADS) && "PATCH".equals(method)) {
                append(exchange, path.substring(UPLOADS.length()), body);
            } else if (path.equals(DOCUMENTS) && "POST".equals(method)) {
                complete(exchange, new String(body, StandardCharsets.UTF_8));
            } else {
                exchange.sendResponseHeaders(404, NO_BODY);
            }
        }
    }

    private void start(HttpExchange exchange, String sha) throws IOException {
        long length = Long.parseLong(exchange.getRequestHeaders().getFirst("Upload-Length"));
        if (stored.contains(sha)) {
            answer(exchange, 200, length, "");
        } else if (length > lfsThreshold) {
            answer(
                    exchange,
                    422,
                    null,
                    "{\"error\":\"a file of this size needs git-lfs, which is not installed\","
                            + "\"hint\":\"Install git-lfs (kb doctor checks it) and retry.\"}");
        } else if (staged.containsKey(sha)) {
            answer(exchange, 200, (long) staged.get(sha).size(), "");
        } else {
            staged.put(sha, new ByteArrayOutputStream());
            lengths.put(sha, length);
            answer(exchange, 201, 0L, "");
        }
    }

    private void append(HttpExchange exchange, String sha, byte[] body) throws IOException {
        ByteArrayOutputStream bytes = staged.get(sha);
        long offset = Long.parseLong(exchange.getRequestHeaders().getFirst("Upload-Offset"));
        if (bytes == null) {
            answer(exchange, 404, null, "{\"error\":\"no upload with this id\",\"hint\":\"PUT\"}");
            return;
        }
        if (offset != bytes.size()) {
            answer(exchange, 409, (long) bytes.size(), "{\"error\":\"offset\",\"hint\":\"PUT\"}");
            return;
        }
        if (patches == dropAtPatch) {
            // The server streams the body to disk: what arrived before the break stays.
            bytes.write(body, 0, body.length / 2);
            return; // closed without an answer: the connection drops
        }
        bytes.write(body);
        answer(exchange, 204, (long) bytes.size(), "");
    }

    private void complete(HttpExchange exchange, String body) throws IOException {
        Matcher sha = SHA256.matcher(body);
        if (!sha.find()) {
            answer(exchange, 400, null, "{\"error\":\"sha256\",\"hint\":\"send it\"}");
            return;
        }
        String id = sha.group(1);
        String added =
                "{\"id\":\"doc:" + id.substring(0, 12) + "\",\"path\":\"" + BLOB_DIR + id + "\"}";
        if (stored.contains(id)) {
            answer(exchange, 200, null, added);
            return;
        }
        ByteArrayOutputStream bytes = staged.get(id);
        if (bytes == null) {
            answer(exchange, 404, null, "{\"error\":\"no upload\",\"hint\":\"PUT\"}");
        } else if (bytes.size() != lengths.get(id)) {
            answer(exchange, 409, null, "{\"error\":\"incomplete\",\"hint\":\"PUT\"}");
        } else if (!sha256(bytes.toByteArray()).equals(id)) {
            staged.remove(id);
            answer(
                    exchange,
                    422,
                    null,
                    "{\"error\":\"the uploaded bytes do not have this sha256; the upload was"
                            + " removed\",\"hint\":\"Upload the file again with PUT and PATCH.\"}");
        } else {
            staged.remove(id);
            stored.add(id);
            answer(exchange, 201, null, added);
        }
    }

    private static void answer(
            HttpExchange exchange, int status, @Nullable Long offset, String json)
            throws IOException {
        if (offset != null) {
            exchange.getResponseHeaders().add("Upload-Offset", Long.toString(offset));
        }
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 0) {
            exchange.getResponseHeaders().add("Content-Type", "application/json");
        }
        exchange.sendResponseHeaders(status, bytes.length == 0 ? NO_BODY : bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
