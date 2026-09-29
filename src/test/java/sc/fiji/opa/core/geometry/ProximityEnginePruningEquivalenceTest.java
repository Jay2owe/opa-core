/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package sc.fiji.opa.core.geometry;

import ij.ImagePlus;
import ij.ImageStack;
import ij.measure.Calibration;
import ij.process.ShortProcessor;
import org.junit.Test;
import sc.fiji.opa.core.DistanceMode;
import sc.fiji.opa.core.ProgressListener;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The pruned engine must report exactly what the brute-force 0.3.0 engine
 * reports: same neighbours, same order, every value bit for bit.
 *
 * <p>The oracle runs once per scene, direction and contact distance with
 * k = 1000, which exceeds every object count here, so it ranks every pair;
 * the brute-force engine takes the first k of one full sort, so k = 1 and
 * k = 3 are prefixes of that list.</p>
 */
public class ProximityEnginePruningEquivalenceTest {

    private static final EnumSet<DistanceMode> ALL = EnumSet.allOf(DistanceMode.class);
    private static final int[] NEIGHBOUR_COUNTS = {1, 3, 1000};
    private static final double[] CONTACT_DISTANCES = {0.0, 0.5, 2.5};
    private static final double[][] CALIBRATIONS = {
            {1.0, 1.0, 1.0},
            {0.1, 0.1, 0.3},
            {0.3, 0.3, 0.1},
            {0.2, 0.2, 0.5},
            {0.1625, 0.1625, 0.4}
    };

    @Test
    public void prunedEngineMatchesTheBruteForceOracleBitForBit() {
        int scenes = 0;
        long comparedNeighbours = 0;
        long zeroDistances = 0;
        long pairs = 0;
        for (int seed = 1; seed <= 48; seed++) {
            Random random = new Random(seed * 7919L);
            boolean threeD = seed % 2 == 0;
            int layout = (seed / 2) % 4;
            double[] calibration = CALIBRATIONS[seed % CALIBRATIONS.length];
            ImagePlus a = scene("A", random, threeD, layout, calibration);
            ImagePlus b = scene("B", random, threeD, (layout + 1) % 4, calibration);
            ChannelGeometry first = LabelGeometryExtractor.extract(a, "A");
            ChannelGeometry second = LabelGeometryExtractor.extract(b, "B");
            scenes++;

            ChannelGeometry[][] directions = {
                    {first, first}, {first, second}, {second, first}, {second, second}
            };
            for (ChannelGeometry[] direction : directions) {
                pairs += (long) direction[0].getObjects().size()
                        * direction[1].getObjects().size();
                for (double contact : CONTACT_DISTANCES) {
                    DirectionResult oracle = withParallelism(1,
                            direction[0], direction[1], 1000, contact, true);
                    zeroDistances += zeros(oracle);
                    for (int k : NEIGHBOUR_COUNTS) {
                        for (int parallelism : new int[]{1, 4}) {
                            DirectionResult pruned = withParallelism(parallelism,
                                    direction[0], direction[1], k, contact, false);
                            String where = "seed " + seed + " " + direction[0].getName()
                                    + "->" + direction[1].getName() + " k=" + k
                                    + " contact=" + contact + " p=" + parallelism;
                            comparedNeighbours += assertSame(where, oracle, pruned, k);
                        }
                    }
                }
            }
        }
        assertTrue("too few pairs exercised: " + pairs, pairs > 10000);
        assertTrue("no zero distances exercised", zeroDistances > 100);
        System.out.println("[pruning] scenes=" + scenes + " pairs=" + pairs
                + " neighbours=" + comparedNeighbours + " zeros=" + zeroDistances);
    }

