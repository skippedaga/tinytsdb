package io.github.skippedaga.tinytsdb;

import java.util.Arrays;

/**
 * Streaming estimate of one quantile with the P² algorithm (Jain &amp; Chlamtac, 1985): five markers, O(1) memory and
 * time per sample, no samples kept. Exact for the first five samples. Not thread-safe.
 */
public final class P2Quantile {

    private final double p;
    private final double[] height = new double[5];
    private final double[] position = {1, 2, 3, 4, 5};
    private final double[] desired;
    private final double[] increment;
    private long count;

    /** @param p the quantile, strictly between 0 and 1 (0.5 is the median) */
    public P2Quantile(double p) {
        if (!(p > 0 && p < 1)) {
            throw new IllegalArgumentException("p must be in (0, 1), got " + p);
        }
        this.p = p;
        this.desired = new double[]{1, 1 + 2 * p, 1 + 4 * p, 3 + 2 * p, 5};
        this.increment = new double[]{0, p / 2, p, (1 + p) / 2, 1};
    }

    public double quantile() {
        return p;
    }

    public long count() {
        return count;
    }

    public void add(double x) {
        if (Double.isNaN(x)) {
            throw new IllegalArgumentException("NaN sample");
        }
        if (count < 5) {
            height[(int) count] = x;
            count++;
            if (count == 5) {
                Arrays.sort(height);
            }
            return;
        }
        count++;
        int k;
        if (x < height[0]) {
            height[0] = x;
            k = 0;
        } else if (x >= height[4]) {
            height[4] = Math.max(height[4], x);
            k = 3;
        } else {
            k = 0;
            while (k < 3 && x >= height[k + 1]) {
                k++;
            }
        }
        for (int i = k + 1; i < 5; i++) {
            position[i]++;
        }
        for (int i = 0; i < 5; i++) {
            desired[i] += increment[i];
        }
        for (int i = 1; i <= 3; i++) {
            double d = desired[i] - position[i];
            if ((d >= 1 && position[i + 1] - position[i] > 1) || (d <= -1 && position[i - 1] - position[i] < -1)) {
                int sign = d >= 0 ? 1 : -1;
                double q = parabolic(i, sign);
                if (height[i - 1] < q && q < height[i + 1]) {
                    height[i] = q;
                } else {
                    height[i] = linear(i, sign);
                }
                position[i] += sign;
            }
        }
    }

    /** The estimate; NaN before the first sample. */
    public double value() {
        if (count == 0) {
            return Double.NaN;
        }
        if (count < 5) {
            double[] sorted = Arrays.copyOf(height, (int) count);
            Arrays.sort(sorted);
            int index = (int) Math.round(p * (count - 1));
            return sorted[index];
        }
        return height[2];
    }

    private double parabolic(int i, int d) {
        double n0 = position[i - 1];
        double n1 = position[i];
        double n2 = position[i + 1];
        return height[i] + d / (n2 - n0)
                * ((n1 - n0 + d) * (height[i + 1] - height[i]) / (n2 - n1)
                + (n2 - n1 - d) * (height[i] - height[i - 1]) / (n1 - n0));
    }

    private double linear(int i, int d) {
        return height[i] + d * (height[i + d] - height[i]) / (position[i + d] - position[i]);
    }
}
