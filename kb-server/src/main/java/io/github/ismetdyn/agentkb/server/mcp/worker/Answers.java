package io.github.ismetdyn.agentkb.server.mcp.worker;

import io.github.ismetdyn.agentkb.server.hooks.BriefService;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

/** The two shapes a read tool answers in (docs/MCP-TOOLS.md "Ortak çıktı kuralları"). */
final class Answers {

    private static final Logger LOG = LoggerFactory.getLogger(Answers.class);

    static final String DATA_HEADER = BriefService.DATA_HEADER;
    // ADR-0017 decision 4: one call is at most 8,000 characters.
    static final int MAX_CHARS = 8_000;
    private static final int CUT_RESERVE = 120;
    // What a result list may take, so content() never has to cut it.
    static final int LIST_BUDGET = MAX_CHARS - DATA_HEADER.length() - 1;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private Answers() {}

    /** The data header, then the body; a body over budget is cut at a line and says so. */
    static String content(String body) {
        String text = DATA_HEADER + "\n" + body;
        if (text.length() <= MAX_CHARS) {
            return text;
        }
        int cut = text.lastIndexOf('\n', MAX_CHARS - CUT_RESERVE);
        return text.substring(0, cut)
                + "\n(kısaltıldı: "
                + (text.length() - cut)
                + " karakter daha; ilişkiler için kb_related kullan)\n";
    }

    /** {@code {"error": …, "hint": …}}. Neither echoes the agent's input (invariant 2). */
    static String error(String error, String hint) {
        Map<String, String> reply = new LinkedHashMap<>();
        reply.put("error", error);
        reply.put("hint", hint);
        return JSON.writeValueAsString(reply);
    }

    /**
     * The one place a read tool turns a failure into an answer: {@code body}, or {@code {error,
     * hint}} if it throws. The log names the tool and the exception class only: a message may hold
     * the query (invariant 10).
     */
    static String attempt(String tool, String error, String hint, Supplier<String> body) {
        try {
            return body.get();
        } catch (RuntimeException e) {
            LOG.warn("event=read_tool_failed tool={} error={}", tool, e.getClass().getName());
            return error(error, hint);
        }
    }
}
