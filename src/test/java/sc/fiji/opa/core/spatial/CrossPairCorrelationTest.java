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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Cross pair correlation: the bivariate form of g(r).
 *
 * <p>Every input here is fixed and every run is deterministic. Where a test
 * asserts something statistical — attraction shows above 1, independence sits
 * inside the envelope — the pattern and the seed are constants, so the test
 * either holds for that exact computation or it does not. Nothing is left to
 * chance at run time.</p>
 */
public class CrossPairCorrelationTest {

    private static final RectangularWindow WINDOW =
            new RectangularWindow(0.0, 0.0, 20.0, 20.0);
    private static final double[] RADII = {0.5, 1.0, 2.0, 4.0, 6.0};

    // ------------------------------------------------------------ the shape

    @Test
    public void isBivariateAndAnswersAnAToBQuestion() {
        assertTrue(PatternFunction.CROSS_PAIR_CORRELATION.isBivariate());
    }

    @Test
    public void expectationUnderRandomnessIsOneAtEveryRadius() {
        double[] expected = SpatialStatistics.expected(
                PatternFunction.CROSS_PAIR_CORRELATION, RADII, 0.37);
        for (int i = 0; i < RADII.length; i++) {
            assertEquals("radius " + RADII[i], 1.0, expected[i], 0.0);
        }
        // Independent of target intensity, unlike G: a ratio, not a
        // probability.
        assertArrayEquals(expected, SpatialStatistics.expected(
                PatternFunction.CROSS_PAIR_CORRELATION, RADII, 0.0), 0.0);
    }

    @Test
    public void isTheAnnulusDerivativeOfCrossK() {
        double[][] source = grid(4, 4, 2.0, 1.0);
        double[][] target = grid(4, 4, 2.0, 1.4);
        double[] direct = SpatialStatistics.computeCrossPairCorrelation(
                source, target, WINDOW, RADII, EdgeCorrection.TRANSLATION);
        double[] viaK = SpatialStatistics.pairCorrelationFromK(
                SpatialStatistics.computeCrossK(
                        source, target, WINDOW, RADII,
                        EdgeCorrection.TRANSLATION),
                RADII);
        assertArrayEquals(viaK, direct, 0.0);
    }

    // -------------------------------------------------------- attraction

    @Test
    public void coLocatedPatternExceedsOneAtShortRadiiAndRejectsTheNull() {
        // Each target sits a fifth of a unit from its source partner, so at
        // r = 0.5 almost every source-target pair is already counted while
        // independence would predict hardly any.
        double[][] source = grid(5, 5, 3.0, 1.0);
        double[][] target = grid(5, 5, 3.0, 1.2);

        double[] observed = SpatialStatistics.computeCrossPairCorrelation(
                source, target, WINDOW, RADII, EdgeCorrection.TRANSLATION);
        assertTrue("g(0.5) = " + observed[0] + " should show attraction",
                observed[0] > 1.0);

        int simulations = 99;
        MonteCarloResult result = MonteCarloAnalyzer.analyzeBivariate(
                PatternFunction.CROSS_PAIR_CORRELATION, source, target,
                WINDOW, RADII, EdgeCorrection.TRANSLATION, simulations, 31L);

        assertEquals(PatternStatus.OK, result.getStatus());
        assertArrayEquals(observed, result.getObserved(), 0.0);
        assertEquals(1.0 / (simulations + 1), result.getMinimumAchievablePValue(), 0.0);
        assertEquals("a co-located pair should sit at the p-value floor",
                result.getMinimumAchievablePValue(),
                result.getGlobalPValue(), 0.0);
        assertTrue(result.hasCompletePointwiseEnvelope());
        assertTrue("observed should escape the envelope at r=0.5",
                observed[0] > result.getUpper()[0]);
    }

    // ------------------------------------------------------- independence

    @Test
    public void independentPatternIsNotRejectedByTheGlobalTest() {
        // Two patterns drawn from the same uniform law the null simulates,
        // from a fixed seed that is not the analysis seed.
        Random random = new Random(20260811L);
        double[][] source = uniform(60, random);
        double[][] target = uniform(60, random);

        int simulations = 99;
        MonteCarloResult result = MonteCarloAnalyzer.analyzeBivariate(
                PatternFunction.CROSS_PAIR_CORRELATION, source, target,
                WINDOW, RADII, EdgeCorrection.TRANSLATION, simulations, 77L);

        assertEquals(PatternStatus.OK, result.getStatus());
        assertTrue(result.hasCompletePointwiseEnvelope());

        // The global maximum-deviation test is the one that answers "is this
        // pair independent", and it must not reject.
        assertTrue("p = " + result.getGlobalPValue()
                        + " should not reject independence",
                result.getGlobalPValue() > 0.05);

        // The null itself must be centred: the envelope is the 2.5-97.5
        // percentile band of 99 independent simulated curves, so the CSR
        // expectation of 1 has to sit inside it at every radius. That is a
        // structural property of a correct null model, not a coin toss.
        double[] lower = result.getLower();
        double[] upper = result.getUpper();
        for (int i = 0; i < RADII.length; i++) {
            assertTrue("r=" + RADII[i] + " envelope [" + lower[i] + ", "
                            + upper[i] + "] excludes the CSR expectation",
                    lower[i] <= 1.0 && upper[i] >= 1.0);
        }
    }

