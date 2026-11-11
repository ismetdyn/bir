package io.github.ismetdyn.agentkb.client;

import io.github.ismetdyn.agentkb.api.DocumentAnswer;
import io.github.ismetdyn.agentkb.api.DocumentListAnswer;
import io.github.ismetdyn.agentkb.client.Json.Fields;

/**
 * The plan.md H7 document answers read field by field into their kb-api records, as AnswerReaders
 * does for H6: a @Nullable component is read as optional, every other one must be there. A snapshot
 * row from another machine may lack any property but the id (DocumentAnswer).
 */
final class DocumentReaders {

    private DocumentReaders() {}

    static DocumentAnswer document(Fields answer) {
        return new DocumentAnswer(
                answer.text("id"),
                answer.optionalText("name"),
                answer.optionalText("sha256"),
                answer.optionalText("mime"),
                answer.optionalNumber("size"),
                answer.optionalText("origin"),
                answer.optionalText("addedAt"),
                answer.optionalText("path"),
                answer.texts("about"));
    }

    static DocumentListAnswer documents(Fields answer) {
        return new DocumentListAnswer(
                answer.list("documents", DocumentReaders::document), answer.flag("more"));
    }
}
