package io.github.ismetdyn.agentkb.client;

import io.github.ismetdyn.agentkb.api.DocumentAnswer;
import io.github.ismetdyn.agentkb.api.DocumentListAnswer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.concurrent.Callable;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/**
 * {@code kb doc add|list|show} (docs/CLIENTS.md §2, plan.md H7): the document store. The server
 * checks, scans and stores; kb only does what touches this machine (ARCHITECTURE §1): the sha256 of
 * the file and its upload in parts. Exit codes 0, 1 refused, bad input or a file that changed under
 * the upload, 3 server unreachable.
 */
final class DocCommands {

    private DocCommands() {}

    @Command(
            name = "doc",
            description = "Add, list and show documents.",
            subcommands = {Doc.Add.class, Doc.ListDocs.class, Doc.Show.class})
    static final class Doc {

        private Doc() {}

        /**
         * The resumable upload of ADR-0025 decision 1: {@code PUT /api/v1/uploads/{sha256}} with
         * Upload-Length answers the bytes the server holds, each {@code PATCH} sends at most 1 MiB
         * from there, {@code POST /api/v1/documents} completes. kb keeps no state: a rerun learns
         * the offset again from PUT, so a broken upload resumes where the server stopped. The
         * server checks the bytes against the sha256 at completion (422 if the file changed).
         */
        @Command(
                name = "add",
                description =
                        "Add a file to the document store; run it again to resume a broken"
                                + " upload. A compressed file (PDF, zip, docx) is not scanned for"
                                + " secrets; kb warns.")
        static final class Add implements Callable<Integer> {

            /** ADR-0022 decision 17: a request body is at most 1 MiB. */
            private static final int PART_BYTES = 1024 * 1024;

            private static final String UPLOAD_OFFSET = "Upload-Offset";
            private static final int CREATED = 201;

            /**
             * ADR-0025 decision 3: the server scans text, not what is packed inside these. The
             * leading bytes of zip (docx, xlsx), gzip, PDF, 7z, xz, zstd and bzip2.
             */
            private static final List<byte[]> COMPRESSED =
                    List.of(
                                    "504b0304",
                                    "1f8b",
                                    "25504446",
                                    "377abcaf271c",
                                    "fd377a585a00",
                                    "28b52ffd",
                                    "425a68")
                            .stream()
                            .map(HexFormat.of()::parseHex)
                            .toList();

            private static final int MAGIC_BYTES = 6;

            /**
             * The slowest hash and secret scan of a completion kb waits for, in bytes per second
             * (assumed, not measured: a floor far below a disk read, so only a hung server times
             * out).
             */
            private static final long COMPLETION_BYTES_PER_SECOND = 1024 * 1024;

            /** A part or the completion broke or timed out after the PUT answered (exit 3). */
            private static final String LOST =
                    "connection lost during upload; run the same command again to resume";

            /** {@code compressed}: the file starts with one of {@link #COMPRESSED}. */
            private record Content(String sha256, long size, boolean compressed) {}

            // Text, not Path: picocli converts a Path by reflection (see ScanCommand).
            @Parameters(paramLabel = "<file>", description = "The file.")
            String file;

            @Option(
                    names = "--name",
                    paramLabel = "<name>",
                    description = "Its name; the file name if left out.")
            String name;

            @Option(names = "--about", paramLabel = "<id>", description = "What it is about.")
            String about;

            @Option(names = "--origin", paramLabel = "<url>", description = "Where it came from.")
            String origin;

            @Option(
                    names = "--replaces",
                    paramLabel = "<docId>",
                    description = "The document it replaces.")
            String replaces;

            @Spec CommandSpec spec;

