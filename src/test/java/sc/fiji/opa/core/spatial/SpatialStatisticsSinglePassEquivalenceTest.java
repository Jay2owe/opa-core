/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package sc.fiji.opa.core.spatial;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Single-pass K across radii must reproduce the one-radius-at-a-time 0.3.0
 * estimator bit for bit, for every function built on K, every edge
 * correction, and self and cross patterns.
 */
public class SpatialStatisticsSinglePassEquivalenceTest {

    private static final PatternFunction[] K_FUNCTIONS = {
            PatternFunction.K,
            PatternFunction.L,
            PatternFunction.L_MINUS_R,
            PatternFunction.PAIR_CORRELATION,
            PatternFunction.CROSS_K,
            PatternFunction.CROSS_L,
            PatternFunction.CROSS_PAIR_CORRELATION
    };

    @Test
    public void everyKFunctionMatchesTheOneRadiusAtATimeOracle() {
        int compared = 0;
        int nanValues = 0;
        for (int seed = 1; seed <= 160; seed++) {
            Random random = new Random(seed * 104729L);
            RectangularWindow window = window(random);
            double[][] source = pattern(random, window, size(random, seed));
            double[][] target = pattern(random, window, size(random, seed + 1));
            double[] radii = radii(random, window);
            for (EdgeCorrection correction : EdgeCorrection.values()) {
                for (PatternFunction function : K_FUNCTIONS) {
                    String where = "seed " + seed + " " + function + " " + correction
                            + " n=" + source.length + "/" + target.length
                            + " radii=" + Arrays.toString(radii);
                    double[] expected;
                    double[] actual;
                    try {
                        expected = ReferenceSpatialStatistics.evaluate(
                                function, source, target, window, radii, correction);
                    } catch (IllegalArgumentException rejected) {
                        try {
                            SpatialStatistics.evaluate(
                                    function, source, target, window, radii, correction);
                            fail(where + ": oracle rejected, single pass did not: "
                                    + rejected.getMessage());
                        } catch (IllegalArgumentException alsoRejected) {
                            assertEquals(where, rejected.getMessage(),
                                    alsoRejected.getMessage());
                        }
                        continue;
                    }
                    actual = SpatialStatistics.evaluate(
                            function, source, target, window, radii, correction);
                    assertEquals(where, expected.length, actual.length);
                    for (int i = 0; i < expected.length; i++) {
                        if (Double.doubleToRawLongBits(expected[i])
                                != Double.doubleToRawLongBits(actual[i])) {
                            fail(where + " radius " + i + ": expected " + expected[i]
                                    + " but was " + actual[i]);
                        }
                        if (Double.isNaN(expected[i])) nanValues++;
                        compared++;
                    }
                }
            }
        }
        assertTrue("too few values compared: " + compared, compared > 50000);
        assertTrue("no NaN (empty risk set) case exercised", nanValues > 0);
        System.out.println("[single-pass] values=" + compared + " nan=" + nanValues);
    }

    @Test
    public void firstReachedFindsTheSmallestRadiusAPairCountsAt() {
        double[] squared = {0.0, 1.0, 1.0, 4.0};
        assertEquals(0, SpatialStatistics.firstReached(squared, 0.0));
        assertEquals(1, SpatialStatistics.firstReached(squared, 0.5));
        assertEquals(1, SpatialStatistics.firstReached(squared, 1.0));
        assertEquals(3, SpatialStatistics.firstReached(squared, 4.0));
        assertEquals(4, SpatialStatistics.firstReached(squared, 4.0000001));
    }

    // ---------------------------------------------------------------- inputs

    private static int size(Random random, int seed) {
        switch (seed % 6) {
            case 0:
                return random.nextInt(3);          // 0, 1 or 2 points
            case 1:
                return 200 + random.nextInt(101);  // up to 300
            default:
                return 3 + random.nextInt(80);
        }
    }

