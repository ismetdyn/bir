package io.github.ismetdyn.agentkb.server.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import io.github.ismetdyn.agentkb.server.RunningServerTestBase;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ApplicationContext;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * ADR-0022 decision 19: the dispatcherServlet logger is masked, so the one top-level REST error
 * handler logs the exception class and a {@code trace_id} itself, never the payload; the reply
 * carries the same trace id and no internal detail. A failure carrying a secret in its message (and
 * its cause's) must leave it in neither. The production advice runs in front of a throwing test
 * controller in a standalone MockMvc; a second web server would rename the Tomcat engine
 * (RunningServerTestBase).
 */
@ExtendWith(OutputCaptureExtension.class)
class RestErrorHandlerTest extends RunningServerTestBase {

    private static final String ROOT_PACKAGE = "io.github.ismetdyn.agentkb";
    private static final String SENTINEL = "kbtest-FAKE-payload-4d1e9b62";
    private static final Pattern TRACE_ID = Pattern.compile("trace_id\\W{1,4}([A-Za-z0-9-]{8,})");

    @Autowired private ApplicationContext context;

    /** A failure as a bug deep in a REST path would raise it, the payload in its text. */
    static final class PayloadCarryingFailure extends RuntimeException {
        PayloadCarryingFailure() {
            super("value " + SENTINEL, new IllegalArgumentException("cause " + SENTINEL));
        }
    }

    @RestController
    static final class FailingController {
        @GetMapping("/api/v1/kbtest-fail")
        String fail() {
            throw new PayloadCarryingFailure();
        }
    }

    @Test
    void an_unexpected_failure_is_logged_by_class_and_trace_id_only(CapturedOutput output)
            throws Exception {
        MockMvc mvc =
                MockMvcBuilders.standaloneSetup(new FailingController())
                        .setControllerAdvice(theOneRestErrorHandler())
                        .build();

        MockHttpServletResponse reply =
                mvc.perform(get("/api/v1/kbtest-fail")).andReturn().getResponse();

        String body = reply.getContentAsString();
        assertThat(reply.getStatus()).isEqualTo(500);
        assertThat(body)
                .doesNotContain(SENTINEL)
                .doesNotContain(PayloadCarryingFailure.class.getSimpleName())
                .doesNotContain("Exception")
                .doesNotContain("\tat ");
        assertThat(output.getAll())
                .contains(PayloadCarryingFailure.class.getSimpleName())
                .doesNotContain(SENTINEL);
        Matcher traceId = TRACE_ID.matcher(output.getAll());
        assertThat(traceId.find()).as("a trace_id in the log:%n%s", output.getAll()).isTrue();
        assertThat(body).as("the reply carries the logged trace id").contains(traceId.group(1));
    }

    private Object theOneRestErrorHandler() {
        List<Object> handlers =
                context.getBeansWithAnnotation(ControllerAdvice.class).values().stream()
                        .filter(bean -> bean.getClass().getName().startsWith(ROOT_PACKAGE))
                        .toList();
        assertThat(handlers).as("our @RestControllerAdvice beans").hasSize(1);
        return handlers.getFirst();
    }
}
