/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package sc.fiji.opa.core;

/**
 * Supported directed object-proximity measurements.
 */
public enum DistanceMode {
    CENTRE_TO_CENTRE("Centre-Centre", true),
    CENTRE_TO_EDGE("Centre-Edge", true),
    EDGE_TO_CENTRE("Edge-Centre", true),
    EDGE_TO_EDGE("Edge-Edge", true),
    SURFACE_CONTACT("Surface-Contact", false);

    private final String columnName;
    private final boolean distance;

    DistanceMode(String columnName, boolean distance) {
        this.columnName = columnName;
        this.distance = distance;
    }

    public String getColumnName() {
        return columnName;
    }

    public boolean isDistance() {
        return distance;
    }
}
