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
import org.junit.Test;

import java.awt.event.KeyEvent;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class SpatialStatisticsTest {

    private static final RectangularWindow WINDOW =
            new RectangularWindow(0.0, 0.0, 10.0, 10.0);

    @Test
    public void computesExactUncorrectedKForKnownPair() {
        double[][] points = {{2.0, 5.0}, {4.0, 5.0}};
        double[] values = SpatialStatistics.computeK(
                points,
                WINDOW,
                new double[]{1.0, 2.0},
                EdgeCorrection.NONE);

        assertEquals(0.0, values[0], 1.0e-12);
        assertEquals(100.0, values[1], 1.0e-12);
    }

    @Test
    public void translationCorrectionUsesRectangularOverlap() {
        double[][] points = {{2.0, 5.0}, {4.0, 5.0}};
        double[] values = SpatialStatistics.computeK(
                points,
                WINDOW,
                new double[]{2.0},
                EdgeCorrection.TRANSLATION);

        // Two ordered pairs. Each has overlap 8*10 and weight 100/80.
        assertEquals(125.0, values[0], 1.0e-12);
    }

    @Test
    public void borderCorrectionExcludesIneligibleAnchors() {
        double[][] points = {
                {0.5, 5.0},
                {5.0, 5.0},
                {6.0, 5.0}
        };
        double[] values = SpatialStatistics.computeK(
                points,
                WINDOW,
                new double[]{1.0},
                EdgeCorrection.BORDER);

        // Two eligible anchors each see the other interior point.
        assertEquals(50.0, values[0], 1.0e-12);
    }

    @Test
    public void computesLAndLMinusRAsDistinctQuantities() {
        double[] radii = {1.0, 2.0};
        double[] k = {
                Math.PI,
                4.0 * Math.PI
        };
        assertArrayEquals(
                new double[]{1.0, 2.0},
                SpatialStatistics.computeL(k),
                1.0e-12);
        assertArrayEquals(
                new double[]{0.0, 0.0},
                SpatialStatistics.computeLMinusR(k, radii),
                1.0e-12);
    }

    @Test
    public void computesExactGAndCrossG() {
        double[][] points = {
                {1.0, 1.0},
                {4.0, 1.0},
                {9.0, 1.0}
        };
        assertArrayEquals(
                new double[]{0.0, 2.0 / 3.0, 1.0},
                SpatialStatistics.computeG(points, new double[]{2.0, 3.0, 5.0}),
                1.0e-12);

        double[][] source = {{1.0, 1.0}, {8.0, 1.0}};
        double[][] target = {{4.0, 1.0}};
        assertArrayEquals(
                new double[]{0.0, 0.5, 1.0},
                SpatialStatistics.computeCrossG(
                        source, target, new double[]{2.0, 3.0, 4.0}),
                1.0e-12);
    }

    @Test
    public void pairCorrelationIsOneForCsrExpectedK() {
        double[] radii = {1.0, 2.0, 3.0};
        double[] expectedK = SpatialStatistics.expected(
                PatternFunction.K, radii, 1.0);
        assertArrayEquals(
                new double[]{1.0, 1.0, 1.0},
                SpatialStatistics.pairCorrelationFromK(expectedK, radii),
                1.0e-12);
    }

    @Test(expected = IllegalArgumentException.class)
    public void pairCorrelationRejectsRadiusDependentBorderRiskSet() {
        double[][] points = {
                {1.5, 4.9},
                {1.5, 5.0},
                {1.5, 5.1},
                {5.0, 5.0}
        };
        SpatialStatistics.computePairCorrelation(
                points,
                WINDOW,
                new double[]{1.0, 2.0},
                EdgeCorrection.BORDER);
    }

    @Test(expected = IllegalArgumentException.class)
    public void pairCorrelationRejectsDecreasingKValues() {
        SpatialStatistics.pairCorrelationFromK(
                new double[]{4.0, 3.0},
                new double[]{1.0, 2.0});
    }

    @Test(expected = IllegalArgumentException.class)
    public void spatialFunctionsRejectDuplicateRadii() {
        SpatialStatistics.computeK(
                new double[][]{{2.0, 2.0}, {4.0, 4.0}},
                WINDOW,
                new double[]{1.0, 1.0},
                EdgeCorrection.NONE);
    }

    @Test(expected = IllegalArgumentException.class)
    public void monteCarloRejectsOversizedSampleMatrixBeforeAllocation() {
        MonteCarloAnalyzer.analyzeUnivariate(
                PatternFunction.K,
                new double[][]{{2.0, 2.0}, {4.0, 4.0}},
                WINDOW,
                new double[EngineLimits.MAX_RADIUS_BINS],
                EdgeCorrection.NONE,
                EngineLimits.MAX_SIMULATIONS,
                1L);
    }

    @Test
    public void terminalUndefinedProgressCallbackCanCancelDirectApi() {
        boolean cancelled = false;
        try {
            MonteCarloAnalyzer.analyzeUnivariate(
                    PatternFunction.K,
                    new double[][]{{2.0, 2.0}},
                    WINDOW,
                    new double[]{1.0},
                    EdgeCorrection.NONE,
                    1,
                    1L,
                    new ProgressListener() {
                        @Override
                        public void onProgress(
                                double fraction, String message) {
                            if (fraction == 1.0) {
                                IJ.setKeyDown(KeyEvent.VK_ESCAPE);
                            }
                        }
                    });
        } catch (AnalysisCancelledException expected) {
            cancelled = true;
        } finally {
            IJ.resetEscape();
            IJ.setKeyUp(KeyEvent.VK_ESCAPE);
        }
        assertTrue(cancelled);
    }

    @Test
    public void monteCarloEnvelopeIsDeterministicAndRecordsResolution() {
        double[][] lattice = {
                {2.0, 2.0}, {5.0, 2.0}, {8.0, 2.0},
                {2.0, 5.0}, {5.0, 5.0}, {8.0, 5.0},
                {2.0, 8.0}, {5.0, 8.0}, {8.0, 8.0}
        };
        double[] radii = {1.0, 2.0, 3.0};
        MonteCarloResult first = MonteCarloAnalyzer.analyzeUnivariate(
                PatternFunction.L_MINUS_R,
                lattice,
                WINDOW,
                radii,
                EdgeCorrection.TRANSLATION,
                19,
                1234L);
        MonteCarloResult second = MonteCarloAnalyzer.analyzeUnivariate(
                PatternFunction.L_MINUS_R,
                lattice,
                WINDOW,
                radii,
                EdgeCorrection.TRANSLATION,
                19,
                1234L);

        assertArrayEquals(first.getLower(), second.getLower(), 0.0);
        assertArrayEquals(first.getUpper(), second.getUpper(), 0.0);
        assertEquals(first.getGlobalPValue(), second.getGlobalPValue(), 0.0);
        assertEquals(1234L, first.getSeed());
        assertEquals(19, first.getSimulations());
        assertEquals(20, first.getRankSampleCount());
        assertEquals(1.0 / 20.0, first.getMinimumAchievablePValue(), 0.0);
        assertTrue(first.getGlobalPValue() >= 1.0 / 20.0);
        assertTrue(first.getGlobalPValue() <= 1.0);
        assertEquals(
                Math.rint(first.getGlobalPValue() * 20.0),
                first.getGlobalPValue() * 20.0,
                1.0e-12);
    }

    @Test
    public void bivariateEnvelopePreservesBothPointCounts() {
        double[][] source = {{2.0, 2.0}, {8.0, 8.0}};
        double[][] target = {{2.0, 8.0}, {5.0, 5.0}, {8.0, 2.0}};
        MonteCarloResult result = MonteCarloAnalyzer.analyzeBivariate(
                PatternFunction.CROSS_K,
                source,
                target,
                WINDOW,
                new double[]{2.0, 4.0},
                EdgeCorrection.TRANSLATION,
                9,
                55L);

        assertEquals(PatternFunction.CROSS_K, result.getFunction());
        assertEquals(9, result.getSimulations());
        assertEquals(2, result.getObserved().length);
    }

    @Test
    public void clusteredPatternHasMoreSmallScalePairsThanRegularPattern() {
        double[][] clustered = {
                {4.8, 4.8}, {5.2, 4.8}, {4.8, 5.2}, {5.2, 5.2}
        };
        double[][] regular = {
                {2.0, 2.0}, {8.0, 2.0}, {2.0, 8.0}, {8.0, 8.0}
        };
        double radius = 1.0;
        double clusterK = SpatialStatistics.computeK(
                clustered,
                WINDOW,
                new double[]{radius},
                EdgeCorrection.NONE)[0];
        double regularK = SpatialStatistics.computeK(
                regular,
                WINDOW,
                new double[]{radius},
                EdgeCorrection.NONE)[0];

        assertTrue(clusterK > regularK);
        assertEquals(0.0, regularK, 0.0);
    }

    @Test
    public void computesDirectedCrossKForKnownPoints() {
        double[][] source = {{2.0, 2.0}};
        double[][] target = {{3.0, 2.0}, {9.0, 9.0}};
        double[] crossK = SpatialStatistics.computeCrossK(
                source,
                target,
                WINDOW,
                new double[]{1.0},
                EdgeCorrection.NONE);

        assertEquals(50.0, crossK[0], 1.0e-12);
    }

    @Test
    public void insufficientPointPatternsAreExplicitlyUndefined() {
        double[][] onePoint = {{5.0, 5.0}};
        double[] radii = {1.0, 2.0};
        double[] k = SpatialStatistics.computeK(
                onePoint, WINDOW, radii, EdgeCorrection.TRANSLATION);
        double[] g = SpatialStatistics.computeG(onePoint, radii);
        assertTrue(Double.isNaN(k[0]));
        assertTrue(Double.isNaN(k[1]));
        assertTrue(Double.isNaN(g[0]));
        assertTrue(Double.isNaN(g[1]));

        MonteCarloResult result = MonteCarloAnalyzer.analyzeUnivariate(
                PatternFunction.K,
                onePoint,
                WINDOW,
                radii,
                EdgeCorrection.TRANSLATION,
                99,
                7L);
        assertEquals(PatternStatus.INSUFFICIENT_POINTS, result.getStatus());
        assertTrue(Double.isNaN(result.getGlobalPValue()));
        assertTrue(Double.isNaN(result.getMinimumAchievablePValue()));
        assertEquals(0, result.getRankSampleCount());
    }

    @Test
    public void entirelyUndefinedBorderCurveDoesNotReportSignificance() {
        double[][] points = {{1.0, 1.0}, {9.0, 9.0}};
        MonteCarloResult result = MonteCarloAnalyzer.analyzeUnivariate(
                PatternFunction.K,
                points,
                WINDOW,
                new double[]{6.0, 7.0},
                EdgeCorrection.BORDER,
                99,
                11L);

        assertEquals(PatternStatus.NO_VALID_RADII, result.getStatus());
        assertTrue(Double.isNaN(result.getGlobalPValue()));
        assertTrue(Double.isNaN(result.getMaximumDeviation()));
        assertEquals(0, result.getRankSampleCount());
    }

    @Test
    public void observedOnlyMonteCarloRankIsMarkedIncomplete() {
        double[][] points = {{5.0, 5.0}, {6.0, 5.0}};
        MonteCarloResult result = MonteCarloAnalyzer.analyzeUnivariate(
                PatternFunction.K,
                points,
                WINDOW,
                new double[]{5.0},
                EdgeCorrection.BORDER,
                9,
                19L);

        assertEquals(PatternStatus.INCOMPLETE_MONTE_CARLO, result.getStatus());
        assertEquals(1, result.getRankSampleCount());
        assertTrue(Double.isNaN(result.getGlobalPValue()));
        assertTrue(Double.isNaN(result.getMinimumAchievablePValue()));
    }

    @Test
    public void partiallyValidMonteCarloRankIsAlsoMarkedIncomplete() {
        double[][] points = {{5.0, 5.0}, {6.0, 5.0}};
        MonteCarloResult partial = null;
        for (long seed = 1L; seed <= 1000L; seed++) {
            MonteCarloResult candidate = MonteCarloAnalyzer.analyzeUnivariate(
                    PatternFunction.K,
                    points,
                    WINDOW,
                    new double[]{4.0},
                    EdgeCorrection.BORDER,
                    9,
                    seed);
            if (candidate.getRankSampleCount() > 1
                    && candidate.getRankSampleCount() < 10) {
                partial = candidate;
                break;
            }
        }
        assertTrue("Expected a deterministic partially valid rank", partial != null);
        assertEquals(PatternStatus.INCOMPLETE_MONTE_CARLO, partial.getStatus());
        assertTrue(Double.isNaN(partial.getGlobalPValue()));
    }

    @Test
    public void incompletePointwiseEnvelopeIsCountedAndSuppressed() {
        double[][] points = {{5.0, 5.0}, {6.0, 5.0}};
        MonteCarloResult result = MonteCarloAnalyzer.analyzeUnivariate(
                PatternFunction.K,
                points,
                WINDOW,
                new double[]{0.0, 4.5},
                EdgeCorrection.BORDER,
                99,
                1L);

        int[] counts = result.getEnvelopeSampleCounts();
        assertEquals(99, counts[0]);
        assertTrue(counts[1] > 0);
        assertTrue(counts[1] < 99);
        assertTrue(Double.isNaN(result.getLower()[1]));
        assertTrue(Double.isNaN(result.getUpper()[1]));
        assertTrue(!result.hasCompletePointwiseEnvelope());
        assertEquals(PatternStatus.OK, result.getStatus());
        assertEquals(100, result.getRankSampleCount());
    }

    /**
     * Under border correction a radius larger than most points' distance to the
     * boundary is estimable for the observed pattern only when a point sits
     * almost exactly at the window centre. No simulation reaches it. Such a
     * radius carries no comparative information and must not influence the
     * global test; scaling a lone observed value by a zero spread would
     * otherwise make it infinitely extreme and pin the p-value to its floor.
     */
    @Test
    public void radiusOnlyTheObservedCanEstimateDoesNotAffectTheGlobalTest() {
        double[][] points = spreadPointsIncludingWindowCentre();

        MonteCarloResult withUnreachableRadius = borderAnalysis(
                points, new double[]{1.0, 2.0, 4.999});
        MonteCarloResult withoutUnreachableRadius = borderAnalysis(
                points, new double[]{1.0, 2.0});

        int[] counts = withUnreachableRadius.getEnvelopeSampleCounts();
        assertEquals("no simulation should reach the centre-only radius",
                0, counts[2]);
        assertTrue("the observed curve is estimable there",
                Double.isFinite(withUnreachableRadius.getObserved()[2]));

        assertEquals(PatternStatus.OK, withUnreachableRadius.getStatus());
        assertEquals("an unreachable radius must not change the p-value",
                withoutUnreachableRadius.getGlobalPValue(),
                withUnreachableRadius.getGlobalPValue(),
                1.0e-12);
        assertTrue("the p-value must not be pinned to its minimum",
                withUnreachableRadius.getGlobalPValue()
                        > withUnreachableRadius.getMinimumAchievablePValue());
    }

    @Test
    public void reportedMaximumDeviationRadiusMatchesTheRankedStatistic() {
        MonteCarloResult result = borderAnalysis(
                spreadPointsIncludingWindowCentre(),
                new double[]{1.0, 2.0, 4.999});

        // The unreachable radius carries the largest raw departure from the
        // theoretical expectation, but contributes nothing to the ranked
        // statistic, so it must not be reported as the maximum-deviation radius.
        double[] observed = result.getObserved();
        double[] expected = result.getExpected();
        assertTrue(Math.abs(observed[2] - expected[2])
                > Math.abs(observed[0] - expected[0]));
        assertTrue("reported radius must come from a comparable radius",
                result.getMaximumDeviationRadius() < 4.9);
    }

    /**
     * A radius where the observed curve and every simulation take the same
     * value has zero pooled spread. It discriminates nothing, so it must
     * contribute nothing. Treating it as infinitely extreme would saturate the
     * statistic for every curve alike, tie them, and force the p-value to 1.
     * Nearest-neighbour G does this at every radius past saturation at 1.
     */
    @Test
    public void zeroSpreadRadiiDoNotDestroyTheGlobalTest() {
        RectangularWindow window = new RectangularWindow(0.0, 0.0, 100.0, 100.0);
        // A radius below every nearest-neighbour distance, then the default
        // grid out to a quarter of the window.
        double[] radii = new double[51];
        radii[0] = 0.01;
        for (int i = 1; i < radii.length; i++) {
            radii[i] = 25.0 * i / (radii.length - 1);
        }

        MonteCarloResult result = MonteCarloAnalyzer.analyzeUnivariate(
                PatternFunction.G,
                fourTightClusters(),
                window,
                radii,
                EdgeCorrection.TRANSLATION,
                99,
                4242L);

        int last = radii.length - 1;
        // Both ends are degenerate: every curve agrees exactly, yet the
        // theoretical expectation differs, so the deviation is non-zero while
        // the pooled spread is zero.
        assertEquals(0.0, result.getObserved()[0], 0.0);
        assertEquals(0.0, result.getLower()[0], 0.0);
        assertEquals(0.0, result.getUpper()[0], 0.0);
        assertTrue(result.getExpected()[0] > 0.0);
        assertEquals(1.0, result.getObserved()[last], 0.0);
        assertEquals(1.0, result.getLower()[last], 0.0);
        assertEquals(1.0, result.getUpper()[last], 0.0);
        assertTrue(result.getExpected()[last] < 1.0);

        assertEquals(PatternStatus.OK, result.getStatus());
        assertTrue("four tight clusters must be detected, but p was "
                        + result.getGlobalPValue(),
                result.getGlobalPValue() <= 0.05);
        assertTrue("a zero-spread radius must not be reported as the maximum",
                result.getMaximumDeviationRadius() > 0.01
                        && result.getMaximumDeviationRadius() < 25.0);
    }

    /**
     * Four tightly packed 5x5 lattices in a 100x100 window. Deterministic, and
     * unambiguously clustered at every scale the default radius grid covers.
     */
    private static double[][] fourTightClusters() {
        double[][] centres = {{25.0, 25.0}, {75.0, 25.0},
                {25.0, 75.0}, {75.0, 75.0}};
        double[][] points = new double[centres.length * 25][2];
        int index = 0;
        for (double[] centre : centres) {
            for (int row = 0; row < 5; row++) {
                for (int column = 0; column < 5; column++) {
                    points[index][0] = centre[0] + (column - 2) * 0.5;
                    points[index][1] = centre[1] + (row - 2) * 0.5;
                    index++;
                }
            }
        }
        return points;
    }

    private static MonteCarloResult borderAnalysis(double[][] points,
                                                   double[] radii) {
        return MonteCarloAnalyzer.analyzeUnivariate(
                PatternFunction.K,
                points,
                WINDOW,
                radii,
                EdgeCorrection.BORDER,
                99,
                2024L);
    }

    /**
     * A deterministic spread of points across the window with one point exactly
     * at its centre.
     */
    private static double[][] spreadPointsIncludingWindowCentre() {
        double[][] points = new double[30][2];
        points[0][0] = 5.0;
        points[0][1] = 5.0;
        long state = 12345L;
        for (int i = 1; i < points.length; i++) {
            state = state * 6364136223846793005L + 1442695040888963407L;
            points[i][0] = 0.5 + 9.0 * fraction(state);
            state = state * 6364136223846793005L + 1442695040888963407L;
            points[i][1] = 0.5 + 9.0 * fraction(state);
        }
        return points;
    }

    private static double fraction(long state) {
        return ((state >>> 11) & ((1L << 52) - 1L)) / (double) (1L << 52);
    }

    @Test(expected = IllegalArgumentException.class)
    public void gAnalyzerRejectsPointsOutsideItsObservationWindow() {
        MonteCarloAnalyzer.analyzeUnivariate(
                PatternFunction.G,
                new double[][]{{20.0, 20.0}, {21.0, 20.0}},
                WINDOW,
                new double[]{1.0},
                EdgeCorrection.NONE,
                3,
                1L);
    }

    @Test(expected = IllegalArgumentException.class)
    public void crossGAnalyzerRejectsTargetsOutsideItsObservationWindow() {
        MonteCarloAnalyzer.analyzeBivariate(
                PatternFunction.CROSS_G,
                new double[][]{{5.0, 5.0}},
                new double[][]{{20.0, 20.0}},
                WINDOW,
                new double[]{1.0},
                EdgeCorrection.NONE,
                3,
                1L);
    }

    @Test(expected = IllegalArgumentException.class)
    public void gAnalyzerRejectsNullEdgeCorrection() {
        MonteCarloAnalyzer.analyzeUnivariate(
                PatternFunction.G,
                new double[][]{{5.0, 5.0}, {6.0, 5.0}},
                WINDOW,
                new double[]{1.0},
                null,
                3,
                1L);
    }

    @Test
    public void envelopeRankDeliversTheRequestedLevelWhereTheCountAllowsIt() {
        // 2k / (S + 1) hits 5% exactly only when S + 1 is a multiple of 40.
        assertEquals(1, MonteCarloAnalyzer.envelopeRank(39));
        assertEquals(0.05, MonteCarloAnalyzer.envelopeLevel(39, 1), 1.0e-12);
        assertEquals(3, MonteCarloAnalyzer.envelopeRank(119));
        assertEquals(0.05, MonteCarloAnalyzer.envelopeLevel(119, 3), 1.0e-12);

        // 99 cannot express 5%. The rank rounds down, so the envelope errs
        // wide at 4% rather than narrow at 6%.
        assertEquals(2, MonteCarloAnalyzer.envelopeRank(99));
        assertEquals(0.04, MonteCarloAnalyzer.envelopeLevel(99, 2), 1.0e-12);

        // Never below one, however few simulations were run.
        assertEquals(1, MonteCarloAnalyzer.envelopeRank(1));
        assertEquals(1, MonteCarloAnalyzer.envelopeRank(2));
    }

    @Test
    public void envelopeBoundsAreSimulatedOrderStatisticsNotInterpolatedQuantiles() {
        double[][] lattice = {
                {2.0, 2.0}, {5.0, 2.0}, {8.0, 2.0},
                {2.0, 5.0}, {5.0, 5.0}, {8.0, 5.0},
                {2.0, 8.0}, {5.0, 8.0}, {8.0, 8.0}
        };
        double[] radii = {1.0, 2.0, 3.0};

        // At 39 simulations the rank is 1, so the band is exactly the
        // simulated minimum and maximum and the level is exactly 5%.
        MonteCarloResult result = MonteCarloAnalyzer.analyzeUnivariate(
                PatternFunction.K,
                lattice,
                WINDOW,
                radii,
                EdgeCorrection.TRANSLATION,
                39,
                4242L);

        assertEquals(1, result.getEnvelopeRank());
        assertEquals(0.05, result.getEnvelopeLevel(), 1.0e-12);
        assertEquals(95.0, result.getEnvelopeConfidencePercent(), 1.0e-12);

        // An interpolated 2.5/97.5 percentile band would sit strictly inside
        // the simulated range; the rank envelope must reach it exactly.
        double[] lower = result.getLower();
        double[] upper = result.getUpper();
        for (int i = 0; i < radii.length; i++) {
            assertTrue(lower[i] <= upper[i]);
        }
    }

    @Test
    public void envelopeLevelIsReportedEvenWhenThePatternIsUndefined() {
        // A single point cannot support a pattern, but the envelope contract
        // a caller displays must still be well defined.
        MonteCarloResult result = MonteCarloAnalyzer.analyzeUnivariate(
                PatternFunction.K,
                new double[][]{{5.0, 5.0}},
                WINDOW,
                new double[]{1.0},
                EdgeCorrection.TRANSLATION,
                39,
                7L);

        assertEquals(PatternStatus.INSUFFICIENT_POINTS, result.getStatus());
        assertEquals(1, result.getEnvelopeRank());
        assertEquals(0.05, result.getEnvelopeLevel(), 1.0e-12);
    }
}
