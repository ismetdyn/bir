package io.github.ismetdyn.agentkb.server.write;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * ARCHITECTURE §4: ids sort by creation time. kb revert tells a later batch by its id
 * (BatchRevert), so ids made in one millisecond must keep their order too (the ULID spec's
 * monotonic mode).
 */
class UlidTest {

    private static final int IDS = 10_000;

    @Test
    void ids_made_one_after_another_sort_in_that_order() {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < IDS; i++) {
            ids.add(Ulid.next());
        }

        assertThat(ids).isSortedAccordingTo(String::compareTo).doesNotHaveDuplicates();
        assertThat(ids).allMatch(id -> WriteHarness.ULID.matcher(id).matches());
    }
}
