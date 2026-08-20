/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package sc.fiji.opa.core.spatial;

import org.junit.Assume;
import org.junit.Test;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * Validation stage V2 — envelope coverage and Type I error.
 *
 * <p>The unit tests in {@link SpatialStatisticsTest} check that the estimators
 * return exact values on hand-computable configurations. They say nothing about
 * whether the Monte Carlo machinery is <em>calibrated</em>: whether the
 * pointwise 95% band really contains the observed curve 95% of the time under
 * complete spatial randomness, and whether the global maximum-deviation
 * p-value really rejects at its nominal rate. Those two properties are what
 * every significance claim the plugin makes depends on.</p>
 *
 * <p>The study repeatedly draws a fresh homogeneous Poisson pattern, runs the
 * plugin's own envelope against it, and tabulates how often the observed curve
 * escapes the band and how the global p-values are distributed.</p>
 *
 * <p>Two properties of the implementation shape how the results must be read.
 * First, {@code MonteCarloAnalyzer} forms the global p-value as
 * {@code (1 + #{simulated >= observed}) / (S + 1)}, so under the null it is
 * uniform on the <em>discrete</em> set {@code {1/(S+1), ..., 1}} — a continuous
 * Kolmogorov-Smirnov test against U(0,1) would reject spuriously, so uniformity
 * is assessed with a binned chi-square instead. Second, the pointwise band is
 * the linearly interpolated 2.5th and 97.5th percentile of the S simulated
 * values at each radius, so the nominal escape rate is 5% <em>per radius</em>;
 * radii along one curve are strongly correlated, so the per-radius rate is the
 * meaningful statistic and "escaped at any radius" is not.</p>
 *
 * <p>This is a long-running study, not a unit test, so it is skipped unless
 * {@code -Dopa.calibration=true} is set. Tunables:</p>
 *
 * <pre>
 *   -Dopa.calibration=true                 enable the study
 *   -Dopa.calibration.realisations=1000    independent patterns (default 200)
 *   -Dopa.calibration.simulations=99       envelope simulations (default 99)
 *   -Dopa.calibration.points=200           expected points per pattern
 *   -Dopa.calibration.out=<dir>            report directory
 * </pre>
 *
 * <p>Headline numbers for the release record need 1000 realisations. The
 * default of 200 is a smoke setting that runs in minutes.</p>
 */
public class EnvelopeCalibrationStudy {

    /**
     * Pointwise escape rate the envelope claims. Read from the result rather
     * than assumed: the rank envelope delivers exactly {@code 2k / (S + 1)},
     * which equals 5% only when the simulation count can express it.
     */
    private static double nominalEscape(CaseResult result) {
        return result.envelopeLevel;
    }

    /** Nominal size of the global test the Type I error is compared against. */
    private static final double NOMINAL_ALPHA = 0.05;

    /** Upper-tail chi-square critical values for 9 degrees of freedom. */
    private static final double CHI2_CRIT_9DF_05 = 16.919;
    private static final double CHI2_CRIT_9DF_01 = 21.666;

    /** Bins used for the uniformity test of the global p-value. */
    private static final int P_BINS = 10;

    /**
     * Window side. Large relative to the radii so that the uncorrected G is
     * only mildly edge-biased and the corrections are well conditioned.
     */
    private static final double WINDOW_SIDE = 1000.0;

    /** Largest radius as a fraction of the window side. */
    private static final double MAX_RADIUS_FRACTION = 0.1;

    private static final int RADIUS_COUNT = 10;

    /** One (function, correction) combination to calibrate. */
    private static final class Case {
        final PatternFunction function;
        final EdgeCorrection correction;

        Case(PatternFunction function, EdgeCorrection correction) {
            this.function = function;
            this.correction = correction;
        }

        @Override
        public String toString() {
            return function + " / " + correction;
        }
    }

    private static List<Case> cases() {
        List<Case> cases = new ArrayList<>();
        cases.add(new Case(PatternFunction.K, EdgeCorrection.TRANSLATION));
        cases.add(new Case(PatternFunction.K, EdgeCorrection.BORDER));
        cases.add(new Case(PatternFunction.L, EdgeCorrection.TRANSLATION));
        // G is uncorrected by design, but the analyzer rejects a null
        // correction, so it is driven through the border path.
        cases.add(new Case(PatternFunction.G, EdgeCorrection.BORDER));
        cases.add(new Case(
                PatternFunction.PAIR_CORRELATION, EdgeCorrection.TRANSLATION));
        return cases;
    }

    @Test
    public void envelopeCoverageAndGlobalTestAreCalibrated() throws IOException {
        Assume.assumeTrue(
                "Set -Dopa.calibration=true to run the V2 calibration study.",
                Boolean.getBoolean("opa.calibration"));
        run(System.out);
    }

    public static void main(String[] args) throws IOException {
        run(System.out);
    }

    private static void run(PrintStream console) throws IOException {
        int realisations = intProperty("opa.calibration.realisations", 200);
        int simulations = intProperty("opa.calibration.simulations", 99);
        int points = intProperty("opa.calibration.points", 200);
        Path out = Paths.get(System.getProperty(
                "opa.calibration.out", "validation/v2-envelope-calibration"));
        Files.createDirectories(out);

        RectangularWindow window =
                new RectangularWindow(0.0, 0.0, WINDOW_SIDE, WINDOW_SIDE);
        double[] radii = radii();
        long baseSeed = Long.getLong("opa.calibration.seed", 20260820L);

        StringBuilder report = new StringBuilder();
        header(report, window, radii, realisations, simulations, points, baseSeed);

        boolean allPassed = true;
        for (Case testCase : cases()) {
            CaseResult result = calibrate(
                    testCase, window, radii, realisations, simulations,
                    points, baseSeed, console);
            allPassed &= appendCase(report, testCase, result, radii, realisations);
        }

        report.append("\nOverall: ")
                .append(allPassed ? "PASS" : "FAIL")
                .append('\n');

        Path file = out.resolve("v2-report.txt");
        Files.write(file, report.toString().getBytes(StandardCharsets.UTF_8));
        console.println(report);
        console.println("Report written to " + file.toAbsolutePath());
    }

    /** Accumulated counters for one (function, correction) combination. */
    private static final class CaseResult {
        final int[] escapes;
        final int[] comparableRadii;
        final int[] pBinCounts = new int[P_BINS];
        int testableRuns;
        int rejections;
        double envelopeLevel = Double.NaN;

        CaseResult(int radiusCount) {
            escapes = new int[radiusCount];
            comparableRadii = new int[radiusCount];
        }
    }

    private static CaseResult calibrate(Case testCase,
                                        RectangularWindow window,
                                        double[] radii,
                                        int realisations,
                                        int simulations,
                                        int points,
                                        long baseSeed,
                                        PrintStream console) {
        CaseResult result = new CaseResult(radii.length);
        long caseSeed = baseSeed + 1000003L * testCase.function.ordinal()
                + 101L * testCase.correction.ordinal();

        for (int realisation = 0; realisation < realisations; realisation++) {
            // A fresh pattern and a fresh analyzer seed per realisation, so no
            // two runs share either the observed pattern or its simulations.
            long patternSeed = caseSeed + 2L * realisation;
            long analyzerSeed = caseSeed + 2L * realisation + 1L;

            double[][] pattern = poissonPattern(points, window, patternSeed);
            MonteCarloResult mc = MonteCarloAnalyzer.analyzeUnivariate(
                    testCase.function,
                    pattern,
                    window,
                    radii,
                    testCase.correction,
                    simulations,
                    analyzerSeed);

            accumulate(result, mc);

            if (console != null && realisations >= 50
                    && (realisation + 1) % (realisations / 10) == 0) {
                console.printf(
                        Locale.ROOT,
                        "  %s: %d/%d%n",
                        testCase, realisation + 1, realisations);
            }
        }
        return result;
    }

    private static void accumulate(CaseResult result, MonteCarloResult mc) {
        result.envelopeLevel = mc.getEnvelopeLevel();
        double[] observed = mc.getObserved();
        double[] lower = mc.getLower();
        double[] upper = mc.getUpper();

        for (int i = 0; i < observed.length; i++) {
            // A radius only counts when the band and the observed value both
            // exist; NaN radii carry no coverage information either way.
            if (!Double.isFinite(observed[i])
                    || !Double.isFinite(lower[i])
                    || !Double.isFinite(upper[i])) {
                continue;
            }
            result.comparableRadii[i]++;
            if (observed[i] < lower[i] || observed[i] > upper[i]) {
                result.escapes[i]++;
            }
        }

        double p = mc.getGlobalPValue();
        if (mc.getStatus() == PatternStatus.OK && Double.isFinite(p)) {
            result.testableRuns++;
            if (p <= NOMINAL_ALPHA) result.rejections++;
            int bin = (int) Math.floor(p * P_BINS);
            // p == 1 lands one past the last bin.
            if (bin >= P_BINS) bin = P_BINS - 1;
            if (bin < 0) bin = 0;
            result.pBinCounts[bin]++;
        }
    }

    private static boolean appendCase(StringBuilder report,
                                      Case testCase,
                                      CaseResult result,
                                      double[] radii,
                                      int realisations) {
        report.append("\n--- ").append(testCase).append(" ---\n");
        report.append(String.format(
                Locale.ROOT,
                "%8s  %10s  %-20s  %8s%n",
                "radius", "escape", "95% CI", "n"));

        boolean passed = true;
        int totalEscapes = 0;
        int totalComparable = 0;
        for (int i = 0; i < radii.length; i++) {
            int n = result.comparableRadii[i];
            if (n == 0) {
                report.append(String.format(
                        Locale.ROOT,
                        "%8.2f  %10s  %-20s  %8d%n",
                        radii[i], "n/a", "no comparable band", 0));
                continue;
            }
            double rate = result.escapes[i] / (double) n;
            double[] interval = wilson(result.escapes[i], n);
            double nominal = nominalEscape(result);
            boolean covers = nominal >= interval[0] && nominal <= interval[1];
            passed &= covers;
            totalEscapes += result.escapes[i];
            totalComparable += n;
            report.append(String.format(
                    Locale.ROOT,
                    "%8.2f  %10.4f  [%6.4f, %6.4f]  %8d  %s%n",
                    radii[i], rate, interval[0], interval[1], n,
                    covers ? "" : "<-- outside nominal"));
        }

        if (totalComparable > 0) {
            report.append(String.format(
                    Locale.ROOT,
                    "pooled per-radius escape: %.4f (envelope level %.4f)%n",
                    totalEscapes / (double) totalComparable,
                    nominalEscape(result)));
        }

        // Uniformity of the global p-value, binned because the statistic is
        // discrete on multiples of 1/(S+1).
        if (result.testableRuns > 0) {
            double expectedPerBin = result.testableRuns / (double) P_BINS;
            double chi2 = 0.0;
            for (int bin = 0; bin < P_BINS; bin++) {
                double difference = result.pBinCounts[bin] - expectedPerBin;
                chi2 += difference * difference / expectedPerBin;
            }
            boolean uniform = chi2 <= CHI2_CRIT_9DF_05;
            passed &= uniform;
            report.append(String.format(
                    Locale.ROOT,
                    "global p uniformity: chi2 = %.3f (df 9), "
                            + "crit .05 = %.3f, crit .01 = %.3f -> %s%n",
                    chi2, CHI2_CRIT_9DF_05, CHI2_CRIT_9DF_01,
                    uniform ? "PASS" : "FAIL"));
            report.append("  bin counts: ");
            for (int bin = 0; bin < P_BINS; bin++) {
                report.append(result.pBinCounts[bin]).append(' ');
            }
            report.append(String.format(
                    Locale.ROOT, "(expected %.1f each)%n", expectedPerBin));

            double typeI = result.rejections / (double) result.testableRuns;
            double[] interval = wilson(result.rejections, result.testableRuns);
            boolean sized = NOMINAL_ALPHA >= interval[0]
                    && NOMINAL_ALPHA <= interval[1];
            passed &= sized;
            report.append(String.format(
                    Locale.ROOT,
                    "Type I error at alpha %.2f: %.4f [%6.4f, %6.4f] -> %s%n",
                    NOMINAL_ALPHA, typeI, interval[0], interval[1],
                    sized ? "PASS" : "FAIL"));
        }

        int untestable = realisations - result.testableRuns;
        if (untestable > 0) {
            report.append(String.format(
                    Locale.ROOT,
                    "note: %d of %d runs were not globally testable "
                            + "and are excluded from the p-value results%n",
                    untestable, realisations));
        }
        return passed;
    }

    private static void header(StringBuilder report,
                               RectangularWindow window,
                               double[] radii,
                               int realisations,
                               int simulations,
                               int points,
                               long baseSeed) {
        report.append("V2 - envelope coverage and Type I error\n");
        report.append("=======================================\n");
        report.append(String.format(
                Locale.ROOT,
                "window            %.0f x %.0f%n",
                window.width(), window.height()));
        report.append(String.format(
                Locale.ROOT,
                "expected points   %d (intensity %.3e)%n",
                points, points / window.area()));
        report.append(String.format(
                Locale.ROOT,
                "radii             %.2f to %.2f in %d steps%n",
                radii[0], radii[radii.length - 1], radii.length));
        report.append(String.format(
                Locale.ROOT, "realisations      %d%n", realisations));
        report.append(String.format(
                Locale.ROOT, "simulations       %d%n", simulations));
        report.append(String.format(
                Locale.ROOT, "base seed         %d%n", baseSeed));
        report.append(
                "\nPass criteria: per-radius escape rate covers 0.05 within its\n"
                        + "binomial 95% interval; binned global p-values pass a\n"
                        + "chi-square uniformity test at df 9; empirical Type I error\n"
                        + "covers the nominal 0.05.\n");
    }

    private static double[] radii() {
        double max = WINDOW_SIDE * MAX_RADIUS_FRACTION;
        double step = max / RADIUS_COUNT;
        double[] radii = new double[RADIUS_COUNT];
        for (int i = 0; i < RADIUS_COUNT; i++) {
            radii[i] = step * (i + 1);
        }
        return radii;
    }

    /**
     * A homogeneous Poisson pattern. The point count is fixed rather than
     * Poisson-distributed because the analyzer conditions its simulations on
     * the observed count, so a binomial process is the matching null.
     */
    private static double[][] poissonPattern(int count,
                                             RectangularWindow window,
                                             long seed) {
        Random random = new Random(seed);
        double[][] points = new double[count][2];
        for (int i = 0; i < count; i++) {
            points[i][0] = window.getMinX() + random.nextDouble() * window.width();
            points[i][1] = window.getMinY() + random.nextDouble() * window.height();
        }
        return points;
    }

    /**
     * Wilson score interval for a binomial proportion.
     *
     * <p>The Wald interval used by the obvious {@code p +/- 1.96 * sqrt(pq/n)}
     * formula degenerates to a zero-width interval when no successes are
     * observed, which at a nominal rate of 5% happens routinely at small
     * sample sizes and would report a spurious failure. Wilson stays finite
     * and correctly asymmetric at both boundaries.</p>
     */
    private static double[] wilson(int successes, int trials) {
        if (trials == 0) return new double[]{Double.NaN, Double.NaN};
        double z = 1.96;
        double zSquared = z * z;
        double rate = successes / (double) trials;
        double denominator = 1.0 + zSquared / trials;
        double centre = (rate + zSquared / (2.0 * trials)) / denominator;
        double margin = z / denominator
                * Math.sqrt(rate * (1.0 - rate) / trials
                        + zSquared / (4.0 * trials * trials));
        return new double[]{
                Math.max(0.0, centre - margin),
                Math.min(1.0, centre + margin)};
    }

    private static int intProperty(String key, int fallback) {
        String value = System.getProperty(key);
        if (value == null || value.isEmpty()) return fallback;
        return Integer.parseInt(value.trim());
    }
}
