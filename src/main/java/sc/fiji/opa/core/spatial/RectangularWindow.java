/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package sc.fiji.opa.core.spatial;

/**
 * Calibrated rectangular 2D observation window.
 */
public final class RectangularWindow {

    private final double minX;
    private final double minY;
    private final double maxX;
    private final double maxY;

    public RectangularWindow(double minX, double minY, double maxX, double maxY) {
        if (!Double.isFinite(minX) || !Double.isFinite(minY)
                || !Double.isFinite(maxX) || !Double.isFinite(maxY)
                || maxX <= minX || maxY <= minY) {
            throw new IllegalArgumentException(
                    "Window bounds must define a finite positive-area rectangle.");
        }
        this.minX = minX;
        this.minY = minY;
        this.maxX = maxX;
        this.maxY = maxY;
    }

    public double getMinX() {
        return minX;
    }

    public double getMinY() {
        return minY;
    }

    public double getMaxX() {
        return maxX;
    }

    public double getMaxY() {
        return maxY;
    }

    public double width() {
        return maxX - minX;
    }

    public double height() {
        return maxY - minY;
    }

    public double area() {
        return width() * height();
    }

    public boolean contains(double x, double y) {
        return x >= minX && x <= maxX && y >= minY && y <= maxY;
    }

    double boundaryDistance(double x, double y) {
        return Math.min(
                Math.min(x - minX, maxX - x),
                Math.min(y - minY, maxY - y));
    }

    double translationOverlap(double dx, double dy) {
        double overlapWidth = width() - Math.abs(dx);
        double overlapHeight = height() - Math.abs(dy);
        if (overlapWidth <= 0.0 || overlapHeight <= 0.0) return 0.0;
        return overlapWidth * overlapHeight;
    }
}
