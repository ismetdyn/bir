package io.github.ismetdyn.agentkb.server.api;

import io.github.ismetdyn.agentkb.api.CheckAnswer;
import io.github.ismetdyn.agentkb.api.EditAnswer;
import io.github.ismetdyn.agentkb.api.MemoryImportAnswer;
import io.github.ismetdyn.agentkb.api.RestoreAnswer;
import io.github.ismetdyn.agentkb.api.RevertAnswer;
import io.github.ismetdyn.agentkb.api.ScanAnswer;
import io.github.ismetdyn.agentkb.api.SyncAnswer;
import io.github.ismetdyn.agentkb.api.WriteAnswer;
import io.github.ismetdyn.agentkb.server.hooks.MemoryImport;
import io.github.ismetdyn.agentkb.server.snapshot.CheckReport;
import io.github.ismetdyn.agentkb.server.write.EditResult;
import io.github.ismetdyn.agentkb.server.write.RestoreResult;
import io.github.ismetdyn.agentkb.server.write.RevertResult;
import io.github.ismetdyn.agentkb.server.write.ScanResult;
import io.github.ismetdyn.agentkb.server.write.SyncResult;
import io.github.ismetdyn.agentkb.server.write.WriteResult;

/**
 * Domain results as the kb-api records the REST endpoints answer with (ADR-0025 decision 6): a
 * domain record changes without changing the wire, and the wire never carries a domain type.
 */
final class ApiAnswers {

    /** What kb run takes for a secret (docs/SECRETS.md): this prefix and the secret id. */
    static final String PLACEHOLDER = "kb:secret/";

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

    static WriteAnswer of(WriteResult result) {
        return new WriteAnswer(
                result.entity(),
                result.created(),
                result.batch(),
                result.added(),
                result.refreshed(),
                result.closed(),
                result.conflicts());
    }

    static EditAnswer of(EditResult result) {
        return new EditAnswer(result.batch());
    }

    /** Secret ids become placeholders; a value never is in a ScanResult (invariant 2). */
    static ScanAnswer of(ScanResult result) {
        return new ScanAnswer(
                result.project(),
                result.repo(),
                result.batch(),
                result.files(),
                result.newEntities(),
                result.added(),
                result.refreshed(),
                result.closed(),
                result.conflicts(),
                result.secrets().stream().map(id -> PLACEHOLDER + id).toList(),
                result.findings(),
                result.skipped());
    }
}
