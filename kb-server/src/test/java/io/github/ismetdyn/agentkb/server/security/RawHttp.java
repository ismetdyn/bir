package io.github.ismetdyn.agentkb.server.security;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * HTTP over a plain socket. {@link java.net.http.HttpClient} refuses {@code Host} unless a JVM-wide
 * flag is set at startup, and cannot send a duplicated or missing Host, HTTP/1.0 or an absolute
 * URI, so these bytes are written directly. {@code RawHttpWireTest} proves they are what the wire
 * and the server see.
 */
final class RawHttp {

    static final String HTTP_1_1 = "HTTP/1.1";
    static final String HTTP_1_0 = "HTTP/1.0";
    private static final String CRLF = "\r\n";
    private static final String INTERIM_CONTINUE = HTTP_1_1 + " 100 " + CRLF + CRLF;
    private static final int TIMEOUT_MILLIS = 20_000;
    private static final int BUFFER_BYTES = 64 * 1024;
    private static final Pattern CONTENT_LENGTH_HEADER =
            Pattern.compile("(?im)^content-length:\\s*(\\d+)");
    private static final Pattern CHUNKED_HEADER =
            Pattern.compile("(?im)^transfer-encoding:\\s*chunked");

    private RawHttp() {}

    record Reply(int status, Map<String, String> headers, String body) {

        boolean hasHeader(String name) {
            return headers.containsKey(name);
        }
    }

    /**
     * Sends exactly the given header lines plus {@code Content-Length} and {@code Connection:
     * close}.
     */
    static Reply send(
            int port,
            String version,
            String method,
            String target,
            String body,
            String... headerLines)
            throws IOException {
        StringBuilder request = new StringBuilder(method + " " + target + " " + version + CRLF);
        for (String headerLine : headerLines) {
            request.append(headerLine).append(CRLF);
        }
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        request.append("Content-Length: ").append(payload.length).append(CRLF);
        request.append("Connection: close").append(CRLF).append(CRLF);

        try (Socket socket = new Socket()) {
            socket.setSoTimeout(TIMEOUT_MILLIS);
            socket.connect(
                    new InetSocketAddress(InetAddress.getLoopbackAddress(), port), TIMEOUT_MILLIS);
            socket.getOutputStream()
                    .write(request.toString().getBytes(StandardCharsets.ISO_8859_1));
            socket.getOutputStream().write(payload);
            socket.getOutputStream().flush();
            return parse(socket.getInputStream().readAllBytes());
        }
    }

