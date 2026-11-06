package io.github.ismetdyn.agentkb.server.api;

import io.github.ismetdyn.agentkb.api.CheckAnswer;
import io.github.ismetdyn.agentkb.api.MemoryImportAnswer;
import io.github.ismetdyn.agentkb.api.RestoreAnswer;
import io.github.ismetdyn.agentkb.api.RevertAnswer;
import io.github.ismetdyn.agentkb.api.SyncAnswer;
import io.github.ismetdyn.agentkb.server.hooks.MemoryImport;
import io.github.ismetdyn.agentkb.server.snapshot.CheckReport;
import io.github.ismetdyn.agentkb.server.write.RestoreResult;
import io.github.ismetdyn.agentkb.server.write.RevertResult;
import io.github.ismetdyn.agentkb.server.write.SyncResult;

/**
 * Domain results as the kb-api records the REST endpoints answer with (ADR-0025 decision 6): a
 * domain record changes without changing the wire, and the wire never carries a domain type.
 */
final class ApiAnswers {

    private ApiAnswers() {}

    static SyncAnswer of(SyncResult result) {
        return new SyncAnswer(
                result.commit(),
                result.pushed(),
                result.nodes(),
                result.edges(),
                result.conflicts());
    }

    static RevertAnswer of(RevertResult result) {
        return new RevertAnswer(
                result.batch(), result.deleted(), result.reopened(), result.deletedSecretValues());
    }

    static RestoreAnswer of(RestoreResult result) {
        return new RestoreAnswer(
                result.nodes(),
                result.edges(),
                result.keptSecrets(),
                result.keptSignals(),
                result.droppedEdges(),
                result.droppedSecretValues());
    }

    static MemoryImportAnswer of(MemoryImport.Imported imported) {
        return new MemoryImportAnswer(imported.received(), imported.opened(), imported.truncated());
    }

    static CheckAnswer of(CheckReport report) {
        return new CheckAnswer(
                report.clean(),
                report.problems().stream()
                        .map(
                                problem ->
                                        new CheckAnswer.Problem(
                                                problem.kind(), problem.id(), problem.detail()))
                        .toList());
    }
}
