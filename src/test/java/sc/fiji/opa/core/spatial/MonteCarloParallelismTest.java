/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package sc.fiji.opa.core.spatial;

import org.junit.Test;

import java.util.Random;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

/**
 * Worker count must not change the answer.
 *
 * <p>The aggregate assertions came first and are kept, but on their own they
 * have a blind spot that is worth naming, because the same one has already
 * been found elsewhere in this family. Comparing two finished
 * {@link MonteCarloResult}s across worker counts does <strong>not</strong> pin
 * the indexed merge in {@code MonteCarloAnalyzer}: every consumer of the
 * simulation matrix is a sort (the percentile envelope), a count (the global
 * rank and the p-value) or a sum over the same multiset
 * ({@code exchangeableScales}). Scramble which row holds which permutation and
 * the envelope is unchanged, the counts are unchanged, and the standardized
 * scale changes at most in the last bits of a floating-point sum — which for a
 * small well-conditioned corpus will usually be no change at all. A test that
 * can pass against a broken merge is not evidence.</p>
 *
 * <p>So the merge is asserted directly, on the matrix, in two ways: the matrix
 * is identical element by element whatever the worker count, and row <em>i</em>
 * is the curve of the <em>i</em>-th pattern drawn from the seeded stream. The
 * second is what makes the seed mean anything.</p>
 */
public class MonteCarloParallelismTest {

    private static final RectangularWindow WINDOW =
            new RectangularWindow(0.0, 0.0, 10.0, 10.0);
    private static final double[] RADII = {1.0, 2.5, 4.0};

    // ------------------------------------------------ aggregate equivalence

    @Test
    public void parallelUnivariateSimulationPreservesLegacySeedStream() {
        double[][] points = {
                {1.0, 1.0}, {3.0, 2.0}, {5.0, 6.0},
                {7.0, 4.0}, {8.0, 8.0}, {2.0, 7.0}
        };
        compare(false, points, new double[][]{{2.0, 8.0}}, 41, 987654321L);
    }

    @Test
    public void parallelBivariateSimulationPreservesLegacySeedStream() {
        double[][] source = {{1.0, 1.0}, {3.0, 2.0}, {7.0, 4.0}};
        double[][] target = {{2.0, 8.0}, {5.0, 6.0}, {8.0, 8.0}, {2.0, 7.0}};
        compare(true, source, target, 37, 1234567L);
    }

    // --------------------------------------------- the merge itself, pinned

    @Test
    public void univariateSampleMatrixIsIdenticalAtEveryWorkerCount() {
        double[][] serial = withWorkers(1, new Samples() {
            @Override
            public double[][] get() {
                return MonteCarloAnalyzer.evaluateUnivariateSamples(
                        PatternFunction.K, 6, WINDOW, RADII,
                        EdgeCorrection.TRANSLATION, 41, 987654321L, null);
            }
        });
        for (final int workers : new int[]{2, 3, 8}) {
            double[][] parallel = withWorkers(workers, new Samples() {
                @Override
                public double[][] get() {
                    return MonteCarloAnalyzer.evaluateUnivariateSamples(
                            PatternFunction.K, 6, WINDOW, RADII,
                            EdgeCorrection.TRANSLATION, 41, 987654321L, null);
                }
            });
            assertMatrixEquals("workers=" + workers, serial, parallel);
        }
    }

    @Test
    public void bivariateSampleMatrixIsIdenticalAtEveryWorkerCount() {
        double[][] serial = withWorkers(1, new Samples() {
            @Override
            public double[][] get() {
                return MonteCarloAnalyzer.evaluateBivariateSamples(
                        PatternFunction.CROSS_K, 3, 4, WINDOW, RADII,
                        EdgeCorrection.TRANSLATION, 37, 1234567L, null);
            }
        });
        for (final int workers : new int[]{2, 3, 8}) {
            double[][] parallel = withWorkers(workers, new Samples() {
                @Override
                public double[][] get() {
                    return MonteCarloAnalyzer.evaluateBivariateSamples(
                            PatternFunction.CROSS_K, 3, 4, WINDOW, RADII,
                            EdgeCorrection.TRANSLATION, 37, 1234567L, null);
                }
            });
            assertMatrixEquals("workers=" + workers, serial, parallel);
        }
    }

    @Test
    public void univariateRowHoldsTheCurveOfThatDrawFromTheSeededStream() {
        final int simulations = 17;
        final int pointCount = 5;
        final long seed = 424242L;
        double[][] samples = withWorkers(4, new Samples() {
            @Override
            public double[][] get() {
                return MonteCarloAnalyzer.evaluateUnivariateSamples(
                        PatternFunction.K, pointCount, WINDOW, RADII,
                        EdgeCorrection.TRANSLATION, simulations, seed, null);
            }
        });

        Random random = new Random(seed);
        for (int simulation = 0; simulation < simulations; simulation++) {
            double[] expected = SpatialStatistics.evaluate(
                    PatternFunction.K, draw(pointCount, random), null,
                    WINDOW, RADII, EdgeCorrection.TRANSLATION);
            assertArrayEquals("row " + simulation + " is not the curve of "
                            + "draw " + simulation,
                    expected, samples[simulation], 0.0);
        }
    }

