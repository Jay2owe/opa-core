/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package sc.fiji.opa.core.spatial;

import ij.IJ;
import sc.fiji.opa.core.AnalysisCancelledException;
import sc.fiji.opa.core.EngineLimits;
import sc.fiji.opa.core.ProgressListener;

import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Deterministic complete-spatial-randomness envelopes for supported curves.
 */
public final class MonteCarloAnalyzer {

    /**
     * Pointwise escape probability the envelope aims for. The level actually
     * delivered depends on the simulation count; see {@link #envelopeRank}.
     */
    public static final double NOMINAL_ENVELOPE_ALPHA = 0.05;

    /**
     * Expected value of G past which a radius is treated as saturated.
     *
     * <p>Nearest-neighbour G is a cumulative distribution, so it climbs to 1
     * and stops. Once nearly every point has a neighbour inside r, the
     * simulated curves all take the same value there, the pointwise envelope
     * collapses to a point, and the radius can neither be escaped nor
     * contribute to the global test. That is correct behaviour, but a user who
     * asked for those radii is getting nothing back from them and should be
     * told rather than left to wonder why the band went flat.</p>
     */
    public static final double G_SATURATION_EXPECTATION = 0.99;

    private MonteCarloAnalyzer() {
    }

    public static MonteCarloResult analyzeUnivariate(PatternFunction function,
                                                     double[][] points,
                                                     RectangularWindow window,
                                                     double[] radii,
                                                     EdgeCorrection correction,
                                                     int simulations,
                                                     long seed) {
        return analyzeUnivariate(
                function,
                points,
                window,
                radii,
                correction,
                simulations,
                seed,
                null);
    }

    public static MonteCarloResult analyzeUnivariate(PatternFunction function,
                                                     double[][] points,
                                                     RectangularWindow window,
                                                     double[] radii,
                                                     EdgeCorrection correction,
                                                     int simulations,
                                                     long seed,
                                                     ProgressListener progress) {
        if (function == null || function.isBivariate()) {
            throw new IllegalArgumentException(
                    "A univariate pattern function is required.");
        }
        validateSimulationWork(simulations, radii);
        double[] observed = SpatialStatistics.evaluate(
                function, points, null, window, radii, correction);
        double intensity = points.length / window.area();
        double[] expected = SpatialStatistics.expected(function, radii, intensity);
        if (points.length < 2) {
            reportProgress(progress, 1.0, "Point pattern is undefined");
            checkCancelled();
            MonteCarloResult result = undefined(
                    function,
                    radii,
                    observed,
                    expected,
                    simulations,
                    seed,
                    PatternStatus.INSUFFICIENT_POINTS,
                    saturationRadius(function, intensity));
            checkCancelled();
            return result;
        }

        double[][] samples = evaluateUnivariateSamples(
                function, points.length, window, radii, correction,
                simulations, seed, progress);
        MonteCarloResult result = summarize(
                function, radii, observed, expected, samples, simulations, seed,
                saturationRadius(function, intensity));
        checkCancelled();
        return result;
    }

    public static MonteCarloResult analyzeBivariate(PatternFunction function,
                                                    double[][] source,
                                                    double[][] target,
                                                    RectangularWindow window,
                                                    double[] radii,
                                                    EdgeCorrection correction,
                                                    int simulations,
                                                    long seed) {
        return analyzeBivariate(
                function,
                source,
                target,
                window,
                radii,
                correction,
                simulations,
                seed,
                null);
    }