    /** Windows with non-zero origins, elongated shapes and awkward sizes. */
    private static RectangularWindow window(Random random) {
        double minX = random.nextBoolean() ? 0.0 : random.nextDouble() * 50.0 - 25.0;
        double minY = random.nextBoolean() ? 0.0 : random.nextDouble() * 50.0 - 25.0;
        double width;
        double height;
        switch (random.nextInt(3)) {
            case 0:
                width = 1.0 + random.nextDouble() * 40.0;
                height = width;
                break;
            case 1:
                width = 40.0 + random.nextDouble() * 60.0;
                height = 0.5 + random.nextDouble() * 3.0;
                break;
            default:
                width = 0.3 + random.nextDouble() * 20.0;
                height = 0.3 + random.nextDouble() * 20.0;
                break;
        }
        return new RectangularWindow(minX, minY, minX + width, minY + height);
    }

    /**
     * Uniform points plus deliberate duplicates, points on the window edges
     * and corners, and points on a coarse lattice (many equal distances).
     */
    private static double[][] pattern(Random random, RectangularWindow window, int n) {
        List<double[]> points = new ArrayList<double[]>();
        double w = window.width();
        double h = window.height();
        for (int i = 0; i < n; i++) {
            int kind = random.nextInt(10);
            double x;
            double y;
            if (kind == 0 && !points.isEmpty()) {
                double[] copy = points.get(random.nextInt(points.size()));
                x = copy[0];
                y = copy[1];
            } else if (kind == 1) {
                x = random.nextBoolean() ? window.getMinX() : window.getMaxX();
                y = window.getMinY() + random.nextDouble() * h;
            } else if (kind == 2) {
                x = random.nextBoolean() ? window.getMinX() : window.getMaxX();
                y = random.nextBoolean() ? window.getMinY() : window.getMaxY();
            } else if (kind <= 4) {
                x = window.getMinX() + Math.floor(random.nextDouble() * 5.0) * w / 4.0;
                y = window.getMinY() + Math.floor(random.nextDouble() * 5.0) * h / 4.0;
            } else {
                x = window.getMinX() + random.nextDouble() * w;
                y = window.getMinY() + random.nextDouble() * h;
            }
            x = Math.min(window.getMaxX(), Math.max(window.getMinX(), x));
            y = Math.min(window.getMaxY(), Math.max(window.getMinY(), y));
            points.add(new double[]{x, y});
        }
        return points.toArray(new double[0][]);
    }

    /**
     * Strictly increasing grids: with or without zero, near-duplicate
     * neighbours one ulp apart, lattice spacings that pairs hit exactly, and
     * radii beyond the window so every pair counts.
     */
    private static double[] radii(Random random, RectangularWindow window) {
        double span = Math.max(window.width(), window.height());
        int count = 1 + random.nextInt(60);
        double[] radii = new double[count];
        double step = span * (0.3 + random.nextDouble()) / count;
        double start = random.nextBoolean() ? 0.0 : step * random.nextDouble();
        for (int i = 0; i < count; i++) radii[i] = start + i * step;
        if (count > 2 && random.nextBoolean()) {
            int at = 1 + random.nextInt(count - 2);
            radii[at] = Math.nextUp(radii[at - 1]);
            for (int i = at + 1; i < count; i++) {
                if (radii[i] <= radii[i - 1]) radii[i] = Math.nextUp(radii[i - 1]);
            }
        }
        if (random.nextInt(4) == 0) {
            radii[count - 1] = Math.max(radii[count - 1], span * 2.0);
        }
        if (random.nextInt(4) == 0) {
            double lattice = window.width() / 4.0;
            for (int i = 0; i < count; i++) {
                double candidate = lattice * (i + 1);
                if (i == 0 || candidate > radii[i - 1]) radii[i] = candidate;
                else radii[i] = Math.nextUp(radii[i - 1]);
            }
        }
        return radii;
    }
}
