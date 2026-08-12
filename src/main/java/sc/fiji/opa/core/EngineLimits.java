/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package sc.fiji.opa.core;

/**
 * Resource ceilings the engine itself enforces.
 *
 * <p>These are the three limits {@code MonteCarloAnalyzer} checks before it
 * allocates a simulation matrix, and they carry the exact wording of the
 * rejections a user already sees. They live here rather than on a consumer's
 * parameter class because the engine must refuse an oversized request whoever
 * asked for it, not only when the request came through one particular
 * plugin.</p>
 *
 * <p>Limits that are a <em>consumer's</em> policy rather than the engine's —
 * how many channels an analysis accepts, how many neighbours or histogram bins
 * it will report — deliberately stay with that consumer.</p>
 */
public final class EngineLimits {

    /** Largest Monte Carlo simulation count a single analysis may request. */
    public static final int MAX_SIMULATIONS = 10000;

    /** Largest number of radii a single curve may be evaluated at. */
    public static final int MAX_RADIUS_BINS = 10000;

    /** Largest simulations x radii product one analysis may hold in memory. */
    public static final long MAX_MONTE_CARLO_VALUES = 10000000L;

    private EngineLimits() {
    }
}