            @Override
            public Integer call() {
                Path path;
                try {
                    path = Path.of(file);
                } catch (InvalidPathException e) {
                    throw new KbException("not a valid path: " + shown());
                }
                // CWE-835: a FIFO or a device would block the hash or never end.
                if (!Files.isRegularFile(path)) {
                    throw new KbException(
                            (Files.exists(path) ? "not a regular file: " : "no such file: ")
                                    + shown());
                }
                Content content = hash(path);
                String server = SecretApi.serverUrl();
                upload(
                        new JsonApi(server),
                        new JsonApi(server, JsonApi.REQUEST_TIMEOUT, LOST),
                        path,
                        content);
                JsonApi.Reply added =
                        new JsonApi(server, completionTimeout(content.size()), LOST)
                                .request(
                                        "POST",
                                        "/api/v1/documents",
                                        Map.of("Content-Type", "application/json"),
                                        completion(content.sha256(), path)
                                                .getBytes(StandardCharsets.UTF_8));
                Json.Fields document = Json.Fields.of(added.body(), "the document answer");
                spec.commandLine()
                        .getOut()
                        .println(
                                AnswerText.line(
                                        added.status() == CREATED ? "added" : "already stored",
                                        document.text("id"),
                                        document.text("path")));
                if (content.compressed()) {
                    spec.commandLine()
                            .getErr()
                            .println(
                                    "kb: warning: "
                                            + shown()
                                            + " is compressed or a container (zip, gzip, PDF, 7z,"
                                            + " xz, zstd, bzip2); content inside it was not"
                                            + " scanned for secrets");
                }
                return 0;
            }

            /**
             * How long POST /documents may take: the server hashes and scans the whole file before
             * it answers, so the usual request timeout plus a second per {@link
             * #COMPLETION_BYTES_PER_SECOND}. Only a hang stopper, not a deadline.
             */
            static Duration completionTimeout(long size) {
                return JsonApi.REQUEST_TIMEOUT.plusSeconds(size / COMPLETION_BYTES_PER_SECOND);
            }

            /** The file argument as kb may echo it: one line, no control characters (CWE-150). */
            private String shown() {
                return AnswerText.oneLine((String) ServerText.printable(file));
            }

            /** The file's sha256 and size, read as a stream: a large file never sits in memory. */
            private Content hash(Path path) {
                try {
                    MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
                    byte[] head;
                    long size;
                    try (InputStream in =
                            new DigestInputStream(Files.newInputStream(path), sha256)) {
                        head = in.readNBytes(MAGIC_BYTES);
                        size = head.length + in.transferTo(OutputStream.nullOutputStream());
                    }
                    boolean compressed =
                            COMPRESSED.stream()
                                    .anyMatch(
                                            magic ->
                                                    head.length >= magic.length
                                                            && Arrays.equals(
                                                                    head,
                                                                    0,
                                                                    magic.length,
                                                                    magic,
                                                                    0,
                                                                    magic.length));
                    return new Content(HexFormat.of().formatHex(sha256.digest()), size, compressed);
                } catch (NoSuchFileException e) {
                    throw new KbException("no such file: " + shown());
                } catch (IOException e) {
                    throw new KbException("cannot read " + shown());
                } catch (NoSuchAlgorithmException e) {
                    throw new IllegalStateException("every JDK has SHA-256", e);
                }
            }

            /**
             * PUT through {@code start}, then a PATCH per part through {@code parts} (where a
             * broken connection is a lost upload) from the offset the server answers, to the size.
             */
            private void upload(JsonApi start, JsonApi parts, Path path, Content content) {
                long size = content.size();
                String upload = "/api/v1/uploads/" + content.sha256();
                long offset =
                        offset(
                                start.request(
                                        "PUT",
                                        upload,
                                        Map.of("Upload-Length", Long.toString(size)),
                                        new byte[0]),
                                size);
                try (SeekableByteChannel channel = Files.newByteChannel(path)) {
                    while (offset < size) {
                        byte[] part =
                                read(channel, offset, (int) Math.min(PART_BYTES, size - offset));
                        Map<String, String> headers =
                                Map.of(
                                        UPLOAD_OFFSET,
                                        Long.toString(offset),
                                        "Content-Type",
                                        "application/offset+octet-stream");
                        long next = offset(parts.request("PATCH", upload, headers, part), size);
                        if (next <= offset) {
                            throw new KbException(
                                    "the server took no byte of the part at " + offset);
                        }
                        offset = next;
                    }
                } catch (IOException e) {
                    throw new KbException("cannot read " + shown());
                }
            }

