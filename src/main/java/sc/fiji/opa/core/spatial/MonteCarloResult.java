/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package sc.fiji.opa.core.spatial;

import java.util.Arrays;

/**
 * Observed curve, complete-spatial-randomness expectation and Monte Carlo
 * envelope with a global maximum-deviation p-value.
 */
public final class MonteCarloResult {

    private final PatternFunction function;
    private final double[] radii;
    private final double[] observed;
    private final double[] expected;
    private final double[] lower;
    private final double[] upper;
    private final int[] envelopeSampleCounts;
    private final double globalPValue;
    private final double maximumDeviation;
    private final double maximumDeviationRadius;
    private final int simulations;
    private final long seed;
    private final PatternStatus status;
    private final int rankSampleCount;

    MonteCarloResult(PatternFunction function,
                     double[] radii,
                     double[] observed,
                     double[] expected,
                     double[] lower,
                     double[] upper,
                     int[] envelopeSampleCounts,
                     double globalPValue,
                     double maximumDeviation,
                     double maximumDeviationRadius,
                     int simulations,
                     long seed,
                     PatternStatus status,
                     int rankSampleCount) {
        this.function = function;
        this.radii = copy(radii);
        this.observed = copy(observed);
        this.expected = copy(expected);
        this.lower = copy(lower);
        this.upper = copy(upper);
        this.envelopeSampleCounts = copy(envelopeSampleCounts);
        this.globalPValue = globalPValue;
        this.maximumDeviation = maximumDeviation;
        this.maximumDeviationRadius = maximumDeviationRadius;
        this.simulations = simulations;
        this.seed = seed;
        this.status = status;
        this.rankSampleCount = rankSampleCount;
    }

    public PatternFunction getFunction() {
        return function;
    }

    public double[] getRadii() {
        return copy(radii);
    }

    public double[] getObserved() {
        return copy(observed);
    }

    public double[] getExpected() {
        return copy(expected);
    }

    public double[] getLower() {
        return copy(lower);
    }

    public double[] getUpper() {
        return copy(upper);
    }

    /**
     * Number of simulated curves contributing to each pointwise envelope.
     */
    public int[] getEnvelopeSampleCounts() {
        return copy(envelopeSampleCounts);
    }

    public boolean hasCompletePointwiseEnvelope() {
        for (int count : envelopeSampleCounts) {
            if (count != simulations) return false;
        }
        return true;
    }

    public double getGlobalPValue() {
        return globalPValue;
    }

    public double getMaximumDeviation() {
        return maximumDeviation;
    }

    public double getMaximumDeviationRadius() {
        return maximumDeviationRadius;
    }

    public int getSimulations() {
        return simulations;
    }

    public long getSeed() {
        return seed;
    }

    public PatternStatus getStatus() {
        return status;
    }

    /**
     * Number of observed/simulated curves participating in the global rank.
     */
    public int getRankSampleCount() {
        return rankSampleCount;
    }

    public double getMinimumAchievablePValue() {
        return status == PatternStatus.OK && rankSampleCount > 0
                ? 1.0 / rankSampleCount
                : Double.NaN;
    }

    private static double[] copy(double[] values) {
        return Arrays.copyOf(values, values.length);
    }

    private static int[] copy(int[] values) {
        return Arrays.copyOf(values, values.length);
    }
}
