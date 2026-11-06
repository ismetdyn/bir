package io.github.ismetdyn.agentkb.api.archfixture;

import org.springframework.http.HttpStatus;

/**
 * Violation for ArchitectureRulesTest: kb-api depends on the JDK and JSpecify only, so kb (native,
 * no Spring) can use it. Only its class file is read.
 */
public record ApiRecordUsingSpring(HttpStatus status) {}
