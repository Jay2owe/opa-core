/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package sc.fiji.opa.core.spatial;

import java.util.Arrays;

/**
 * Radius/value pair returned by the pure spatial-statistics API.
 */
public final class SpatialCurve {

    private final double[] radii;
    private final double[] values;

    public SpatialCurve(double[] radii, double[] values) {
        if (radii == null || values == null || radii.length != values.length) {
            throw new IllegalArgumentException(
                    "Radii and values must be non-null arrays of equal length.");
        }
        this.radii = Arrays.copyOf(radii, radii.length);
        this.values = Arrays.copyOf(values, values.length);
    }

    public double[] getRadii() {
        return Arrays.copyOf(radii, radii.length);
    }

    public double[] getValues() {
        return Arrays.copyOf(values, values.length);
    }
}
