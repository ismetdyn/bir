package io.github.ismetdyn.agentkb.server.write.archfixture;

import io.github.ismetdyn.agentkb.api.archfixture.ApiRecord;

/**
 * Violation for ArchitectureRulesTest: in the server only server.api maps to kb-api records. Only
 * its class file is read.
 */
public final class WriteUsesApiRecord {
    ApiRecord answer;
}
