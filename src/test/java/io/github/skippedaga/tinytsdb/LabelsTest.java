package io.github.skippedaga.tinytsdb;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LabelsTest {

    @Test
    void namesAreSortedAndOrderDoesNotMatter() {
        Labels a = Labels.of("b", "2", "a", "1");
        Labels b = Labels.of("a", "1", "b", "2");

        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
        assertThat(a.name(0)).isEqualTo("a");
        assertThat(a.toMap()).containsExactly(Map.entry("a", "1"), Map.entry("b", "2"));
    }

    @Test
    void anEmptyValueIsNoLabel() {
        Labels labels = Labels.of("a", "1", "b", "");

        assertThat(labels.size()).isEqualTo(1);
        assertThat(labels.has("b")).isFalse();
        assertThat(labels.get("b")).isEmpty();
        assertThat(labels).isEqualTo(Labels.of("a", "1"));
    }

    @Test
    void withWithoutAndKeep() {
        Labels labels = Labels.of("a", "1", "b", "2");

        assertThat(labels.with("c", "3")).isEqualTo(Labels.of("a", "1", "b", "2", "c", "3"));
        assertThat(labels.with("a", "")).isEqualTo(Labels.of("b", "2"));
        assertThat(labels.without(List.of("a"))).isEqualTo(Labels.of("b", "2"));
        assertThat(labels.keep(List.of("a", "x"))).isEqualTo(Labels.of("a", "1"));
    }

    @Test
    void theMetricNameIsALabel() {
        Labels labels = Labels.of(Labels.METRIC_NAME, "http_requests_total", "code", "200", "Zone", "eu");

        assertThat(labels.metricName()).isEqualTo("http_requests_total");
        assertThat(labels.withoutMetricName()).isEqualTo(Labels.of("code", "200", "Zone", "eu"));
        assertThat(labels).hasToString("http_requests_total{Zone=\"eu\", code=\"200\"}");
    }

    @Test
    void valuesAreEscapedInToString() {
        assertThat(Labels.of("a", "say \"hi\"\n")).hasToString("{a=\"say \\\"hi\\\"\\n\"}");
    }

    @Test
    void invalidNamesAreRejected() {
        assertThatThrownBy(() -> Labels.of("1a", "x")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Labels.of("a-b", "x")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Labels.of(Labels.METRIC_NAME, "no-dash")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Labels.of("a", "1", "a", "2")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Labels.of("a")).isInstanceOf(IllegalArgumentException.class);
    }
}
