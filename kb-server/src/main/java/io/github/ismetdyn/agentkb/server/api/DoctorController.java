package io.github.ismetdyn.agentkb.server.api;

import io.github.ismetdyn.agentkb.api.DoctorAnswer;
import io.github.ismetdyn.agentkb.server.docs.Doctor;
import java.io.IOException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/v1/doctor} (docs/CLIENTS.md §1–§2): every check of {@link Doctor} with name, ok
 * and hint; 200 whether they pass or not, as {@code ok} says it.
 */
@RestController
class DoctorController {

    private final Doctor doctor;

    DoctorController(Doctor doctor) {
        this.doctor = doctor;
    }

    @GetMapping("/api/v1/doctor")
    DoctorAnswer doctor() throws IOException {
        Doctor.Report report = doctor.check();
        return new DoctorAnswer(
                report.ok(),
                report.checks().stream()
                        .map(
                                check ->
                                        new DoctorAnswer.Check(
                                                check.name(), check.ok(), check.hint()))
                        .toList());
    }
}