    public static MonteCarloResult analyzeBivariate(PatternFunction function,
                                                    double[][] source,
                                                    double[][] target,
                                                    RectangularWindow window,
                                                    double[] radii,
                                                    EdgeCorrection correction,
                                                    int simulations,
                                                    long seed,
                                                    ProgressListener progress) {
        if (function == null || !function.isBivariate()) {
            throw new IllegalArgumentException(
                    "A bivariate pattern function is required.");
        }
        validateSimulationWork(simulations, radii);
        double[] observed = SpatialStatistics.evaluate(
                function, source, target, window, radii, correction);
        double targetIntensity = target.length / window.area();
        double[] expected = SpatialStatistics.expected(
                function, radii, targetIntensity);
        if (source.length == 0 || target.length == 0) {
            reportProgress(progress, 1.0, "Point pattern is undefined");
            checkCancelled();
            MonteCarloResult result = undefined(
                    function,
                    radii,
                    observed,
                    expected,
                    simulations,
                    seed,
                    PatternStatus.INSUFFICIENT_POINTS,
                    saturationRadius(function, targetIntensity));
            checkCancelled();
            return result;
        }

        double[][] samples = evaluateBivariateSamples(
                function, source.length, target.length, window, radii,
                correction, simulations, seed, progress);
        MonteCarloResult result = summarize(
                function, radii, observed, expected, samples, simulations, seed,
                saturationRadius(function, targetIntensity));
        checkCancelled();
        return result;
    }

    private static MonteCarloResult summarize(PatternFunction function,
                                              double[] radii,
                                              double[] observed,
                                              double[] expected,
                                              double[][] samples,
                                              int simulations,
                                              long seed,
                                              double saturationRadius) {
        int radiusCount = radii.length;
        double[] lower = new double[radiusCount];
        double[] upper = new double[radiusCount];
        int[] envelopeSampleCounts = new int[radiusCount];

        int envelopeRank = envelopeRank(simulations);
        for (int radiusIndex = 0; radiusIndex < radiusCount; radiusIndex++) {
            checkCancelled();
            double[] finite = finiteColumn(samples, radiusIndex);
            envelopeSampleCounts[radiusIndex] = finite.length;
            if (finite.length != simulations) {
                lower[radiusIndex] = Double.NaN;
                upper[radiusIndex] = Double.NaN;
                continue;
            }
            Arrays.sort(finite);
            lower[radiusIndex] = finite[envelopeRank - 1];
            upper[radiusIndex] = finite[finite.length - envelopeRank];
        }

        double[] exchangeableScales = exchangeableScales(observed, samples);

        // A curve joins the global rank when it is estimable at any radius.
        // That is deliberately independent of whether a comparable radius
        // exists, so a run where the simulations could not be estimated is
        // still reported as an incomplete rank rather than as no valid radii.
        int rankSampleCount = isEstimable(observed, expected) ? 1 : 0;
        for (double[] sample : samples) {
            checkCancelled();
            if (isEstimable(sample, expected)) rankSampleCount++;
        }
        boolean completeRank = rankSampleCount == simulations + 1;

        double observedMaximum = standardizedMaximum(
                observed, expected, exchangeableScales);
        boolean comparable = !Double.isNaN(observedMaximum);
        int asOrMoreExtreme = 1;
        if (comparable) {
            for (double[] sample : samples) {
                checkCancelled();
                double simulatedMaximum = standardizedMaximum(
                        sample, expected, exchangeableScales);
                if (Double.isNaN(simulatedMaximum)) {
                    comparable = false;
                    break;
                }
                if (simulatedMaximum >= observedMaximum) asOrMoreExtreme++;
            }
        }
        boolean testable = completeRank && comparable;
        double globalP = testable
                ? asOrMoreExtreme / (double) rankSampleCount
                : Double.NaN;

        // The reported radius is the argmax of the same standardized statistic
        // the p-value ranks, so the two describe the same feature of the curve.
        // The deviation itself stays raw, in the curve's own units. Without a
        // comparable radius there is no standardized scale, so fall back to the
        // largest raw departure from the theoretical expectation.
        int maximumIndex = -1;
        double maximumScore = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < observed.length; i++) {
            checkCancelled();
            if (!Double.isFinite(observed[i]) || !Double.isFinite(expected[i])) continue;
            double deviation = Math.abs(observed[i] - expected[i]);
            double scale = exchangeableScales[i];
            double score;
            if (!comparable) {
                score = deviation;
            } else if (!Double.isFinite(scale)) {
                continue;
            } else {
                score = standardize(deviation, scale);
            }
            if (maximumIndex < 0 || score > maximumScore) {
                maximumIndex = i;
                maximumScore = score;
            }
        }
        double maximumDeviation = maximumIndex < 0
                ? Double.NaN
                : Math.abs(observed[maximumIndex] - expected[maximumIndex]);
        double maximumRadius = maximumIndex < 0
                ? Double.NaN
                : radii[maximumIndex];

