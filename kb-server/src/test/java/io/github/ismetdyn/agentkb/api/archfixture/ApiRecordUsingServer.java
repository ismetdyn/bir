package io.github.ismetdyn.agentkb.api.archfixture;

import io.github.ismetdyn.agentkb.server.write.RevertResult;

/**
 * Violation for ArchitectureRulesTest: a kb-api record that carries a server domain record, the
 * domain → wire dependency ADR-0025 decision 6 rules out. Only its class file is read.
 */
public record ApiRecordUsingServer(RevertResult result) {}
