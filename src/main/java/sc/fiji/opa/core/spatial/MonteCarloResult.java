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
    private final int envelopeRank;
    private final double envelopeLevel;
    private final double saturationRadius;
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
                     int envelopeRank,
                     double envelopeLevel,
                     double saturationRadius,
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
        this.envelopeRank = envelopeRank;
        this.envelopeLevel = envelopeLevel;
        this.saturationRadius = saturationRadius;
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

    /**
     * How far in from each end of the sorted simulated curves the pointwise
     * envelope was drawn: 1 means the simulated minimum and maximum.
     */
    public int getEnvelopeRank() {
        return envelopeRank;
    }

    /**
     * Pointwise escape probability the envelope actually delivers, which is
     * {@code 2 * rank / (simulations + 1)}.
     *
     * <p>Report this rather than a nominal 95%: the two coincide only when the
     * simulation count allows the requested level to be expressed exactly. At
     * the historical default of 99 simulations, for instance, 5% is not
     * reachable and the envelope delivers 4%.</p>
     */
    public double getEnvelopeLevel() {
        return envelopeLevel;
    }

    /** Pointwise confidence the envelope delivers, as a percentage. */
    public double getEnvelopeConfidencePercent() {
        return 100.0 * (1.0 - envelopeLevel);
    }

    /**
     * Radius past which this curve has effectively saturated, or NaN when the
     * function does not saturate.
     *
     * <p>Only nearest-neighbour G and cross-G saturate. Past this radius
     * almost every point already has a neighbour, the simulated curves tie, the
     * pointwise band collapses and the radius stops discriminating. Results
     * there are not wrong, they are empty, and callers should say so rather
     * than presenting a flat envelope as a finding.</p>
     */
    public double getSaturationRadius() {
        return saturationRadius;
    }

    /** How many requested radii lie past {@link #getSaturationRadius()}. */
    public int getSaturatedRadiusCount() {
        if (Double.isNaN(saturationRadius)) return 0;
        int count = 0;
        for (double radius : radii) {
            if (radius >= saturationRadius) count++;
        }
        return count;
    }

    /** True when any requested radius is past saturation. */
    public boolean hasSaturatedRadii() {
        return getSaturatedRadiusCount() > 0;
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