    @Test
    public void pointwiseEnvelopesAreNotASimultaneousBand() {
        // Recorded as a test rather than a comment, because it is the single
        // most likely misreading of this output and it caught this file's own
        // first draft.
        //
        // A pointwise 2.5-97.5 band at five radii is not a 95% band over the
        // whole curve: an independent pair is expected to poke outside it at
        // some radius roughly one run in five. This exact independent pattern
        // does, at r = 1.0, while the global test correctly does not reject.
        // Asserting pointwise containment would therefore have been a test
        // that fails on correct behaviour.
        Random random = new Random(20260811L);
        double[][] source = uniform(60, random);
        double[][] target = uniform(60, random);
        MonteCarloResult result = MonteCarloAnalyzer.analyzeBivariate(
                PatternFunction.CROSS_PAIR_CORRELATION, source, target,
                WINDOW, RADII, EdgeCorrection.TRANSLATION, 99, 77L);

        int outside = 0;
        for (int i = 0; i < RADII.length; i++) {
            if (result.getObserved()[i] > result.getUpper()[i]
                    || result.getObserved()[i] < result.getLower()[i]) {
                outside++;
            }
        }
        assertTrue("this fixture is meant to leave the pointwise band "
                        + "somewhere; if it no longer does, the point it "
                        + "documents still stands but the fixture is stale",
                outside > 0);
        assertTrue("...while the global test does not reject",
                result.getGlobalPValue() > 0.05);
    }

    // ------------------------------------------------ the border restriction

    @Test
    public void borderCorrectionIsRejectedTheSameWayAsTheUnivariateForm() {
        double[][] source = grid(3, 3, 4.0, 1.0);
        double[][] target = grid(3, 3, 4.0, 2.0);

        String univariate = messageFrom(new Runnable() {
            @Override
            public void run() {
                SpatialStatistics.computePairCorrelation(
                        grid(3, 3, 4.0, 1.0), WINDOW, RADII,
                        EdgeCorrection.BORDER);
            }
        });
        String bivariate = messageFrom(new Runnable() {
            @Override
            public void run() {
                SpatialStatistics.computeCrossPairCorrelation(
                        grid(3, 3, 4.0, 1.0), grid(3, 3, 4.0, 2.0),
                        WINDOW, RADII, EdgeCorrection.BORDER);
            }
        });

        // Same reason, same remedy, same shape — only the statistic's name
        // differs. A rule that held for one form and not the other would be
        // worse than the restriction.
        assertEquals("Pair correlation cannot use border correction because "
                        + "the radius-dependent risk set can make K increments "
                        + "negative; use translation or no edge correction.",
                univariate);
        assertEquals("Cross pair correlation cannot use border correction "
                        + "because the radius-dependent risk set can make K "
                        + "increments negative; use translation or no edge "
                        + "correction.",
                bivariate);
        assertTrue(univariate.contains(
                "risk set can make K increments negative"));
        assertTrue(bivariate.contains(
                "risk set can make K increments negative"));

        // And through the null model, where a consumer will actually meet it.
        assertEquals(bivariate, messageFrom(new Runnable() {
            @Override
            public void run() {
                MonteCarloAnalyzer.analyzeBivariate(
                        PatternFunction.CROSS_PAIR_CORRELATION,
                        grid(3, 3, 4.0, 1.0), grid(3, 3, 4.0, 2.0),
                        WINDOW, RADII, EdgeCorrection.BORDER, 9, 1L);
            }
        }));
        assertFalse(source.length == 0 || target.length == 0);
    }

    @Test
    public void translationAndNoCorrectionAreBothAccepted() {
        double[][] source = grid(4, 4, 3.0, 1.0);
        double[][] target = grid(4, 4, 3.0, 1.5);
        for (EdgeCorrection correction : new EdgeCorrection[]{
                EdgeCorrection.NONE, EdgeCorrection.TRANSLATION}) {
            double[] values = SpatialStatistics.computeCrossPairCorrelation(
                    source, target, WINDOW, RADII, correction);
            assertEquals(RADII.length, values.length);
            for (double value : values) {
                assertTrue(correction + " produced " + value,
                        Double.isNaN(value) || value >= 0.0);
            }
        }
    }

