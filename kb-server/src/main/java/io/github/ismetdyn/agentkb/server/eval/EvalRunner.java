package io.github.ismetdyn.agentkb.server.eval;

import io.github.ismetdyn.agentkb.server.mcp.worker.QueryTools;
import io.github.ismetdyn.agentkb.server.mcp.worker.ReadTools;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * Scores eval/questions.yaml through the read tools, as an agent would call them (ADR-0022 decision
 * 8). A question passes if one expected record is among the first three results (kb_read: it is the
 * record returned, kb_cypher: the first column of the first rows) and every {@code expect_text} is
 * in the answer. A question that cannot be run fails; skipping it would raise the score.
 */
@Component
public class EvalRunner {

    private static final Logger LOG = LoggerFactory.getLogger(EvalRunner.class);

    private static final int TOP = 3;
    private static final YAMLMapper YAML = YAMLMapper.builder().build();
    // "1. ornek-app-01 · Server — …" (SPEC §6.6) and "# ornek-app-01 · Server · current".
    private static final Pattern RESULT_ID = Pattern.compile("(?m)^\\d+\\. (\\S+)");
    private static final Pattern HEADING_ID = Pattern.compile("(?m)^# (\\S+)");
    // The first cell of each table line; the header and the |---| line come first.
    private static final Pattern FIRST_CELL = Pattern.compile("(?m)^\\| ([^|]*?) \\|");
    private static final int TABLE_HEAD = 2;

    private final ReadTools read;
    private final QueryTools query;

    public EvalRunner(ReadTools read, QueryTools query) {
        this.read = read;
        this.query = query;
    }

    /**
     * @throws IllegalStateException naming the file if it cannot be read
     */
    public EvalReport run(Path questions) {
        List<String> failed = new ArrayList<>();
        int total = 0;
        for (JsonNode question : questions(questions)) {
            total++;
            String id = question.path("id").asString("");
            boolean passed = passes(question);
            LOG.info("event=eval_question id={} passed={}", id, passed);
            if (!passed) {
                failed.add(id);
            }
        }
        EvalReport report = new EvalReport(total, total - failed.size(), failed);
        LOG.info(
                "event=eval_done passed={} total={} score={}",
                report.passed(),
                report.total(),
                report.score());
        return report;
    }

    private static JsonNode questions(Path file) {
        try {
            return YAML.readTree(file).path("questions");
        } catch (JacksonException e) {
            throw new IllegalStateException("cannot read eval questions " + file, e);
        }
    }

    private boolean passes(JsonNode question) {
        Optional<String> answer = answer(question);
        if (answer.isEmpty()) {
            return false;
        }
        String text = answer.get();
        List<String> top = top(question.path("tool").asString(""), text);
        boolean found =
                question.path("expect")
                        .valueStream()
                        .map(JsonNode::asString)
                        .anyMatch(top::contains);
        boolean texts =
                question.path("expect_text")
                        .valueStream()
                        .map(JsonNode::asString)
                        .allMatch(text::contains);
        return found && texts;
    }

    /** The answer of the question's tool, empty for a tool the runner does not know. */
    private Optional<String> answer(JsonNode q) {
        String text = string(q, "query");
        return Optional.ofNullable(
                switch (q.path("tool").asString("")) {
                    case "kb_search" ->
                            read.search(
                                    text, string(q, "type"), string(q, "env"), number(q, "limit"));
                    case "kb_read" -> read.read(text);
                    case "kb_related" ->
                            read.related(
                                    text,
                                    string(q, "rel"),
                                    string(q, "direction"),
                                    number(q, "depth"));
                    case "kb_stale" -> query.stale(number(q, "days"));
                    case "kb_cypher" -> query.cypher(text);
                    default -> null;
                });
    }

    private static List<String> top(String tool, String answer) {
        Pattern ids =
                switch (tool) {
                    case "kb_read" -> HEADING_ID;
                    case "kb_cypher" -> FIRST_CELL;
                    default -> RESULT_ID;
                };
        List<String> found = new ArrayList<>();
        Matcher id = ids.matcher(answer);
        while (id.find()) {
            found.add(id.group(1).strip());
        }
        int first = "kb_cypher".equals(tool) ? Math.min(TABLE_HEAD, found.size()) : 0;
        return found.subList(first, Math.min(first + TOP, found.size()));
    }

    private static @Nullable String string(JsonNode question, String field) {
        JsonNode value = question.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asString();
    }

    private static @Nullable Integer number(JsonNode question, String field) {
        JsonNode value = question.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asInt();
    }
}