    @Test
    public void bivariateRowHoldsTheCurveOfThatDrawFromTheSeededStream() {
        final int simulations = 13;
        final int sourceCount = 4;
        final int targetCount = 6;
        final long seed = 24680L;
        double[][] samples = withWorkers(8, new Samples() {
            @Override
            public double[][] get() {
                return MonteCarloAnalyzer.evaluateBivariateSamples(
                        PatternFunction.CROSS_K, sourceCount, targetCount,
                        WINDOW, RADII, EdgeCorrection.TRANSLATION,
                        simulations, seed, null);
            }
        });

        Random random = new Random(seed);
        for (int simulation = 0; simulation < simulations; simulation++) {
            // Source first, then target, from the one stream — the order the
            // coordinator draws them in.
            double[][] source = draw(sourceCount, random);
            double[][] target = draw(targetCount, random);
            double[] expected = SpatialStatistics.evaluate(
                    PatternFunction.CROSS_K, source, target,
                    WINDOW, RADII, EdgeCorrection.TRANSLATION);
            assertArrayEquals("row " + simulation + " is not the curve of "
                            + "draw " + simulation,
                    expected, samples[simulation], 0.0);
        }
    }

    @Test
    public void scrambledMergeWouldBeCaught() {
        // A negative control: a comparison that cannot fail is not evidence.
        // Rotating the serial matrix by one row is the smallest thing a broken
        // indexed merge could do, and the row-wise assertion must reject it.
        double[][] samples = withWorkers(1, new Samples() {
            @Override
            public double[][] get() {
                return MonteCarloAnalyzer.evaluateUnivariateSamples(
                        PatternFunction.K, 6, WINDOW, RADII,
                        EdgeCorrection.TRANSLATION, 9, 5L, null);
            }
        });
        double[][] rotated = new double[samples.length][];
        for (int i = 0; i < samples.length; i++) {
            rotated[i] = samples[(i + 1) % samples.length];
        }
        boolean noticed = false;
        try {
            assertMatrixEquals("rotated", samples, rotated);
        } catch (AssertionError expected) {
            noticed = true;
        }
        org.junit.Assert.assertTrue(
                "the row-wise assertion cannot detect a scrambled merge",
                noticed);
    }

    // ------------------------------------------------------------- helpers

    private interface Samples {
        double[][] get();
    }

    private static double[][] withWorkers(int workers, Samples samples) {
        String previous = System.getProperty("opa.parallelism");
        try {
            System.setProperty("opa.parallelism", Integer.toString(workers));
            return samples.get();
        } finally {
            restore("opa.parallelism", previous);
        }
    }

    private static double[][] draw(int count, Random random) {
        double[][] points = new double[count][2];
        for (int i = 0; i < count; i++) {
            points[i][0] = WINDOW.getMinX() + random.nextDouble() * WINDOW.width();
            points[i][1] = WINDOW.getMinY() + random.nextDouble() * WINDOW.height();
        }
        return points;
    }

    private static void assertMatrixEquals(String message,
                                           double[][] expected,
                                           double[][] actual) {
        assertEquals(message + ": row count", expected.length, actual.length);
        for (int row = 0; row < expected.length; row++) {
            assertArrayEquals(message + ": row " + row,
                    expected[row], actual[row], 0.0);
        }
    }

    private static void compare(boolean bivariate, double[][] source,
                                double[][] target, int simulations, long seed) {
        String previous = System.getProperty("opa.parallelism");
        try {
            System.setProperty("opa.parallelism", "1");
            MonteCarloResult serial = analyze(
                    bivariate, source, target, simulations, seed);
            System.setProperty("opa.parallelism", "4");
            MonteCarloResult parallel = analyze(
                    bivariate, source, target, simulations, seed);
            assertArrayEquals(serial.getObserved(), parallel.getObserved(), 0.0);
            assertArrayEquals(serial.getExpected(), parallel.getExpected(), 0.0);
            assertArrayEquals(serial.getLower(), parallel.getLower(), 0.0);
            assertArrayEquals(serial.getUpper(), parallel.getUpper(), 0.0);
            assertArrayEquals(serial.getEnvelopeSampleCounts(),
                    parallel.getEnvelopeSampleCounts());
            assertEquals(serial.getGlobalPValue(), parallel.getGlobalPValue(), 0.0);
            assertEquals(serial.getMaximumDeviation(), parallel.getMaximumDeviation(), 0.0);
            assertEquals(serial.getMaximumDeviationRadius(),
                    parallel.getMaximumDeviationRadius(), 0.0);
            assertEquals(serial.getStatus(), parallel.getStatus());
        } finally {
            restore("opa.parallelism", previous);
        }
    }

    private static MonteCarloResult analyze(
            boolean bivariate, double[][] source, double[][] target,
            int simulations, long seed) {
        if (bivariate) {
            return MonteCarloAnalyzer.analyzeBivariate(
                    PatternFunction.CROSS_K, source, target, WINDOW, RADII,
                    EdgeCorrection.TRANSLATION, simulations, seed);
        }
        return MonteCarloAnalyzer.analyzeUnivariate(
                PatternFunction.L_MINUS_R, source, WINDOW, RADII,
                EdgeCorrection.TRANSLATION, simulations, seed);
    }

    private static void restore(String key, String previous) {
        if (previous == null) System.clearProperty(key);
        else System.setProperty(key, previous);
    }
}