    @Test
    public void univariateAnalysisRejectsIt() {
        assertEquals("A univariate pattern function is required.",
                messageFrom(new Runnable() {
                    @Override
                    public void run() {
                        MonteCarloAnalyzer.analyzeUnivariate(
                                PatternFunction.CROSS_PAIR_CORRELATION,
                                grid(3, 3, 4.0, 1.0), WINDOW, RADII,
                                EdgeCorrection.TRANSLATION, 9, 1L);
                    }
                }));
    }

    @Test
    public void anEmptyChannelIsUndefinedRatherThanZero() {
        MonteCarloResult result = MonteCarloAnalyzer.analyzeBivariate(
                PatternFunction.CROSS_PAIR_CORRELATION,
                grid(3, 3, 4.0, 1.0), new double[0][2],
                WINDOW, RADII, EdgeCorrection.TRANSLATION, 9, 1L);
        assertEquals(PatternStatus.INSUFFICIENT_POINTS, result.getStatus());
        for (double value : result.getObserved()) {
            assertTrue("empty target must not report 0.0", Double.isNaN(value));
        }
    }

    // ------------------------------------------------------- determinism

    @Test
    public void aFixedSeedReproducesAcrossWorkerCounts() {
        double[][] source = grid(5, 5, 3.0, 1.0);
        double[][] target = grid(5, 5, 3.0, 2.3);
        MonteCarloResult reference = null;
        String previous = System.getProperty("opa.parallelism");
        try {
            for (int workers : new int[]{1, 2, 4, 8}) {
                System.setProperty("opa.parallelism", Integer.toString(workers));
                MonteCarloResult result = MonteCarloAnalyzer.analyzeBivariate(
                        PatternFunction.CROSS_PAIR_CORRELATION, source, target,
                        WINDOW, RADII, EdgeCorrection.TRANSLATION, 49, 909090L);
                if (reference == null) {
                    reference = result;
                    continue;
                }
                String at = "workers=" + workers;
                assertArrayEquals(at, reference.getObserved(),
                        result.getObserved(), 0.0);
                assertArrayEquals(at, reference.getLower(),
                        result.getLower(), 0.0);
                assertArrayEquals(at, reference.getUpper(),
                        result.getUpper(), 0.0);
                assertEquals(at, reference.getGlobalPValue(),
                        result.getGlobalPValue(), 0.0);
                assertEquals(at, reference.getSeed(), result.getSeed());
            }
        } finally {
            if (previous == null) System.clearProperty("opa.parallelism");
            else System.setProperty("opa.parallelism", previous);
        }
    }

    @Test
    public void theSimulationMatrixItselfIsIdenticalAcrossWorkerCounts() {
        // The aggregate check above is blind to which row holds which
        // permutation; this is not. See MonteCarloParallelismTest.
        double[][] serial = samples(1);
        for (int workers : new int[]{2, 4, 8}) {
            double[][] parallel = samples(workers);
            assertEquals(serial.length, parallel.length);
            for (int row = 0; row < serial.length; row++) {
                assertArrayEquals("workers=" + workers + " row " + row,
                        serial[row], parallel[row], 0.0);
            }
        }
    }

    private static double[][] samples(int workers) {
        String previous = System.getProperty("opa.parallelism");
        try {
            System.setProperty("opa.parallelism", Integer.toString(workers));
            return MonteCarloAnalyzer.evaluateBivariateSamples(
                    PatternFunction.CROSS_PAIR_CORRELATION, 5, 7, WINDOW,
                    RADII, EdgeCorrection.TRANSLATION, 23, 5150L, null);
        } finally {
            if (previous == null) System.clearProperty("opa.parallelism");
            else System.setProperty("opa.parallelism", previous);
        }
    }

    // ---------------------------------------------------------- fixtures

    private static double[][] grid(int columns, int rows,
                                   double spacing, double offset) {
        double[][] points = new double[columns * rows][2];
        int index = 0;
        for (int y = 0; y < rows; y++) {
            for (int x = 0; x < columns; x++) {
                points[index][0] = offset + x * spacing;
                points[index][1] = offset + y * spacing;
                index++;
            }
        }
        return points;
    }

    private static double[][] uniform(int count, Random random) {
        double[][] points = new double[count][2];
        for (int i = 0; i < count; i++) {
            points[i][0] = WINDOW.getMinX() + random.nextDouble() * WINDOW.width();
            points[i][1] = WINDOW.getMinY() + random.nextDouble() * WINDOW.height();
        }
        return points;
    }

    private static String messageFrom(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException rejected) {
            return rejected.getMessage();
        }
        fail("expected a rejection");
        return null;
    }
}
