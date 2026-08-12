/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package sc.fiji.opa.core.geometry;

import ij.ImagePlus;
import ij.ImageStack;
import ij.measure.Calibration;
import ij.process.ByteProcessor;
import ij.process.FloatProcessor;
import sc.fiji.opa.core.DistanceMode;
import org.junit.Test;

import java.util.EnumSet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ProximityEngineTest {

    @Test
    public void extractsCalibratedCentroidsAndExactDistances() {
        ImagePlus sourceImage = labels("source", 8, 3, 1);
        sourceImage.getProcessor().set(1, 1, 1);
        ImagePlus targetImage = labels("target", 8, 3, 1);
        targetImage.getProcessor().set(5, 1, 2);
        calibrate(sourceImage, 2.0, 3.0, 4.0);
        calibrate(targetImage, 2.0, 3.0, 4.0);

        ChannelGeometry source = LabelGeometryExtractor.extract(sourceImage, "A");
        ChannelGeometry target = LabelGeometryExtractor.extract(targetImage, "B");
        DirectionResult result = ProximityEngine.analyze(
                source,
                target,
                EnumSet.allOf(DistanceMode.class),
                1,
                6.0);

        ObjectGeometry object = source.getObjects().get(0);
        assertEquals(3.0, object.getCentroidX(), 1.0e-12);
        assertEquals(4.5, object.getCentroidY(), 1.0e-12);
        assertEquals(2.0, object.getCentroidZ(), 1.0e-12);

        ObjectMeasurement measurement = result.getMeasurements().get(0);
        assertEquals(8.0, value(measurement, DistanceMode.CENTRE_TO_CENTRE), 1.0e-12);
        assertEquals(7.0, value(measurement, DistanceMode.CENTRE_TO_EDGE), 1.0e-12);
        assertEquals(7.0, value(measurement, DistanceMode.EDGE_TO_CENTRE), 1.0e-12);
        assertEquals(6.0, value(measurement, DistanceMode.EDGE_TO_EDGE), 1.0e-12);
        assertTrue(measurement.getNeighbors(DistanceMode.EDGE_TO_EDGE)
                .get(0).isWithinContactDistance());
    }

    @Test
    public void selfComparisonExcludesTheSameLabelAndRanksKNeighbors() {
        ImagePlus image = labels("objects", 9, 3, 1);
        image.getProcessor().set(1, 1, 1);
        image.getProcessor().set(4, 1, 2);
        image.getProcessor().set(8, 1, 3);

        ChannelGeometry geometry = LabelGeometryExtractor.extract(image, "A");
        DirectionResult result = ProximityEngine.analyze(
                geometry,
                geometry,
                EnumSet.of(DistanceMode.CENTRE_TO_CENTRE),
                2,
                0.0);

        ObjectMeasurement first = result.getMeasurements().get(0);
        assertEquals(2, first.getNeighbors(DistanceMode.CENTRE_TO_CENTRE).size());
        assertEquals(2, first.getNeighbors(DistanceMode.CENTRE_TO_CENTRE)
                .get(0).getPartnerLabel());
        assertEquals(3.0, first.getNeighbors(DistanceMode.CENTRE_TO_CENTRE)
                .get(0).getValue(), 1.0e-12);
        assertEquals(3, first.getNeighbors(DistanceMode.CENTRE_TO_CENTRE)
                .get(1).getPartnerLabel());
        assertEquals(7.0, first.getNeighbors(DistanceMode.CENTRE_TO_CENTRE)
                .get(1).getValue(), 1.0e-12);
    }

    @Test
    public void reportsExactAndThresholdedSurfaceContactSeparately() {
        ImagePlus sourceImage = labels("source", 5, 3, 1);
        sourceImage.getProcessor().set(1, 1, 1);
        ImagePlus touchingTargetImage = labels("target", 5, 3, 1);
        touchingTargetImage.getProcessor().set(2, 1, 2);

        ChannelGeometry source = LabelGeometryExtractor.extract(sourceImage, "A");
        ChannelGeometry target = LabelGeometryExtractor.extract(touchingTargetImage, "B");
        DirectionResult result = ProximityEngine.analyze(
                source,
                target,
                EnumSet.of(DistanceMode.EDGE_TO_EDGE, DistanceMode.SURFACE_CONTACT),
                1,
                0.0);

        NeighborMeasurement contact = result.getMeasurements().get(0)
                .getNeighbors(DistanceMode.SURFACE_CONTACT).get(0);
        assertEquals(1.0, contact.getExactContactArea(), 1.0e-12);
        assertEquals(1.0, contact.getApposedSurfaceArea(), 1.0e-12);
        assertEquals(1.0, contact.getValue(), 1.0e-12);
        assertTrue(contact.isWithinContactDistance());
    }

    @Test
    public void overlappingObjectsHaveZeroObjectDistance() {
        ImagePlus sourceImage = labels("source", 7, 7, 1);
        ImagePlus targetImage = labels("target", 7, 7, 1);
        for (int y = 2; y <= 4; y++) {
            for (int x = 2; x <= 4; x++) {
                sourceImage.getProcessor().set(x, y, 1);
                targetImage.getProcessor().set(x, y, 2);
            }
        }

        DirectionResult result = ProximityEngine.analyze(
                LabelGeometryExtractor.extract(sourceImage, "A"),
                LabelGeometryExtractor.extract(targetImage, "B"),
                EnumSet.of(
                        DistanceMode.CENTRE_TO_EDGE,
                        DistanceMode.EDGE_TO_CENTRE,
                        DistanceMode.EDGE_TO_EDGE),
                1,
                0.0);

        ObjectMeasurement measurement = result.getMeasurements().get(0);
        assertEquals(0.0, value(measurement, DistanceMode.CENTRE_TO_EDGE), 0.0);
        assertEquals(0.0, value(measurement, DistanceMode.EDGE_TO_CENTRE), 0.0);
        assertEquals(0.0, value(measurement, DistanceMode.EDGE_TO_EDGE), 0.0);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsFractionalLabels() {
        ImagePlus image = new ImagePlus("bad", new FloatProcessor(3, 3));
        image.getProcessor().setf(1, 1, 1.5f);
        LabelGeometryExtractor.extract(image);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsNegativeLabels() {
        ImagePlus image = new ImagePlus("bad-negative", new FloatProcessor(3, 3));
        image.getProcessor().setf(1, 1, -1.0f);
        LabelGeometryExtractor.extract(image);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsNonFiniteLabels() {
        ImagePlus image = new ImagePlus("bad-infinite", new FloatProcessor(3, 3));
        image.getProcessor().setf(1, 1, Float.POSITIVE_INFINITY);
        LabelGeometryExtractor.extract(image);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsNearIntegerFractionalLabels() {
        ImagePlus image = new ImagePlus(
                "bad-near-integer", new FloatProcessor(3, 3));
        image.getProcessor().setf(1, 1, 1.00005f);
        LabelGeometryExtractor.extract(image);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsLabelsAboveIntegerRange() {
        ImagePlus image = new ImagePlus(
                "bad-range", new FloatProcessor(3, 3));
        image.getProcessor().setf(1, 1, 2147483648.0f);
        LabelGeometryExtractor.extract(image);
    }

    @Test
    public void flagsObjectsTouchingTheObservationWindow() {
        ImagePlus image = labels("edge", 5, 5, 1);
        image.getProcessor().set(0, 2, 1);
        image.getProcessor().set(2, 2, 2);

        ChannelGeometry geometry = LabelGeometryExtractor.extract(image);
        assertTrue(geometry.getObject(1).isEdgeObject());
        assertFalse(geometry.getObject(2).isEdgeObject());
        assertFalse(geometry.getObjects().isEmpty());
    }

    @Test
    public void threeDimensionalContactUsesCalibratedFaceArea() {
        ImagePlus sourceImage = labels("source3d", 3, 3, 3);
        ImagePlus targetImage = labels("target3d", 3, 3, 3);
        sourceImage.getStack().getProcessor(1).set(1, 1, 1);
        targetImage.getStack().getProcessor(2).set(1, 1, 2);
        calibrate(sourceImage, 2.0, 3.0, 4.0);
        calibrate(targetImage, 2.0, 3.0, 4.0);

        DirectionResult result = ProximityEngine.analyze(
                LabelGeometryExtractor.extract(sourceImage, "A"),
                LabelGeometryExtractor.extract(targetImage, "B"),
                EnumSet.of(
                        DistanceMode.CENTRE_TO_CENTRE,
                        DistanceMode.EDGE_TO_EDGE,
                        DistanceMode.SURFACE_CONTACT),
                1,
                0.0);
        ObjectMeasurement measurement = result.getMeasurements().get(0);
        assertEquals(4.0, value(
                measurement, DistanceMode.CENTRE_TO_CENTRE), 1.0e-12);
        assertEquals(0.0, value(
                measurement, DistanceMode.EDGE_TO_EDGE), 1.0e-12);
        NeighborMeasurement contact = measurement
                .getNeighbors(DistanceMode.SURFACE_CONTACT).get(0);
        assertEquals(6.0, contact.getExactContactArea(), 1.0e-12);
        assertEquals(6.0, contact.getApposedSurfaceArea(), 1.0e-12);
        assertEquals("\u00b5m^2", result.getSurfaceMeasureUnit());
    }

    /**
     * Pixel sizes that are not dyadic rationals are the common case in
     * microscopy. Deriving a face plane as voxel centre plus half a voxel makes
     * the plane two touching voxels share disagree by one unit in the last
     * place, which silently loses the contact.
     */
    @Test
    public void touchingObjectsContactAtNonDyadicPixelSizes() {
        double[] pixelSizes = {0.1, 0.2, 0.3, 0.1625, 0.3107, 0.065, 1.3};
        for (double pixelSize : pixelSizes) {
            for (int x = 0; x < 24; x++) {
                ImagePlus sourceImage = labels("source", 32, 3, 1);
                sourceImage.getProcessor().set(x, 1, 1);
                ImagePlus targetImage = labels("target", 32, 3, 1);
                targetImage.getProcessor().set(x + 1, 1, 2);
                calibrate(sourceImage, pixelSize, pixelSize, pixelSize);
                calibrate(targetImage, pixelSize, pixelSize, pixelSize);

                DirectionResult result = ProximityEngine.analyze(
                        LabelGeometryExtractor.extract(sourceImage, "A"),
                        LabelGeometryExtractor.extract(targetImage, "B"),
                        EnumSet.of(DistanceMode.EDGE_TO_EDGE,
                                DistanceMode.SURFACE_CONTACT),
                        1,
                        0.0);

                String where = "pixel size " + pixelSize + " at x=" + x;
                ObjectMeasurement measurement = result.getMeasurements().get(0);
                assertEquals(where + ": edge-to-edge must be exactly zero",
                        0.0,
                        value(measurement, DistanceMode.EDGE_TO_EDGE),
                        0.0);
                NeighborMeasurement contact = measurement
                        .getNeighbors(DistanceMode.SURFACE_CONTACT).get(0);
                assertEquals(where + ": exact contact must be one face",
                        pixelSize, contact.getExactContactArea(), 1.0e-12);
                assertEquals(where + ": apposed surface must match exact contact",
                        pixelSize, contact.getApposedSurfaceArea(), 1.0e-12);
                assertTrue(where + ": touching objects are within contact "
                                + "distance zero",
                        measurement.getNeighbors(DistanceMode.EDGE_TO_EDGE)
                                .get(0).isWithinContactDistance());
            }
        }
    }

    @Test
    public void touchingObjectsContactAtNonDyadicPixelSizesWithSpatialOrigin() {
        ImagePlus sourceImage = labels("source", 16, 3, 1);
        sourceImage.getProcessor().set(6, 1, 1);
        ImagePlus targetImage = labels("target", 16, 3, 1);
        targetImage.getProcessor().set(7, 1, 2);
        for (ImagePlus image : new ImagePlus[]{sourceImage, targetImage}) {
            calibrate(image, 0.2, 0.3, 1.0);
            image.getCalibration().xOrigin = 4.0;
            image.getCalibration().yOrigin = 3.0;
        }

        DirectionResult result = ProximityEngine.analyze(
                LabelGeometryExtractor.extract(sourceImage, "A"),
                LabelGeometryExtractor.extract(targetImage, "B"),
                EnumSet.of(DistanceMode.EDGE_TO_EDGE, DistanceMode.SURFACE_CONTACT),
                1,
                0.0);

        ObjectMeasurement measurement = result.getMeasurements().get(0);
        assertEquals(0.0, value(measurement, DistanceMode.EDGE_TO_EDGE), 0.0);
        assertEquals(0.3, measurement.getNeighbors(DistanceMode.SURFACE_CONTACT)
                .get(0).getApposedSurfaceArea(), 1.0e-12);
    }

    private static double value(ObjectMeasurement measurement, DistanceMode mode) {
        return measurement.getNeighbors(mode).get(0).getValue();
    }

    private static ImagePlus labels(String title, int width, int height, int depth) {
        ImageStack stack = new ImageStack(width, height);
        for (int z = 0; z < depth; z++) {
            stack.addSlice(new ByteProcessor(width, height));
        }
        return new ImagePlus(title, stack);
    }

    private static void calibrate(ImagePlus image,
                                  double pixelWidth,
                                  double pixelHeight,
                                  double pixelDepth) {
        Calibration calibration = new Calibration();
        calibration.pixelWidth = pixelWidth;
        calibration.pixelHeight = pixelHeight;
        calibration.pixelDepth = pixelDepth;
        calibration.setUnit("um");
        image.setCalibration(calibration);
    }
}
