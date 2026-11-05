package io.github.ismetdyn.agentkb.server.write;

import io.github.ismetdyn.agentkb.core.Times;
import io.github.ismetdyn.agentkb.core.model.Source;
import io.github.ismetdyn.agentkb.server.write.WriteRequest.FactInput;
import io.github.ismetdyn.agentkb.server.write.WriteRequest.RelationInput;
import java.time.Instant;
import java.util.List;

/** The only place in the tests that knows the shape of WriteRequest. */
final class WriteRequests {

    static final String SERVER = "server";
    static final String PROD = "prod";

    private WriteRequests() {}

    static FactInput fact(String key, String value, String env) {
        return new FactInput(key, value, env, null);
    }

    /** A fact of a key that takes a target (port); {@code target} is an entity id or alias. */
    static FactInput fact(String key, String value, String env, String target) {
        return new FactInput(key, value, env, target);
    }

    /** A write of {@code facts} about a server, which is created if it does not exist. */
    static WriteRequest request(Source source, String entity, Instant at, FactInput... facts) {
        return new WriteRequest(source, entity, SERVER, List.of(facts), List.of(), null, null, at);
    }

    static WriteRequest inBatch(
            Source source, String entity, Instant at, String batch, FactInput... facts) {
        return new WriteRequest(source, entity, SERVER, List.of(facts), List.of(), null, batch, at);
    }

    static WriteRequest inBatch(
            Source source,
            String entity,
            Instant at,
            String batch,
            List<FactInput> facts,
            List<RelationInput> relations) {
        return new WriteRequest(source, entity, SERVER, facts, relations, null, batch, at);
    }

    /** No type: the entity must already exist. */
    static WriteRequest untyped(Source source, String entity, Instant at, FactInput... facts) {
        return new WriteRequest(source, entity, null, List.of(facts), List.of(), null, null, at);
    }

    static WriteRequest request(
            Source source,
            String entity,
            Instant at,
            List<FactInput> facts,
            List<RelationInput> relations) {
        return new WriteRequest(source, entity, SERVER, facts, relations, null, null, at);
    }

    static RelationInput relationTo(String type, String target, String env) {
        return new RelationInput(type, target, env);
    }

    static WriteRequest relation(
            Source source, String entity, Instant at, String type, String target) {
        return new WriteRequest(
                source,
                entity,
                SERVER,
                List.of(),
                List.of(new RelationInput(type, target, null)),
                null,
                null,
                at);
    }

    /** The graph and snapshot form of a time (Times). */
    static String time(Instant at) {
        return Times.format(at);
    }
}
