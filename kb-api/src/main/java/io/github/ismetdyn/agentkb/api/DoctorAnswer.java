package io.github.ismetdyn.agentkb.api;

import java.util.List;
import org.jspecify.annotations.Nullable;

/** {@code GET /api/v1/doctor}: ok only if every check is. */
public record DoctorAnswer(boolean ok, List<Check> checks) {

    public DoctorAnswer {
        checks = List.copyOf(checks);
    }

    /** One check: git, git-lfs, repo, lfs-filter or data-dir; {@code hint} only when it fails. */
    public record Check(String name, boolean ok, @Nullable String hint) {}
}