    @Test
    public void progressReachesOneOncePerSourceObjectWithoutGoingBackwards() {
        Random random = new Random(5L);
        ImagePlus image = scene("A", random, false, 0, CALIBRATIONS[0]);
        ChannelGeometry geometry = LabelGeometryExtractor.extract(image, "A");
        int objects = geometry.getObjects().size();
        assertTrue(objects > 4);
        for (int parallelism : new int[]{1, 4}) {
            final List<Double> fractions =
                    Collections.synchronizedList(new ArrayList<Double>());
            String previous = System.getProperty("opa.parallelism");
            System.setProperty("opa.parallelism", Integer.toString(parallelism));
            try {
                ProximityEngine.analyze(geometry, geometry, ALL, 2, 0.5,
                        new ProgressListener() {
                            @Override
                            public void onProgress(double fraction, String message) {
                                fractions.add(fraction);
                            }
                        });
            } finally {
                restore(previous);
            }
            assertEquals(objects, fractions.size());
            for (int i = 1; i < fractions.size(); i++) {
                assertTrue(fractions.get(i) > fractions.get(i - 1));
            }
            assertEquals(1.0, fractions.get(fractions.size() - 1), 0.0);
        }
    }

    // ------------------------------------------------------------ comparison

    private static int assertSame(String where, DirectionResult oracle,
                                  DirectionResult pruned, int k) {
        assertEquals(where, oracle.getSourceChannel(), pruned.getSourceChannel());
        assertEquals(where, oracle.getTargetChannel(), pruned.getTargetChannel());
        assertEquals(where, oracle.isSelfComparison(), pruned.isSelfComparison());
        assertEquals(where, oracle.getUnit(), pruned.getUnit());
        assertEquals(where, oracle.getSurfaceMeasureUnit(), pruned.getSurfaceMeasureUnit());
        assertEquals(where, oracle.getSourceObjectCount(), pruned.getSourceObjectCount());
        assertEquals(where, oracle.getTargetObjectCount(), pruned.getTargetObjectCount());
        List<ObjectMeasurement> expected = oracle.getMeasurements();
        List<ObjectMeasurement> actual = pruned.getMeasurements();
        assertEquals(where, expected.size(), actual.size());
        int compared = 0;
        for (int i = 0; i < expected.size(); i++) {
            ObjectMeasurement e = expected.get(i);
            ObjectMeasurement a = actual.get(i);
            assertEquals(where, e.getSourceLabel(), a.getSourceLabel());
            assertEquals(where, e.isEdgeObject(), a.isEdgeObject());
            assertEquals(where, e.getNeighborsByMode().keySet(),
                    a.getNeighborsByMode().keySet());
            for (DistanceMode mode : ALL) {
                List<NeighborMeasurement> full = e.getNeighbors(mode);
                List<NeighborMeasurement> top = a.getNeighbors(mode);
                String at = where + " source " + e.getSourceLabel() + " " + mode;
                assertEquals(at, Math.min(k, full.size()), top.size());
                for (int r = 0; r < top.size(); r++) {
                    NeighborMeasurement x = full.get(r);
                    NeighborMeasurement y = top.get(r);
                    String rank = at + " rank " + (r + 1);
                    assertEquals(rank, x.getMode(), y.getMode());
                    assertEquals(rank, x.getRank(), y.getRank());
                    assertEquals(rank, x.getPartnerLabel(), y.getPartnerLabel());
                    bits(rank + " value", x.getValue(), y.getValue());
                    assertEquals(rank, x.isWithinContactDistance(),
                            y.isWithinContactDistance());
                    bits(rank + " exact contact", x.getExactContactArea(),
                            y.getExactContactArea());
                    bits(rank + " apposed", x.getApposedSurfaceArea(),
                            y.getApposedSurfaceArea());
                    compared++;
                }
            }
        }
        return compared;
    }

    private static void bits(String what, double expected, double actual) {
        if (Double.doubleToRawLongBits(expected) != Double.doubleToRawLongBits(actual)) {
            fail(what + ": expected " + expected + " but was " + actual);
        }
    }

    private static long zeros(DirectionResult result) {
        long zeros = 0;
        for (ObjectMeasurement measurement : result.getMeasurements()) {
            for (NeighborMeasurement neighbour
                    : measurement.getNeighbors(DistanceMode.EDGE_TO_EDGE)) {
                if (neighbour.getValue() == 0.0) zeros++;
            }
        }
        return zeros;
    }

