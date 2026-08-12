/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package sc.fiji.opa.core.geometry;

import sc.fiji.opa.core.DistanceMode;

/**
 * One ranked source-to-partner measurement.
 */
public final class NeighborMeasurement {

    private final DistanceMode mode;
    private final int rank;
    private final int partnerLabel;
    private final double value;
    private final boolean withinContactDistance;
    private final double exactContactArea;
    private final double apposedSurfaceArea;

    NeighborMeasurement(DistanceMode mode,
                        int rank,
                        int partnerLabel,
                        double value,
                        boolean withinContactDistance,
                        double exactContactArea,
                        double apposedSurfaceArea) {
        this.mode = mode;
        this.rank = rank;
        this.partnerLabel = partnerLabel;
        this.value = value;
        this.withinContactDistance = withinContactDistance;
        this.exactContactArea = exactContactArea;
        this.apposedSurfaceArea = apposedSurfaceArea;
    }

    public DistanceMode getMode() {
        return mode;
    }

    public int getRank() {
        return rank;
    }

    public int getPartnerLabel() {
        return partnerLabel;
    }

    /**
     * Distance for distance modes; apposed surface area for SURFACE_CONTACT.
     */
    public double getValue() {
        return value;
    }

    public boolean isWithinContactDistance() {
        return withinContactDistance;
    }

    public double getExactContactArea() {
        return exactContactArea;
    }

    public double getApposedSurfaceArea() {
        return apposedSurfaceArea;
    }
}