        return new MonteCarloResult(
                function,
                radii,
                observed,
                expected,
                lower,
                upper,
                envelopeSampleCounts,
                envelopeRank,
                envelopeLevel(simulations, envelopeRank),
                saturationRadius,
                globalP,
                maximumDeviation,
                maximumRadius,
                simulations,
                seed,
                rankSampleCount == 0
                        ? PatternStatus.NO_VALID_RADII
                        : testable
                                ? PatternStatus.OK
                                : completeRank
                                        ? PatternStatus.NO_VALID_RADII
                                        : PatternStatus.INCOMPLETE_MONTE_CARLO,
                rankSampleCount);
    }

    /**
     * Deviation expressed in units of the pooled spread at one radius.
     *
     * <p>A zero spread means every estimable curve took the same value there,
     * so the observed curve is exactly typical of the null however far the
     * shared value sits from the theoretical expectation. Such a radius
     * discriminates nothing and contributes nothing to the maximum. Mapping it
     * to infinity instead would saturate the statistic for the observed curve
     * and every simulation alike, tie them all, and force the p-value to 1.
     * Nearest-neighbour G does exactly this at radii where it has saturated
     * at 1, as do K and its derived curves at a smallest radius closer than
     * any pair in the pattern.</p>
     */
    private static double standardize(double deviation, double scale) {
        return scale > 0.0 ? deviation / scale : 0.0;
    }

    /**
     * True when a curve has at least one radius where both it and the
     * theoretical expectation are finite.
     */
    private static boolean isEstimable(double[] values, double[] expected) {
        for (int i = 0; i < values.length; i++) {
            if (Double.isFinite(values[i]) && Double.isFinite(expected[i])) {
                return true;
            }
        }
        return false;
    }

    private static MonteCarloResult undefined(PatternFunction function,
                                              double[] radii,
                                              double[] observed,
                                              double[] expected,
                                              int simulations,
                                              long seed,
                                              PatternStatus status,
                                              double saturationRadius) {
        double[] undefined = new double[radii.length];
        Arrays.fill(undefined, Double.NaN);
        int[] envelopeSampleCounts = new int[radii.length];
        int envelopeRank = envelopeRank(simulations);
        return new MonteCarloResult(
                function,
                radii,
                observed,
                expected,
                undefined,
                undefined,
                envelopeSampleCounts,
                envelopeRank,
                envelopeLevel(simulations, envelopeRank),
                saturationRadius,
                Double.NaN,
                Double.NaN,
                Double.NaN,
                simulations,
                seed,
                status,
                0);
    }

    private static double[][] generate(int count,
                                       RectangularWindow window,
                                       Random random) {
        double[][] points = new double[count][2];
        for (int i = 0; i < count; i++) {
            if ((i & 255) == 0) checkCancelled();
            points[i][0] = window.getMinX() + random.nextDouble() * window.width();
            points[i][1] = window.getMinY() + random.nextDouble() * window.height();
        }
        return points;
    }

    /*
     * Package-private, not private, so MonteCarloParallelismTest can assert
     * the simulation matrix element by element.
     *
     * Comparing only the finished MonteCarloResult across worker counts does
     * not pin the indexed merge below. Every consumer of `samples` in
     * summarize() is a sort (the percentile envelope), a count (the rank and
     * the p-value) or a sum over the same multiset (exchangeableScales), so
     * scrambling which row holds which permutation leaves the aggregate
     * result identical or different only in the last bits. The contract that
     * actually matters -- row i holds the curve of the i-th pattern drawn from
     * the seeded stream -- is only testable on the matrix itself.
     */
    static double[][] evaluateUnivariateSamples(
            final PatternFunction function,
            int pointCount,
            final RectangularWindow window,
            final double[] radii,
            final EdgeCorrection correction,
            int simulations,
            long seed,
            ProgressListener progress) {
        final double[][] samples = new double[simulations][radii.length];
        int workers = workerCount(simulations);
        if (workers == 1) {
            Random random = new Random(seed);
            for (int simulation = 0; simulation < simulations; simulation++) {
                checkCancelled();
                double[][] randomPoints = generate(pointCount, window, random);
                samples[simulation] = SpatialStatistics.evaluate(
                        function, randomPoints, null, window, radii, correction);
                completed(progress, simulation + 1, simulations);
            }
            return samples;
        }

        ExecutorService executor = Executors.newFixedThreadPool(workers);
        CompletionService<IndexedSample> completed =
                new ExecutorCompletionService<IndexedSample>(executor);
        Random random = new Random(seed);
        int submitted = 0;
        int finished = 0;
        int windowSize = workers * 2;
        try {
            while (finished < simulations) {
                while (submitted < simulations && submitted - finished < windowSize) {
                    checkCancelled();
                    final int index = submitted++;
                    final double[][] randomPoints = generate(pointCount, window, random);
                    completed.submit(new Callable<IndexedSample>() {
                        @Override
                        public IndexedSample call() {
                            return new IndexedSample(index, SpatialStatistics.evaluate(
                                    function, randomPoints, null,
                                    window, radii, correction));
                        }
                    });
                }
                IndexedSample sample = take(completed);
                samples[sample.index] = sample.values;
                finished++;
                completed(progress, finished, simulations);
            }
            return samples;
        } finally {
            stop(executor);
        }
    }

    /*
     * Package-private, not private, so MonteCarloParallelismTest can assert
     * the simulation matrix element by element.
     *
     * Comparing only the finished MonteCarloResult across worker counts does
     * not pin the indexed merge below. Every consumer of `samples` in
     * summarize() is a sort (the percentile envelope), a count (the rank and
     * the p-value) or a sum over the same multiset (exchangeableScales), so
     * scrambling which row holds which permutation leaves the aggregate
     * result identical or different only in the last bits. The contract that
     * actually matters -- row i holds the curve of the i-th pattern drawn from
     * the seeded stream -- is only testable on the matrix itself.
     */
    static double[][] evaluateBivariateSamples(
            final PatternFunction function,
            int sourceCount,
            int targetCount,
            final RectangularWindow window,
            final double[] radii,
            final EdgeCorrection correction,
            int simulations,
            long seed,
            ProgressListener progress) {
        final double[][] samples = new double[simulations][radii.length];
        int workers = workerCount(simulations);
        if (workers == 1) {
            Random random = new Random(seed);
            for (int simulation = 0; simulation < simulations; simulation++) {
                checkCancelled();
                double[][] randomSource = generate(sourceCount, window, random);
                double[][] randomTarget = generate(targetCount, window, random);
                samples[simulation] = SpatialStatistics.evaluate(
                        function, randomSource, randomTarget,
                        window, radii, correction);
                completed(progress, simulation + 1, simulations);
            }
            return samples;
        }

        ExecutorService executor = Executors.newFixedThreadPool(workers);
        CompletionService<IndexedSample> completed =
                new ExecutorCompletionService<IndexedSample>(executor);
        Random random = new Random(seed);
        int submitted = 0;
        int finished = 0;
        int windowSize = workers * 2;
        try {
            while (finished < simulations) {
                while (submitted < simulations && submitted - finished < windowSize) {
                    checkCancelled();
                    final int index = submitted++;
                    final double[][] randomSource = generate(sourceCount, window, random);
                    final double[][] randomTarget = generate(targetCount, window, random);
                    completed.submit(new Callable<IndexedSample>() {
                        @Override
                        public IndexedSample call() {
                            return new IndexedSample(index, SpatialStatistics.evaluate(
                                    function, randomSource, randomTarget,
                                    window, radii, correction));
                        }
                    });
                }
                IndexedSample sample = take(completed);
                samples[sample.index] = sample.values;
                finished++;
                completed(progress, finished, simulations);
            }
            return samples;
        } finally {
            stop(executor);
        }
    }

    private static void completed(
            ProgressListener progress, int completed, int simulations) {
        checkCancelled();
        reportProgress(progress, completed / (double) simulations,
                "Monte Carlo simulation " + completed + " of " + simulations);
        checkCancelled();
    }

    private static int workerCount(int tasks) {
        if (tasks < 2) return 1;
        int configured = Integer.getInteger("opa.parallelism", 0).intValue();
        int available = Runtime.getRuntime().availableProcessors();
        int desired = configured > 0 ? configured : Math.min(available, 8);
        return Math.max(1, Math.min(tasks, desired));
    }

    private static IndexedSample take(
            CompletionService<IndexedSample> completed) {
        try {
            checkCancelled();
            Future<IndexedSample> future = completed.take();
            return future.get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AnalysisCancelledException();
        } catch (ExecutionException failed) {
            Throwable cause = failed.getCause();
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            if (cause instanceof Error) throw (Error) cause;
            throw new IllegalStateException("Monte Carlo simulation failed.", cause);
        }
    }

    private static void stop(ExecutorService executor) {
        executor.shutdownNow();
        try {
            executor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static final class IndexedSample {
        final int index;
        final double[] values;

        IndexedSample(int index, double[] values) {
            this.index = index;
            this.values = values;
        }
    }

    private static double[] finiteColumn(double[][] values, int column) {
        int count = 0;
        for (double[] row : values) {
            if (Double.isFinite(row[column])) count++;
        }
        double[] finite = new double[count];
        int index = 0;
        for (double[] row : values) {
            if (Double.isFinite(row[column])) finite[index++] = row[column];
        }
        return finite;
    }

    private static double[] exchangeableScales(double[] observed,
                                               double[][] samples) {
        double[] scales = new double[observed.length];
        for (int column = 0; column < scales.length; column++) {
            checkCancelled();
            int count = Double.isFinite(observed[column]) ? 1 : 0;
            for (double[] sample : samples) {
                if (Double.isFinite(sample[column])) count++;
            }
            // A radius where fewer than two curves are estimable carries no
            // comparative information: its spread cannot be estimated, so it
            // is excluded from the standardized maximum for every curve alike.
            // Scaling a lone observed value by zero would otherwise make it
            // infinitely extreme and force the p-value to its minimum.
            if (count < 2) {
                scales[column] = Double.NaN;
                continue;
            }
            double[] values = new double[count];
            int index = 0;
            if (Double.isFinite(observed[column])) {
                values[index++] = observed[column];
            }
            for (double[] sample : samples) {
                if (Double.isFinite(sample[column])) {
                    values[index++] = sample[column];
                }
            }
            double centre = mean(values);
            scales[column] = standardDeviation(values, centre);
        }
        return scales;
    }

    private static double standardizedMaximum(double[] values,
                                              double[] expected,
                                              double[] standardDeviations) {
        double maximum = 0.0;
        boolean found = false;
        for (int i = 0; i < values.length; i++) {
            if (!Double.isFinite(values[i]) || !Double.isFinite(expected[i])) continue;
            if (!Double.isFinite(standardDeviations[i])) continue;
            double standardized = standardize(
                    Math.abs(values[i] - expected[i]), standardDeviations[i]);
            if (!found || standardized > maximum) maximum = standardized;
            found = true;
        }
        return found ? maximum : Double.NaN;
    }

    /**
     * How far in from each end of the sorted simulated values the pointwise
     * envelope is drawn.
     *
     * <p>Taking the k-th smallest and k-th largest of S simulated values gives
     * an envelope whose pointwise escape probability is exactly
     * {@code 2k / (S + 1)} when the observed curve is exchangeable with the
     * simulations, because the observed curve's rank among all S+1 curves is
     * then uniform. This replaces an earlier construction that interpolated
     * the 2.5th and 97.5th percentiles of the S simulated values: that band
     * looked like a 95% envelope but escaped 6.9% of the time at S=99 and
     * 9.6% at S=39, because an interpolated percentile of S values does not
     * land on the rank boundary a 5% escape rate requires. Measured by
     * {@code EnvelopeCalibrationStudy} in this module's test sources, over
     * 1,000 complete-spatial-randomness patterns.</p>
     *
     * <p>The rank is rounded down so that the envelope errs wide, and it is
     * never less than one. {@link #envelopeLevel} reports the level actually
     * achieved, which equals {@link #NOMINAL_ENVELOPE_ALPHA} exactly only when
     * {@code S + 1} is a multiple of {@code 2 / alpha} — for the default 5%
     * that means 39, 79, 119, 159 or 199 simulations. Callers should display
     * the achieved level rather than assuming the nominal one.</p>
     */
    static int envelopeRank(int simulations) {
        int rank = (int) Math.floor(
                NOMINAL_ENVELOPE_ALPHA * (simulations + 1) / 2.0);
        return Math.max(1, Math.min(rank, (simulations + 1) / 2));
    }

    /**
     * Radius past which a nearest-neighbour curve has effectively saturated,
     * or NaN for functions that do not saturate.
     *
     * <p>Under complete spatial randomness G(r) = 1 - exp(-lambda*pi*r^2), so
     * the radius at which it reaches {@link #G_SATURATION_EXPECTATION} is
     * {@code sqrt(-ln(1 - threshold) / (lambda * pi))}. Only G and cross-G
     * saturate; K and its derived curves grow without bound.</p>
     */
    static double saturationRadius(PatternFunction function, double intensity) {
        if (function != PatternFunction.G && function != PatternFunction.CROSS_G) {
            return Double.NaN;
        }
        if (!(intensity > 0.0) || !Double.isFinite(intensity)) return Double.NaN;
        return Math.sqrt(
                -Math.log(1.0 - G_SATURATION_EXPECTATION) / (intensity * Math.PI));
    }

    /** Pointwise escape probability the chosen rank actually delivers. */
    static double envelopeLevel(int simulations, int rank) {
        return 2.0 * rank / (simulations + 1);
    }

    private static double mean(double[] values) {
        double sum = 0.0;
        for (double value : values) sum += value;
        return sum / values.length;
    }

    private static double standardDeviation(double[] values, double mean) {
        if (values.length < 2) return 0.0;
        double sumSquares = 0.0;
        for (double value : values) {
            double difference = value - mean;
            sumSquares += difference * difference;
        }
        return Math.sqrt(sumSquares / (values.length - 1));
    }

    private static void validateSimulationWork(int simulations,
                                               double[] radii) {
        if (simulations < 1 || simulations > EngineLimits.MAX_SIMULATIONS) {
            throw new IllegalArgumentException(
                    "Simulation count must be between 1 and "
                            + EngineLimits.MAX_SIMULATIONS + ".");
        }
        if (radii == null) {
            throw new IllegalArgumentException("Radii must not be null.");
        }
        if (radii.length == 0) {
            throw new IllegalArgumentException(
                    "At least one pattern radius is required.");
        }
        if (radii.length > EngineLimits.MAX_RADIUS_BINS) {
            throw new IllegalArgumentException(
                    "Pattern radius count must not exceed "
                            + EngineLimits.MAX_RADIUS_BINS + ".");
        }
        if ((long) simulations * radii.length
                > EngineLimits.MAX_MONTE_CARLO_VALUES) {
            throw new IllegalArgumentException(
                    "Monte Carlo simulations multiplied by radius count "
                            + "must not exceed "
                            + EngineLimits.MAX_MONTE_CARLO_VALUES + ".");
        }
    }

    private static void reportProgress(ProgressListener progress,
                                       double fraction,
                                       String message) {
        if (progress != null) progress.onProgress(fraction, message);
    }

    private static void checkCancelled() {
        if (IJ.escapePressed()) throw new AnalysisCancelledException();
    }
}
