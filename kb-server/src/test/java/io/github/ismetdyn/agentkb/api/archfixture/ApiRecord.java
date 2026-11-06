package io.github.ismetdyn.agentkb.api.archfixture;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * What kb-api may hold (ADR-0025 decision 6): a record over JDK types and JSpecify. For
 * ArchitectureRulesTest, under kb-api's package name; only its class file is read.
 */
public record ApiRecord(String id, @Nullable String hint, List<String> names, int count) {}
