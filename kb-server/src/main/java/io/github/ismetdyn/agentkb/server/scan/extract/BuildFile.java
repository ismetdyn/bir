package io.github.ismetdyn.agentkb.server.scan.extract;

import java.io.StringReader;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * {@code pom.xml} and {@code build.gradle(.kts)} (ARCHITECTURE §6): the stack (java-spring, kotlin,
 * java), the Spring Boot version and the Java version, as facts of the project in the default env.
 * A {@code ${...}} version is a property reference and is skipped.
 */
public final class BuildFile {

    private static final String POM = "pom.xml";
    private static final Pattern GRADLE = Pattern.compile("build\\.gradle(?:\\.kts)?");
    private static final String SPRING = "java-spring";
    private static final String STACK = "stack";
    private static final String FRAMEWORK_VERSION = "framework_version";
    private static final String JAVA_VERSION = "java_version";
    private static final String SPRING_BOOT = "spring-boot ";
    private static final List<String> JAVA_PROPERTIES =
            List.of(
                    "project/properties/java.version",
                    "project/properties/maven.compiler.release",
                    "project/properties/maven.compiler.source");
    private static final Pattern GRADLE_SPRING =
            Pattern.compile(
                    "org\\.springframework\\.boot['\"]\\)?(?:\\s++version\\s++['\"]([^'\"]++)['\"])?");
    private static final Pattern GRADLE_JAVA =
            Pattern.compile(
                    "JavaLanguageVersion\\.of\\(\\s*+(\\d++)\\s*+\\)|jvmToolchain\\(\\s*+(\\d++)"
                            + "|sourceCompatibility\\s*+=\\s*+(?:JavaVersion\\.VERSION_)?['\"]?"
                            + "([0-9][0-9._]*+)");
    private static final Pattern GRADLE_KOTLIN =
            Pattern.compile("org\\.jetbrains\\.kotlin|kotlin\\(\\s*+\"jvm\"");
    private static final Pattern GRADLE_JAVA_PLUGIN =
            Pattern.compile("id\\s*+\\(?\\s*+['\"]java['\"]|apply\\s++plugin:\\s*+['\"]java['\"]");

    private BuildFile() {}

    public static boolean reads(String fileName) {
        return POM.equals(fileName) || GRADLE.matcher(fileName).matches();
    }

    /**
     * @throws IllegalArgumentException if a pom.xml is not well-formed XML
     */
    public static void extract(String fileName, String text, Extraction out) {
        if (POM.equals(fileName)) {
            pom(elements(text), out);
        } else if (GRADLE.matcher(fileName).matches()) {
            gradle(text, out);
        }
    }

    /** A leaf element by its path from the root ({@code project/parent/version}). */
    private record Element(String path, String text, int line) {}

    private static void pom(List<Element> elements, Extraction out) {
        Optional<Element> parent = first(elements, "project/parent/artifactId");
        boolean spring = false;
        if (parent.isPresent() && "spring-boot-starter-parent".equals(parent.get().text())) {
            spring = true;
            fact(out, STACK, SPRING, parent.get().line());
            first(elements, "project/parent/version")
                    .filter(version -> !Extraction.isReference(version.text()))
                    .ifPresent(
                            version ->
                                    fact(
                                            out,
                                            FRAMEWORK_VERSION,
                                            SPRING_BOOT + version.text(),
                                            version.line()));
        }
        Optional<Element> starter = artifact(elements, "spring-boot");
        if (!spring && starter.isPresent()) {
            spring = true;
            fact(out, STACK, SPRING, starter.get().line());
        }
        Optional<Element> kotlin = artifact(elements, "kotlin-");
        kotlin.ifPresent(element -> fact(out, STACK, "kotlin", element.line()));
        if (!spring && kotlin.isEmpty()) {
            first(elements, "project/artifactId")
                    .ifPresent(element -> fact(out, STACK, "java", element.line()));
        }
        JAVA_PROPERTIES.stream()
                .flatMap(path -> first(elements, path).stream())
                .filter(version -> !Extraction.isReference(version.text()))
                .findFirst()
                .ifPresent(version -> fact(out, JAVA_VERSION, version.text(), version.line()));
    }