    private static DirectionResult withParallelism(int parallelism,
                                                   ChannelGeometry source,
                                                   ChannelGeometry target,
                                                   int k,
                                                   double contact,
                                                   boolean reference) {
        String previous = System.getProperty("opa.parallelism");
        System.setProperty("opa.parallelism", Integer.toString(parallelism));
        try {
            return reference
                    ? ReferenceProximityEngine.analyze(source, target, ALL, k, contact)
                    : ProximityEngine.analyze(source, target, ALL, k, contact);
        } finally {
            restore(previous);
        }
    }

    private static void restore(String previous) {
        if (previous == null) {
            System.clearProperty("opa.parallelism");
        } else {
            System.setProperty("opa.parallelism", previous);
        }
    }

    // ---------------------------------------------------------------- scenes

    /**
     * Layouts: 0 random boxes that may overwrite one another (irregular and
     * split objects), 1 a regular grid of equal squares (forced distance
     * ties), 2 touching runs of boxes (zero gaps and shared faces), 3 sparse
     * single voxels and small boxes with shuffled, non-contiguous labels.
     */
    private static ImagePlus scene(String title, Random random, boolean threeD,
                                   int layout, double[] calibration) {
        int width = threeD ? 18 : 30;
        int height = threeD ? 16 : 28;
        int depth = threeD ? 6 : 1;
        ImageStack stack = new ImageStack(width, height);
        for (int z = 0; z < depth; z++) stack.addSlice(new ShortProcessor(width, height));
        int objects = 1 + random.nextInt(60);
        List<Integer> labels = new ArrayList<Integer>();
        for (int i = 1; i <= objects; i++) labels.add(layout == 3 ? i * 7 + random.nextInt(5) : i);
        Collections.shuffle(labels, random);

        switch (layout) {
            case 0:
                for (int label : labels) {
                    box(stack, random.nextInt(width), random.nextInt(height),
                            random.nextInt(depth), 1 + random.nextInt(5),
                            1 + random.nextInt(5), 1 + random.nextInt(3), label);
                }
                break;
            case 1: {
                int step = 3 + random.nextInt(3);
                int size = 1 + random.nextInt(step - 1);
                int i = 0;
                for (int y = 0; y + size <= height && i < labels.size(); y += step) {
                    for (int x = 0; x + size <= width && i < labels.size(); x += step) {
                        box(stack, x, y, 0, size, size, depth, labels.get(i++));
                    }
                }
                break;
            }
            case 2: {
                int x = 0;
                int y = random.nextInt(4);
                for (int label : labels) {
                    int w = 1 + random.nextInt(4);
                    if (x + w > width) {
                        x = 0;
                        y += 2 + random.nextInt(3);
                        if (y >= height) break;
                    }
                    box(stack, x, y, 0, w, 1 + random.nextInt(3),
                            1 + random.nextInt(depth), label);
                    x += w;
                }
                break;
            }
            default:
                for (int label : labels) {
                    int size = random.nextInt(3) == 0 ? 2 : 1;
                    box(stack, random.nextInt(width), random.nextInt(height),
                            random.nextInt(depth), size, size, size, label);
                }
                break;
        }
        ImagePlus image = new ImagePlus(title, stack);
        Calibration cal = new Calibration();
        cal.pixelWidth = calibration[0];
        cal.pixelHeight = calibration[1];
        cal.pixelDepth = calibration[2];
        cal.setUnit("um");
        image.setCalibration(cal);
        return image;
    }

    private static void box(ImageStack stack, int x0, int y0, int z0,
                            int w, int h, int d, int label) {
        for (int z = z0; z < Math.min(stack.getSize(), z0 + d); z++) {
            for (int y = y0; y < Math.min(stack.getHeight(), y0 + h); y++) {
                for (int x = x0; x < Math.min(stack.getWidth(), x0 + w); x++) {
                    stack.getProcessor(z + 1).set(x, y, label);
                }
            }
        }
    }
}
