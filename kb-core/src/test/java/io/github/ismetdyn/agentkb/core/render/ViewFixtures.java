package io.github.ismetdyn.agentkb.core.render;

import static io.github.ismetdyn.agentkb.core.model.FactStatus.CONFLICT;
import static io.github.ismetdyn.agentkb.core.model.FactStatus.OK;
import static io.github.ismetdyn.agentkb.core.model.FactStatus.UNVERIFIED;
import static io.github.ismetdyn.agentkb.core.model.Source.AGENT;
import static io.github.ismetdyn.agentkb.core.model.Source.SCAN;
import static io.github.ismetdyn.agentkb.core.model.Source.USER;

import io.github.ismetdyn.agentkb.core.model.Fact;
import io.github.ismetdyn.agentkb.core.model.FactStatus;
import io.github.ismetdyn.agentkb.core.model.Source;
import java.time.Instant;
import java.util.List;

/**
 * Seed records (seed/snapshot) as MarkdownView input, plus one synthetic case from seed/README.md
 * "İlk ve son görülme". The only place that knows the shape of the view records.
 */
final class ViewFixtures {

    private static final String PROD = "prod";
    private static final String JAN_15 = "2026-01-15T09:00:00Z";
    private static final String MAY_10 = "2026-05-10T09:00:00Z";
    private static final String JUN_01 = "2026-06-01T09:00:00Z";
    private static final String OCT_01 = "2026-10-01T09:00:00Z";
    private static final String OCT_28 = "2026-10-28T15:00:00Z";
    private static final String OCT_29 = "2026-10-29T10:00:00Z";
    private static final String OCT_30 = "2026-10-30T14:00:00Z";

    private ViewFixtures() {}

    static EntityView ornekApp01() {
        return new EntityView(
                "ornek-app-01",
                "Server",
                "current",
                "Örnek uygulama sunucusu.",
                List.of("app01"),
                List.of(
                        view(
                                fact(
                                        "hostname",
                                        "ornek-app-01.ornek.example",
                                        USER,
                                        OK,
                                        JAN_15,
                                        MAY_10)),
                        view(fact("ip", "192.0.2.21", USER, OK, MAY_10, MAY_10)),
                        view(fact("ip", "192.0.2.25", AGENT, CONFLICT, OCT_28, OCT_28)),
                        new FactView(
                                fact("port", "8443/https", SCAN, OK, OCT_29, OCT_29),
                                "ornek-proje",
                                "ornek-proje:src/main/resources/application-prod.yml:2"),
                        view(fact("ip", "192.0.2.20", USER, OK, JAN_15, JAN_15, MAY_10))),
                List.of(
                        relation("RUNS", true, "ornek-proje", MAY_10),
                        relation("BEHIND", true, "ornek-lb", MAY_10),
                        relation("PART_OF", true, "ornek-prod-ortami", MAY_10)),
                List.of(
                        new SecretView(
                                "ornek-app-01/prod/deploy-ssh-key", "deploy-ssh-key", PROD)));
    }

    static EntityView ornekApp00() {
        return new EntityView(
                "ornek-app-00",
                "Server",
                "archived",
                "Eski uygulama sunucusu; uygulamalar 2026-05'te ornek-app-01'e taşındı.",
                List.of("app00"),
                List.of(
                        view(
                                fact(
                                        "hostname",
                                        "ornek-app-00.ornek.example",
                                        USER,
                                        OK,
                                        JAN_15,
                                        JAN_15))),
                List.of(relation("DEPLOYED_ON", false, "ornek-proje", JAN_15, MAY_10)),
                List.of());
    }

    static EntityView ornekLb() {
        return new EntityView(
                "ornek-lb",
                "Network",
                "current",
                "Sunucuların önündeki load balancer + WAF (örnek).",
                List.of("ornek-f5"),
                List.of(view(fact("ip", "192.0.2.5", USER, OK, OCT_01, OCT_01))),
                List.of(relation("BEHIND", false, "ornek-app-01", MAY_10)),
                List.of(new SecretView("ornek-lb/prod/panel-admin", "panel-admin", PROD)));
    }

    static EntityView ornekProje() {
        Fact fromDocument =
                withEnv(
                        fact(
                                "convention",
                                "Yeniden başlatma: sudo systemctl restart ornek-proje;"
                                        + " loglar: journalctl -u ornek-proje",
                                AGENT,
                                UNVERIFIED,
                                OCT_30,
                                OCT_30),
                        Fact.DEFAULT_ENV);
        return new EntityView(
                "ornek-proje",
                "Project",
                "current",
                "Örnek Spring Boot projesi.",
                List.of(),
                List.of(new FactView(fromDocument, null, "doc:ded48257b6f3")),
                List.of(
                        relation("DEPLOYED_ON", true, "ornek-app-01", MAY_10),
                        relation("DEPLOYED_ON", true, "ornek-app-00", JAN_15, MAY_10),
                        relation("RUNS", false, "ornek-app-01", MAY_10)),
                List.of());
    }

    static EntityView ornekDb() {
        return new EntityView(
                "ornek-db",
                "Database",
                "observed",
                "Tarayıcının oluşturduğu örnek kayıt; kullanıcı doğrulamadı.",
                List.of("ornek-db.ornek.example"),
                List.of(),
                List.of(),
                List.of());
    }

    /**
     * README "İlk ve son görülme": first seen Jan 15, last seen May 10, closed Jun 1. The history
     * range starts at valid_from, not at observed_at.
     */
    static EntityView hostnameClosedAfterBeingSeenAgain() {
        return new EntityView(
                "ornek-app-01",
                "Server",
                "current",
                "Örnek uygulama sunucusu.",
                List.of(),
                List.of(
                        view(
                                fact(
                                        "hostname",
                                        "ornek-app-01.ornek.example",
                                        USER,
                                        OK,
                                        JAN_15,
                                        MAY_10,
                                        JUN_01))),
                List.of(),
                List.of());
    }

    private static FactView view(Fact fact) {
        return new FactView(fact, null, null);
    }

    /** A prod fact; {@code times} are valid_from, observed_at and, if closed, valid_to. */
    private static Fact fact(
            String key, String value, Source source, FactStatus status, String... times) {
        return new Fact(
                key + "-" + value,
                key,
                value,
                PROD,
                source,
                Instant.parse(times[0]),
                Instant.parse(times[1]),
                times.length > 2 ? Instant.parse(times[2]) : null,
                status,
                null,
                null);
    }

    private static Fact withEnv(Fact fact, String env) {
        return new Fact(
                fact.id(),
                fact.key(),
                fact.value(),
                env,
                fact.source(),
                fact.validFrom(),
                fact.observedAt(),
                fact.validTo(),
                fact.status(),
                fact.evidence(),
                fact.batch());
    }

    /**
     * A user relation without env, like RUNS, BEHIND and PART_OF in the seed; {@code times} are
     * valid_from and, if ended, valid_to.
     */
    private static RelationView relation(
            String type, boolean outgoing, String other, String... times) {
        Instant from = Instant.parse(times[0]);
        Instant to = times.length > 1 ? Instant.parse(times[1]) : null;
        return new RelationView(
                type, outgoing, other, null, USER, from, to == null ? from : to, to);
    }
}
