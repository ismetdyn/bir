package io.github.ismetdyn.agentkb.server.scan.extract;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ismetdyn.agentkb.server.write.ScanWrite.Found;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A pom.xml is untrusted input (A7): neither an external entity (XXE, a file outside the allowed
 * roots) nor an internal one (entity expansion) may reach a fact. With the parser's defaults both
 * payloads below resolve on JDK 21, so these tests fail when BuildFile's DTD controls are removed.
 */
class BuildFileXmlTest {

    private static final String MARKER = "kbtest-xxe-marker";
    // One expansion level of ten; with DTDs on, java.version becomes "kbx" ten times.
    private static final String EXPANDED = "kbxkbx";

    @TempDir Path dir;

    @Test
    void an_external_entity_in_a_pom_is_never_resolved() throws IOException {
        Path outside = Files.writeString(dir.resolve("outside.txt"), MARKER);

        List<String> values =
                values(
                        """
                        <?xml version="1.0"?>
                        <!DOCTYPE project [<!ENTITY xxe SYSTEM "%s">]>
                        <project>
                          <artifactId>zz-xxe</artifactId>
                          <properties><java.version>&xxe;</java.version></properties>
                        </project>
                        """
                                .formatted(outside.toUri()));

        assertThat(values).noneMatch(value -> value.contains(MARKER));
    }

    @Test
    void an_internal_entity_in_a_pom_is_never_expanded() {
        List<String> values =
                values(
                        """
                        <?xml version="1.0"?>
                        <!DOCTYPE project [
                          <!ENTITY a "kbx">
                          <!ENTITY b "&a;&a;&a;&a;&a;&a;&a;&a;&a;&a;">
                        ]>
                        <project>
                          <artifactId>zz-expansion</artifactId>
                          <properties><java.version>&b;</java.version></properties>
                        </project>
                        """);

        assertThat(values).noneMatch(value -> value.contains(EXPANDED));
    }

    /** Control: the same pom without a DOCTYPE does give its java version, so the path is live. */
    @Test
    void a_pom_without_a_doctype_gives_its_java_version() {
        List<String> values =
                values(
                        """
                        <?xml version="1.0"?>
                        <project>
                          <artifactId>zz-plain</artifactId>
                          <properties><java.version>21</java.version></properties>
                        </project>
                        """);

        assertThat(values).contains("21");
    }

    /**
     * The fact values BuildFile extracts; none when it refuses the pom, which the scanner then
     * skips as "cannot be parsed" (ScannerService.read).
     */
    private static List<String> values(String pom) {
        Extraction out = new Extraction(List.of());
        try {
            BuildFile.extract("pom.xml", pom, out);
        } catch (IllegalArgumentException refused) {
            return List.of();
        }
        return out.facts().stream().map(Found::value).toList();
    }
}