    /**
     * POSTs {@code start} padded with spaces to exactly {@code length} bytes, which stays valid
     * JSON and needs no memory. The body goes out on its own thread while the reply is read: a
     * server refusing it stops reading, and the two ends would otherwise wait on each other. A
     * write that fails because of that refusal is ignored; the reply decides.
     */
    static Reply sendPadded(
            int port,
            Framing framing,
            String target,
            String start,
            long length,
            String... headerLines)
            throws IOException {
        StringBuilder request = new StringBuilder("POST " + target + " " + HTTP_1_1 + CRLF);
        for (String headerLine : headerLines) {
            request.append(headerLine).append(CRLF);
        }
        request.append(framing.headerLine(length)).append(CRLF);
        request.append("Connection: close").append(CRLF).append(CRLF);

        try (Socket socket = new Socket()) {
            socket.setSoTimeout(TIMEOUT_MILLIS);
            socket.connect(
                    new InetSocketAddress(InetAddress.getLoopbackAddress(), port), TIMEOUT_MILLIS);
            OutputStream out = socket.getOutputStream();
            out.write(request.toString().getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            Thread.ofVirtual().start(() -> writeIgnoringRefusal(framing, out, start, length));
            return parse(readUntil(socket.getInputStream(), RawHttp::isCompleteReply));
        }
    }

    /**
     * Writes {@code bytes}, padded with spaces to {@code length}, with no framing of its own: what
     * follows a short Content-Length is parsed by the server as the next request. Returns
     * everything until the server closes.
     */
    static String exchange(int port, String bytes, long length) throws IOException {
        try (Socket socket = new Socket()) {
            socket.setSoTimeout(TIMEOUT_MILLIS);
            socket.connect(
                    new InetSocketAddress(InetAddress.getLoopbackAddress(), port), TIMEOUT_MILLIS);
            OutputStream out = socket.getOutputStream();
            Thread.ofVirtual()
                    .start(() -> writeIgnoringRefusal(Framing.CONTENT_LENGTH, out, bytes, length));
            return new String(
                    readUntil(socket.getInputStream(), raw -> false), StandardCharsets.ISO_8859_1);
        }
    }

    private static void writeIgnoringRefusal(
            Framing framing, OutputStream out, String start, long length) {
        try {
            framing.writeBody(out, start.getBytes(StandardCharsets.UTF_8), length);
            out.flush();
        } catch (IOException e) {
            // The server closed the connection; whether that was right is for the caller to judge
            // from the reply.
        }
    }

    /**
     * {@code isComplete} ends the read early: a server may keep the connection open for body bytes
     * that never come.
     */
    private static byte[] readUntil(InputStream in, Predicate<ByteArrayOutputStream> isComplete)
            throws IOException {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        byte[] buffer = new byte[BUFFER_BYTES];
        try {
            int read;
            while (!isComplete.test(raw) && (read = in.read(buffer)) != -1) {
                raw.write(buffer, 0, read);
            }
        } catch (SocketTimeoutException e) {
            throw new SocketTimeoutException(
                    "no complete reply within " + TIMEOUT_MILLIS + " ms, got: " + raw);
        } catch (SocketException e) {
            // A reset after refusing a body is normal; parse rejects the reply if it did not arrive
            // whole.
            return raw.toByteArray();
        }
        return raw.toByteArray();
    }

    private static boolean isCompleteReply(ByteArrayOutputStream raw) {
        String text = withoutInterimReply(raw.toString(StandardCharsets.ISO_8859_1));
        int headerEnd = text.indexOf(CRLF + CRLF);
        if (headerEnd < 0) {
            return false;
        }
        String head = text.substring(0, headerEnd);
        int bodyLength = text.length() - headerEnd - (CRLF + CRLF).length();
        Matcher contentLength = CONTENT_LENGTH_HEADER.matcher(head);
        if (contentLength.find()) {
            return bodyLength >= Integer.parseInt(contentLength.group(1));
        }
        return CHUNKED_HEADER.matcher(head).find() && text.endsWith("0" + CRLF + CRLF);
    }

    /** How {@link #sendPadded} frames the body; some framings deliberately leave it unfinished. */
    enum Framing {
        /**
         * Declares the length and sends nothing, so a reply cannot depend on having read the body.
         */
        DECLARED_ONLY {
            @Override
            void writeBody(OutputStream out, byte[] start, long length) {
                // Nothing is sent on purpose.
            }
        },
        CONTENT_LENGTH {
            @Override
            void writeBody(OutputStream out, byte[] start, long length) throws IOException {
                out.write(start);
                writeSpaces(length - start.length, out::write);
            }
        },
        CHUNKED {
            @Override
            String headerLine(long length) {
                return CHUNKED_ENCODING;
            }

            @Override
            void writeBody(OutputStream out, byte[] start, long length) throws IOException {
                CHUNKED_UNTERMINATED.writeBody(out, start, length);
                out.write(("0" + CRLF + CRLF).getBytes(StandardCharsets.ISO_8859_1));
            }
        },
        /** Omits the terminal chunk, so a reply cannot wait for the end of the body. */
        CHUNKED_UNTERMINATED {
            @Override
            String headerLine(long length) {
                return CHUNKED_ENCODING;
            }

            @Override
            void writeBody(OutputStream out, byte[] start, long length) throws IOException {
                writeChunk(out, start, start.length);
                writeSpaces(
                        length - start.length,
                        (bytes, offset, count) -> writeChunk(out, bytes, count));
            }
        },
        /** A chunk size that is not hexadecimal. */
        CHUNKED_MALFORMED {
            @Override
            String headerLine(long length) {
                return CHUNKED_ENCODING;
            }

            @Override
            void writeBody(OutputStream out, byte[] start, long length) throws IOException {
                out.write(("zz" + CRLF).getBytes(StandardCharsets.ISO_8859_1));
                out.write(start);
                out.write(CRLF.getBytes(StandardCharsets.ISO_8859_1));
            }
        };

        private static final String CHUNKED_ENCODING = "Transfer-Encoding: chunked";

        String headerLine(long length) {
            return "Content-Length: " + length;
        }

        abstract void writeBody(OutputStream out, byte[] start, long length) throws IOException;

        private static void writeChunk(OutputStream out, byte[] bytes, int count)
                throws IOException {
            out.write((Integer.toHexString(count) + CRLF).getBytes(StandardCharsets.ISO_8859_1));
            out.write(bytes, 0, count);
            out.write(CRLF.getBytes(StandardCharsets.ISO_8859_1));
        }

        private static void writeSpaces(long total, BytesSink sink) throws IOException {
            byte[] spaces = new byte[BUFFER_BYTES];
            Arrays.fill(spaces, (byte) ' ');
            for (long left = total; left > 0; left -= spaces.length) {
                sink.accept(spaces, 0, (int) Math.min(left, spaces.length));
            }
        }

        @FunctionalInterface
        private interface BytesSink {
            void accept(byte[] bytes, int offset, int count) throws IOException;
        }
    }

    /**
     * The reply to {@code Expect: 100-continue} comes before the real one, whose status is what the
     * callers judge.
     */
    private static String withoutInterimReply(String text) {
        return text.startsWith(INTERIM_CONTINUE) ? text.substring(INTERIM_CONTINUE.length()) : text;
    }

    private static Reply parse(byte[] raw) {
        String text = withoutInterimReply(new String(raw, StandardCharsets.ISO_8859_1));
        int headerEnd = text.indexOf(CRLF + CRLF);
        if (headerEnd < 0) {
            throw new IllegalStateException("no complete HTTP response: " + text);
        }
        String[] lines = text.substring(0, headerEnd).split(CRLF);
        Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(':');
            headers.put(lines[i].substring(0, colon), lines[i].substring(colon + 1).strip());
        }
        String body = text.substring(headerEnd + (CRLF + CRLF).length());
        if ("chunked".equals(headers.get("Transfer-Encoding"))) {
            body = dechunk(body);
        }
        return new Reply(Integer.parseInt(lines[0].split(" ")[1]), headers, body);
    }

    private static String dechunk(String chunked) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        int position = 0;
        while (true) {
            int lineEnd = chunked.indexOf(CRLF, position);
            int size = Integer.parseInt(chunked.substring(position, lineEnd), 16);
            if (size == 0) {
                return body.toString(StandardCharsets.ISO_8859_1);
            }
            int start = lineEnd + CRLF.length();
            body.writeBytes(
                    chunked.substring(start, start + size).getBytes(StandardCharsets.ISO_8859_1));
            position = start + size + CRLF.length();
        }
    }
}
