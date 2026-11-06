package io.github.ismetdyn.agentkb.server.scan;

import io.github.ismetdyn.agentkb.server.scan.extract.Ansible;
import io.github.ismetdyn.agentkb.server.scan.extract.BuildFile;
import io.github.ismetdyn.agentkb.server.scan.extract.Compose;
import io.github.ismetdyn.agentkb.server.scan.extract.Deployments;
import io.github.ismetdyn.agentkb.server.scan.extract.DotEnv;
import io.github.ismetdyn.agentkb.server.scan.extract.Extraction;
import io.github.ismetdyn.agentkb.server.scan.extract.K8s;
import io.github.ismetdyn.agentkb.server.scan.extract.SpringConfig;
import io.github.ismetdyn.agentkb.server.write.ScanResult;
import io.github.ismetdyn.agentkb.server.write.ScanWrite;
import io.github.ismetdyn.agentkb.server.write.ScanWrite.Found;
import io.github.ismetdyn.agentkb.server.write.ScanWrite.FoundSecret;
import io.github.ismetdyn.agentkb.server.write.ScanWrite.ScannedFile;
import io.github.ismetdyn.agentkb.server.write.WriteRejectedException;
import io.github.ismetdyn.agentkb.server.write.WriteService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * The deterministic scanner (ARCHITECTURE §6, ADR-0008): allowed-roots check, git (remote, HEAD,
 * tracked files), file discovery that follows .gitignore but still reads .env files, the
 * extractors, then one {@link WriteService#scan} with source scan (invariant 1). It reads only
 * under {@code agentkb.scan.allowed-roots}, symlinks resolved and the opened file checked ({@link
 * RootedFiles}), working tree and git dir included (invariant 9), never calls an LLM or the network
 * (invariant 3), and logs counts only (invariant 10).
 */
@Component
@EnableConfigurationProperties(ScanProperties.class)
public class ScannerService {

    private static final Logger LOG = LoggerFactory.getLogger(ScannerService.class);

    // ARCHITECTURE §14 agentkb.scan.max-file-bytes; a config file is a few kilobytes.
    static final long MAX_FILE_BYTES = 1_048_576;
    private static final String ROOTS_HINT =
            "Pass the absolute path of a git working copy under agentkb.scan.allowed-roots; to scan"
                    + " another folder, ask the user to add it there.";
    private static final String FINDING = "finding";
    // User-facing data, not code text: it must equal seed/make_seed.py's finding so a rescan of
    // the seeded repository refreshes it (fixtures/scanner/expected.yaml merge_with_seed).
    // %s, not %d: the line number stays ASCII digits in every default locale.
    private static final String FINDING_TEXT =
            "Düz metin parola bulundu: %s:%s; ortam değişkenine taşınmalı";

    private final WriteService writes;
    private final ScanProperties properties;

    public ScannerService(WriteService writes, ScanProperties properties) {
        this.writes = writes;
        this.properties = properties;
    }

    /** Reads one file into an Extraction; the name is the file name without its folder. */
    @FunctionalInterface
    private interface Extractor {
        void extract(String fileName, String text, Extraction out);
    }

    /**
     * @throws WriteRejectedException if the path is refused (not absolute, missing, not a folder,
     *     outside the allowed roots, not a git working copy); nothing was read or written
     */
    public ScanResult scan(Path path) {
        return scan(path, null);
    }

    /**
     * {@link #scan(Path)} stamped with {@code episode}, or with an Episode of its own if that names
     * none (ADR-0023 decision 3).
     */
    public ScanResult scan(Path path, @Nullable String episode) {
        long started = System.nanoTime();
        List<Path> roots = roots();
        Path dir = allowed(path, roots);
        LocalGit git = new LocalGit(dir);
        Path top =
                git.topLevel()
                        .orElseThrow(
                                () ->
                                        new WriteRejectedException(
                                                "the path is not in a git working copy",
                                                "Pass the folder of a git repository; kb_scan"
                                                        + " reads what git tracks there."));
        // CP5 decision 4: reads stay in this working copy, so a tracked symlink cannot pull in a
        // file of another repository under the same root.
        List<Path> workingCopy = List.of(insideRoots(git, top, roots));
        Optional<String> remote = git.remote().map(RemoteUrl::normalize);
        String folder = RemoteUrl.folder(dir);
        String repo = RemoteUrl.repoId(remote, folder);
        String project = isSame(dir, top) ? repo.substring(repo.lastIndexOf('/') + 1) : folder;

        Set<String> tracked = new HashSet<>(git.files("--cached"));
        Set<String> paths =
                new LinkedHashSet<>(git.files("--cached", "--others", "--exclude-standard"));
        // .gitignore keeps .env out of git, which is why the scanner has to read it (ARCHITECTURE
        // §6).
        git.files("--others", "--ignored", "--exclude-standard", "--directory").stream()
                .filter(file -> DotEnv.reads(file.substring(file.lastIndexOf('/') + 1)))
                .forEach(paths::add);
        Map<String, Extraction> extracted = new LinkedHashMap<>();
        List<String> skipped = new ArrayList<>();
        for (String file : paths) {
            read(top, file, workingCopy, skipped).ifPresent(out -> extracted.put(file, out));
        }
        Deployments.derive(extracted.values(), project);
        List<ScannedFile> files = new ArrayList<>();
        extracted.forEach(
                (file, out) -> {
                    // A file with nothing found is not reported, so its earlier facts close.
                    if (!out.facts().isEmpty() || !out.secrets().isEmpty()) {
                        ScannedFile found =
                                new ScannedFile(file, out.facts(), out.links(), out.secrets());
                        files.add(withFindings(found, tracked.contains(file)));
                    }
                });
        ScanResult result =
                writes.scan(
                        new ScanWrite(
                                project,
                                repo,
                                remote.orElse(null),
                                git.head().orElse(null),
                                files,
                                skipped,
                                Instant.now()),
                        episode);
        LOG.info(
                "event=scan_done files={} skipped={} added={} refreshed={} closed={} conflicts={}"
                        + " duration_ms={}",
                result.files(),
                result.skipped().size(),
                result.added(),
                result.refreshed(),
                result.closed(),
                result.conflicts(),
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
        return result;
    }

    /** The real paths of the allowed roots; refused if none exists. */
    List<Path> roots() {
        List<Path> roots = new ArrayList<>();
        for (Path root : properties.allowedRoots()) {
            try {
                roots.add(root.toRealPath());
            } catch (IOException e) {
                // A configured root that does not exist (yet) allows nothing.
                LOG.debug("event=scan_root_missing error={}", e.getClass().getName());
            }
        }
        if (roots.isEmpty()) {
            throw new WriteRejectedException(
                    "no folder may be scanned on this machine",
                    "Ask the user to set agentkb.scan.allowed-roots to the folders that hold"
                            + " their repositories.");
        }
        return roots;
    }

    /** The real path of {@code path}; symlinks and {@code ..} resolved before the root check. */
    static Path allowed(Path path, List<Path> roots) {
        if (!path.isAbsolute()) {
            throw new WriteRejectedException("the path is not absolute", ROOTS_HINT);
        }
        Path real;
        try {
            real = path.toRealPath();
        } catch (IOException e) {
            throw new WriteRejectedException(
                    "the path does not exist or cannot be read", ROOTS_HINT);
        }
        if (!Files.isDirectory(real)) {
            throw new WriteRejectedException("the path is not a folder", ROOTS_HINT);
        }
        if (!RootedFiles.isUnder(real, roots)) {
            throw new WriteRejectedException(
                    "the path is outside the folders the scanner may read", ROOTS_HINT);
        }
        return real;
    }

    /**
     * Invariant 9: core.worktree or a {@code .git} file's gitdir can put the working tree or the
     * git dir (remote, index) anywhere; the scan reads them only when they too are under the roots.
     *
     * @return the real path of the working tree
     */
    static Path insideRoots(LocalGit git, Path top, List<Path> roots) {
        List<Path> gitDirs = git.gitDirs();
        Optional<Path> realTop = realPath(top).filter(real -> RootedFiles.isUnder(real, roots));
        if (gitDirs.isEmpty()
                || realTop.isEmpty()
                || !gitDirs.stream().allMatch(path -> RootedFiles.isRealUnder(path, roots))) {
            throw new WriteRejectedException(
                    "the git working tree or git folder of the path is outside the folders the"
                            + " scanner may read",
                    ROOTS_HINT);
        }
        return realTop.get();
    }

    private static Optional<Path> realPath(Path path) {
        try {
            return Optional.of(path.toRealPath());
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private static boolean isSame(Path dir, Path top) {
        try {
            return dir.equals(top.toRealPath());
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * One file through its extractor, or empty if no extractor reads it. A file that cannot be read
     * safely is skipped and named in {@code skipped}, never its content.
     */
    private Optional<Extraction> read(
            Path top, String path, List<Path> roots, List<String> skipped) {
        String name = path.substring(path.lastIndexOf('/') + 1);
        Optional<Extractor> extractor = extractor(name);
        // Test resources configure tests, not the systems the project runs against.
        if (extractor.isEmpty() || path.startsWith("src/test/") || path.contains("/src/test/")) {
            return Optional.empty();
        }
        String text = RootedFiles.text(top.resolve(path), path, roots, skipped);
        if (text == null) {
            return Optional.empty();
        }
        Extraction out = new Extraction(properties.internalDomains());
        try {
            extractor.get().extract(name, text, out);
        } catch (RuntimeException e) {
            // A broken file is the repository's problem, not the scan's; the rest goes on.
            LOG.warn("event=scan_file_unparsed error={}", e.getClass().getName());
            skipped.add(path + ": cannot be parsed");
            return Optional.empty();
        }
        return Optional.of(out);
    }

    // The first that reads the name; K8s is last, it takes any YAML file left with a kind.
    private static Optional<Extractor> extractor(String name) {
        if (SpringConfig.reads(name)) {
            return Optional.of(SpringConfig::extract);
        }
        if (DotEnv.reads(name)) {
            return Optional.of(DotEnv::extract);
        }
        if (BuildFile.reads(name)) {
            return Optional.of(BuildFile::extract);
        }
        if (Compose.reads(name)) {
            return Optional.of(Compose::extract);
        }
        if (Ansible.reads(name)) {
            return Optional.of(Ansible::extract);
        }
        if (K8s.reads(name)) {
            return Optional.of(K8s::extract);
        }
        return Optional.empty();
    }

    /**
     * docs/SECRETS.md: a secret in a file git tracks opens a finding at its line; a git-ignored
     * file (.env) gets none, its secret is stored all the same.
     */
    private static ScannedFile withFindings(ScannedFile file, boolean tracked) {
        if (!tracked || file.secrets().isEmpty()) {
            return file;
        }
        List<Found> facts = new ArrayList<>(file.facts());
        for (FoundSecret secret : file.secrets()) {
            facts.add(
                    new Found(
                            null,
                            null,
                            FINDING,
                            FINDING_TEXT.formatted(file.path(), secret.line()),
                            secret.env(),
                            secret.line()));
        }
        return new ScannedFile(file.path(), facts, file.links(), file.secrets());
    }
}
