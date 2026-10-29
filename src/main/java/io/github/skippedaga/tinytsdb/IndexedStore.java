package io.github.skippedaga.tinytsdb;

import java.util.List;
import java.util.Set;

/**
 * A store that finds series by label matchers. Without it, a selector or a grouped read must name its series
 * exactly: every label with {@code =}, nothing else.
 */
public interface IndexedStore extends CounterStore {

    /**
     * The series matching every matcher (a missing label matches as {@code ""}).
     *
     * @return at most {@code limit + 1} series, so the caller can tell the limit was crossed
     */
    List<Labels> series(List<Matcher> matchers, int limit);

    /** The values of {@code label} over all series. */
    Set<String> labelValues(String label);
}