    /** The first line a pattern is found on. */
    private record Hit(MatchResult match, int line) {}

    private static void gradle(String text, Extraction out) {
        List<String> lines = text.lines().toList();
        Optional<Hit> boot = find(lines, GRADLE_SPRING);
        boot.ifPresent(
                hit -> {
                    fact(out, STACK, SPRING, hit.line());
                    if (hit.match().group(1) != null) {
                        fact(
                                out,
                                FRAMEWORK_VERSION,
                                SPRING_BOOT + hit.match().group(1),
                                hit.line());
                    }
                });
        Optional<Hit> kotlin = find(lines, GRADLE_KOTLIN);
        kotlin.ifPresent(hit -> fact(out, STACK, "kotlin", hit.line()));
        if (boot.isEmpty() && kotlin.isEmpty()) {
            find(lines, GRADLE_JAVA_PLUGIN).ifPresent(hit -> fact(out, STACK, "java", hit.line()));
        }
        find(lines, GRADLE_JAVA)
                .ifPresent(hit -> fact(out, JAVA_VERSION, javaVersion(hit.match()), hit.line()));
    }

    private static Optional<Hit> find(List<String> lines, Pattern pattern) {
        for (int i = 0; i < lines.size(); i++) {
            Matcher matcher = pattern.matcher(lines.get(i));
            if (matcher.find()) {
                return Optional.of(new Hit(matcher.toMatchResult(), i + 1));
            }
        }
        return Optional.empty();
    }

    // VERSION_1_8 → 1.8, VERSION_17 → 17.
    private static String javaVersion(MatchResult java) {
        for (int group = 1; group <= java.groupCount(); group++) {
            if (java.group(group) != null) {
                return java.group(group).replace('_', '.');
            }
        }
        throw new IllegalStateException("GRADLE_JAVA matched without a version group");
    }

    private static void fact(Extraction out, String key, String value, int line) {
        out.fact(null, null, key, value, Extraction.DEFAULT_ENV, line);
    }

    private static Optional<Element> first(List<Element> elements, String path) {
        return elements.stream().filter(element -> element.path().equals(path)).findFirst();
    }

    private static Optional<Element> artifact(List<Element> elements, String prefix) {
        return elements.stream()
                .filter(
                        element ->
                                element.path().endsWith("/artifactId")
                                        && element.text().startsWith(prefix))
                .findFirst();
    }

    /**
     * Leaf elements with the line of their start tag. DTDs and external entities are off: a pom.xml
     * is untrusted input (XXE).
     */
    private static List<Element> elements(String xml) {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        List<Element> elements = new ArrayList<>();
        Deque<String> path = new ArrayDeque<>();
        Deque<Integer> lines = new ArrayDeque<>();
        StringBuilder text = new StringBuilder();
        try {
            XMLStreamReader reader = factory.createXMLStreamReader(new StringReader(xml));
            try {
                while (reader.hasNext()) {
                    switch (reader.next()) {
                        case XMLStreamConstants.START_ELEMENT -> {
                            path.addLast(reader.getLocalName());
                            lines.addLast(reader.getLocation().getLineNumber());
                            text.setLength(0);
                        }
                        case XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA ->
                                text.append(reader.getText());
                        case XMLStreamConstants.END_ELEMENT -> {
                            elements.add(
                                    new Element(
                                            String.join("/", path),
                                            text.toString().strip(),
                                            lines.removeLast()));
                            path.removeLast();
                            text.setLength(0);
                        }
                        default -> {
                            // Comments, processing instructions and whitespace carry nothing.
                        }
                    }
                }
            } finally {
                reader.close();
            }
        } catch (XMLStreamException e) {
            throw new IllegalArgumentException("pom.xml is not well-formed XML", e);
        }
        return elements;
    }
}
