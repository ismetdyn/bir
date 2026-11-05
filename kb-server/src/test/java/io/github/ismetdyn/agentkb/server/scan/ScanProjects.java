package io.github.ismetdyn.agentkb.server.scan;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Repositories for the scanner tests, set up the way fixtures/scanner/expected.yaml says: copied to
 * a temporary directory, {@code git init}, a remote, everything that .gitignore allows committed.
 * The files are read by the server from disk only; git runs locally (invariant 3).
 */
public final class ScanProjects {

    /** The fake values of fixtures/scanner/ornek-proje; they may live only in secret_value. */
    public static final String PROD_PASSWORD = "Ornek-Prod-Sifre-1";

    public static final String LOCAL_PASSWORD = "Ornek-Yerel-Sifre-2";

    public static final String FIXTURE_NAME = "ornek-proje";

    private static final Path FIXTURE = Path.of("..", "fixtures", "scanner", FIXTURE_NAME);
    private static final String FIXTURE_REMOTE =
            "https://git.ornek.example/ornek-grup/ornek-proje.git";

    private ScanProjects() {}

    /** fixtures/scanner/ornek-proje as a committed working copy at {@code dir/ornek-proje}. */
    public static Path copyFixture(Path dir) {
        Path project = dir.resolve(FIXTURE_NAME);
        try (Stream<Path> files = Files.walk(FIXTURE)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                Path copy = project.resolve(FIXTURE.relativize(file).toString());
                Files.createDirectories(copy.getParent());
                Files.copy(file, copy);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return commit(project, FIXTURE_REMOTE);
    }

    /**
     * A small Spring project named {@code name} everywhere a scanner could take its id from
     * (folder, remote, artifactId, spring.application.name). Its prod profile has a datasource on
     * {@code <name>-db.ornek.example} with {@code prodPassword} in plain text on line {@link
     * #PASSWORD_LINE} (tracked, so a finding); the git-ignored .env holds {@code localPassword}.
     */
    public static Path springProject(
            Path dir, String name, String prodPassword, String localPassword) {
        return project(
                dir,
                name,
                Map.of(
                        "pom.xml",
                        pom(name),
                        "src/main/resources/application-prod.yml",
                        """
                        spring:
                          application:
                            name: %s
                          datasource:
                            url: jdbc:oracle:thin:@%s-db.ornek.example:1521/ZZPDB
                            username: zz_app
                            password: %s
                        """
                                .formatted(name, name, prodPassword),
                        ".gitignore",
                        ".env\n",
                        ".env",
                        "ORACLE_PASSWORD=" + localPassword + "\n"));
    }

    /** The line of {@code password:} in the application-prod.yml of {@link #springProject}. */
    public static final int PASSWORD_LINE = 7;

    /** {@code files} (relative path → content) written under {@code dir/name} and committed. */
    public static Path project(Path dir, String name, Map<String, String> files) {
        Path project = dir.resolve(name);
        try {
            for (Map.Entry<String, String> file : files.entrySet()) {
                Path path = project.resolve(file.getKey());
                Files.createDirectories(path.getParent());
                Files.writeString(path, file.getValue());
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return commit(project, "https://git.ornek.example/zz-grup/" + name + ".git");
    }

    /** Commits every change in {@code repo} that .gitignore allows. */
    public static void commitAll(Path repo, String message) {
        git(repo, "add", "--all");
        git(repo, "commit", "--quiet", "--message", message);
    }

    public static String git(Path repo, String... args) {
        List<String> command =
                Stream.concat(
                                Stream.of(
                                        "git",
                                        "-c",
                                        "user.name=kbtest",
                                        "-c",
                                        "user.email=kbtest@example.invalid",
                                        "-c",
                                        "commit.gpgsign=false",
                                        "-c",
                                        "init.defaultBranch=main"),
                                Stream.of(args))
                        .toList();
        try {
            Process git =
                    new ProcessBuilder(command)
                            .directory(repo.toFile())
                            .redirectErrorStream(true)
                            .start();
            String out = new String(git.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertThat(git.waitFor()).as("git %s: %s", List.of(args), out).isZero();
            return out;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static Path commit(Path project, String remote) {
        git(project, "init", "--quiet");
        git(project, "remote", "add", "origin", remote);
        commitAll(project, "initial");
        return project;
    }

    private static String pom(String artifactId) {
        try {
            return Files.readString(FIXTURE.resolve("pom.xml"))
                    .replace(
                            "<artifactId>" + FIXTURE_NAME + "</artifactId>",
                            "<artifactId>" + artifactId + "</artifactId>");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
