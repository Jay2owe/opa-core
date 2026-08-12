/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package sc.fiji.opa.core;

import ij.ImagePlus;
import ij.measure.Calibration;

import java.util.Locale;

/**
 * Immutable voxel calibration used by all geometry calculations.
 */
public final class CalibrationInfo {

    private final double pixelWidth;
    private final double pixelHeight;
    private final double pixelDepth;
    private final double xOrigin;
    private final double yOrigin;
    private final double zOrigin;
    private final String unit;
    private final boolean physicalUnits;

    private CalibrationInfo(double pixelWidth,
                            double pixelHeight,
                            double pixelDepth,
                            double xOrigin,
                            double yOrigin,
                            double zOrigin,
                            String unit,
                            boolean physicalUnits) {
        this.pixelWidth = pixelWidth;
        this.pixelHeight = pixelHeight;
        this.pixelDepth = pixelDepth;
        this.xOrigin = xOrigin;
        this.yOrigin = yOrigin;
        this.zOrigin = zOrigin;
        this.unit = unit;
        this.physicalUnits = physicalUnits;
    }

    public static CalibrationInfo from(ImagePlus image) {
        if (image == null) {
            throw new IllegalArgumentException("Image must not be null.");
        }
        Calibration calibration = image.getCalibration();
        double width = calibration == null
                ? 1.0
                : validatedDimension(calibration.pixelWidth, "pixel width");
        double height = calibration == null
                ? 1.0
                : validatedDimension(calibration.pixelHeight, "pixel height");
        double depth = calibration == null
                ? 1.0
                : validatedDimension(calibration.pixelDepth, "pixel depth");
        double xOrigin = calibration == null
                ? 0.0
                : validatedOrigin(calibration.xOrigin, "X origin");
        double yOrigin = calibration == null
                ? 0.0
                : validatedOrigin(calibration.yOrigin, "Y origin");
        double zOrigin = calibration == null
                ? 0.0
                : validatedOrigin(calibration.zOrigin, "Z origin");
        String rawUnit = calibration == null ? null : calibration.getUnit();
        String unit = normalizeUnit(rawUnit);
        boolean physical = !isPixelUnit(unit);
        return new CalibrationInfo(
                width,
                height,
                depth,
                xOrigin,
                yOrigin,
                zOrigin,
                unit,
                physical);
    }

    public double getPixelWidth() {
        return pixelWidth;
    }

    public double getPixelHeight() {
        return pixelHeight;
    }

    public double getPixelDepth() {
        return pixelDepth;
    }

    public double getXOrigin() {
        return xOrigin;
    }

    public double getYOrigin() {
        return yOrigin;
    }

    public double getZOrigin() {
        return zOrigin;
    }

    public String getUnit() {
        return unit;
    }

    /**
     * True when the image declares a physical unit rather than pixels.
     */
    public boolean hasPhysicalUnits() {
        return physicalUnits;
    }

    public boolean isCompatibleWith(CalibrationInfo other) {
        if (other == null) return false;
        return nearlyEqual(pixelWidth, other.pixelWidth)
                && nearlyEqual(pixelHeight, other.pixelHeight)
                && nearlyEqual(pixelDepth, other.pixelDepth)
                && nearlyEqual(xOrigin, other.xOrigin)
                && nearlyEqual(yOrigin, other.yOrigin)
                && nearlyEqual(zOrigin, other.zOrigin)
                && unit.equalsIgnoreCase(other.unit);
    }

    public double x(int pixelX) {
        return xEdge(pixelX + 0.5);
    }

    public double y(int pixelY) {
        return yEdge(pixelY + 0.5);
    }

    public double z(int pixelZ) {
        return zEdge(pixelZ + 0.5);
    }

    public double xEdge(double pixelEdgeX) {
        return (pixelEdgeX - xOrigin) * pixelWidth;
    }

    public double yEdge(double pixelEdgeY) {
        return (pixelEdgeY - yOrigin) * pixelHeight;
    }

    public double zEdge(double pixelEdgeZ) {
        return (pixelEdgeZ - zOrigin) * pixelDepth;
    }

    public int pixelX(double calibratedX) {
        return (int) Math.floor(calibratedX / pixelWidth + xOrigin);
    }

    public int pixelY(double calibratedY) {
        return (int) Math.floor(calibratedY / pixelHeight + yOrigin);
    }

    public int pixelZ(double calibratedZ) {
        return (int) Math.floor(calibratedZ / pixelDepth + zOrigin);
    }

    private static double validatedDimension(double value, String name) {
        if (!Double.isFinite(value) || value <= 0.0) {
            throw new IllegalArgumentException(
                    "Image calibration " + name
                            + " must be a finite positive value; found " + value + ".");
        }
        return value;
    }

    private static double validatedOrigin(double value, String name) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(
                    "Image calibration " + name
                            + " must be finite; found " + value + ".");
        }
        return value;
    }

    private static String normalizeUnit(String value) {
        if (value == null || value.trim().isEmpty()) return "pixel";
        return value.trim();
    }

    private static boolean isPixelUnit(String value) {
        String unit = value.toLowerCase(Locale.ROOT);
        return unit.equals("pixel") || unit.equals("pixels") || unit.equals("px");
    }

    private static boolean nearlyEqual(double a, double b) {
        double scale = Math.max(1.0, Math.max(Math.abs(a), Math.abs(b)));
        return Math.abs(a - b) <= 1.0e-9 * scale;
    }
}