            /** {@code length} bytes at {@code offset}; fewer means the file shrank. */
            private byte[] read(SeekableByteChannel channel, long offset, int length)
                    throws IOException {
                ByteBuffer part = ByteBuffer.allocate(length);
                channel.position(offset);
                while (part.hasRemaining()) {
                    if (channel.read(part) < 0) {
                        throw new KbException(
                                shown()
                                        + " changed while it was uploaded (it is shorter now);"
                                        + " run kb doc add again");
                    }
                }
                return part.array();
            }

            /** The Upload-Offset of a reply; anything but a byte count up to {@code size} fails. */
            private static long offset(JsonApi.Reply reply, long size) {
                long offset;
                try {
                    offset = Long.parseLong(reply.headers().firstValue(UPLOAD_OFFSET).orElse(""));
                } catch (NumberFormatException e) {
                    offset = -1;
                }
                if (offset < 0 || offset > size) {
                    throw new KbException(
                            "the server answered without a valid Upload-Offset; are kb and the"
                                    + " server of the same version?");
                }
                return offset;
            }

            /** The POST /documents body; an option left out is not sent. */
            private String completion(String sha, Path path) {
                StringJoiner json = new StringJoiner(", ", "{", "}");
                field(json, "sha256", sha);
                field(json, "name", name != null ? name : String.valueOf(path.getFileName()));
                field(json, "about", about);
                field(json, "origin", origin);
                field(json, "replaces", replaces);
                return json.toString();
            }

            private static void field(StringJoiner json, String key, @Nullable String value) {
                if (value != null) {
                    json.add("\"" + key + "\": " + RequestText.quote(value));
                }
            }
        }

        @Command(name = "list", description = "List the documents, newest first.")
        static final class ListDocs implements Callable<Integer> {

            @Option(
                    names = "--limit",
                    paramLabel = "<n>",
                    description = "At most n documents; server default if left out.")
            Integer limit;

            @Spec CommandSpec spec;

            @Override
            public Integer call() {
                DocumentListAnswer answer =
                        Json.toRecord(
                                JsonApi.fromEnvironment()
                                        .get(
                                                RequestText.query(
                                                        "/api/v1/documents", "limit", limit)),
                                DocumentListAnswer.class);
                PrintWriter out = spec.commandLine().getOut();
                if (answer.documents().isEmpty() && !answer.more()) {
                    out.println("no documents");
                }
                for (DocumentAnswer document : answer.documents()) {
                    out.println(
                            AnswerText.line(
                                    document.id(),
                                    document.name(),
                                    bytes(document.size()),
                                    document.addedAt()));
                }
                if (answer.more()) {
                    out.println("more documents not shown");
                }
                return 0;
            }
        }

        @Command(
                name = "show",
                description =
                        "Show a document: its metadata, the path of its file, what it is about.")
        static final class Show implements Callable<Integer> {

            @Parameters(paramLabel = "<id>", description = "The document, e.g. doc:ded48257b6f3.")
            String id;

            @Spec CommandSpec spec;

            @Override
            public Integer call() {
                DocumentAnswer document =
                        Json.toRecord(
                                JsonApi.fromEnvironment()
                                        .get("/api/v1/documents/" + RequestText.segment(id)),
                                DocumentAnswer.class);
                PrintWriter out = spec.commandLine().getOut();
                out.println(AnswerText.line(document.id(), document.name()));
                labelled(out, "sha256", document.sha256());
                labelled(out, "mime", document.mime());
                labelled(out, "size", bytes(document.size()));
                labelled(out, "origin", document.origin());
                labelled(out, "added", document.addedAt());
                labelled(out, "path", document.path());
                AnswerText.items(out, "about", document.about());
                return 0;
            }

            /** {@code label: value} on one line; nothing for a value the server did not have. */
            private static void labelled(PrintWriter out, String label, @Nullable String value) {
                if (value != null) {
                    out.println(AnswerText.oneLine(label + ": " + value));
                }
            }
        }

        private static @Nullable String bytes(@Nullable Long size) {
            return size == null ? null : size + " bytes";
        }
    }
}
