package io.github.skippedaga.tinytsdb;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Arrays;
import java.util.Random;
import java.util.function.DoubleSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class P2QuantileTest {

    /** Rank error: where the estimate falls in the sorted samples, compared with p. */
    private static double rankError(double[] sorted, double estimate, double p) {
        int below = 0;
        while (below < sorted.length && sorted[below] < estimate) {
            below++;
        }
        return Math.abs((double) below / sorted.length - p);
    }

    @ParameterizedTest(name = "{0} p={1}")
    @CsvSource({
            "uniform, 0.5", "uniform, 0.9", "uniform, 0.99",
            "normal, 0.5", "normal, 0.9", "normal, 0.99",
            "exponential, 0.5", "exponential, 0.9", "exponential, 0.99",
            "lognormal, 0.5", "lognormal, 0.95"})
    void withinTwoPercentOfTheTrueRankOnTenThousandSamples(String distribution, double p) {
        Random random = new Random(42);
        DoubleSupplier next = switch (distribution) {
            case "uniform" -> random::nextDouble;
            case "normal" -> random::nextGaussian;
            case "exponential" -> () -> -Math.log(1 - random.nextDouble());
            case "lognormal" -> () -> Math.exp(random.nextGaussian());
            default -> throw new IllegalArgumentException(distribution);
        };
        P2Quantile q = new P2Quantile(p);
        double[] samples = new double[10_000];
        for (int i = 0; i < samples.length; i++) {
            samples[i] = next.getAsDouble();
            q.add(samples[i]);
        }
        Arrays.sort(samples);

        assertThat(q.count()).isEqualTo(10_000);
        assertThat(rankError(samples, q.value(), p)).isLessThanOrEqualTo(0.02);
    }

    @Test
    void sortedInputStillConverges() {
        P2Quantile q = new P2Quantile(0.5);
        for (int i = 1; i <= 10_000; i++) {
            q.add(i);
        }
        assertThat(q.value()).isBetween(4_800.0, 5_200.0);
    }

    @Test
    void theFirstFiveSamplesAreExact() {
        P2Quantile q = new P2Quantile(0.5);
        assertThat(q.value()).isNaN();
        q.add(5);
        q.add(1);
        q.add(3);
        assertThat(q.value()).isEqualTo(3);
        q.add(2);
        q.add(4);
        assertThat(q.value()).isEqualTo(3);
    }

    @Test
    void aConstantStreamIsThatConstant() {
        P2Quantile q = new P2Quantile(0.99);
        for (int i = 0; i < 1_000; i++) {
            q.add(7);
        }
        assertThat(q.value()).isEqualTo(7);
    }

    @Test
    void invalidInput() {
        assertThatThrownBy(() -> new P2Quantile(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new P2Quantile(1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new P2Quantile(0.5).add(Double.NaN)).isInstanceOf(IllegalArgumentException.class);
    }
}
