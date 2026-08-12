/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package sc.fiji.opa.core.geometry;

import sc.fiji.opa.core.DistanceMode;

import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * All ranked proximity measurements for one source object.
 */
public final class ObjectMeasurement {

    private final int sourceLabel;
    private final boolean edgeObject;
    private final Map<DistanceMode, List<NeighborMeasurement>> neighbors;

    ObjectMeasurement(int sourceLabel,
                      boolean edgeObject,
                      Map<DistanceMode, List<NeighborMeasurement>> neighbors) {
        this.sourceLabel = sourceLabel;
        this.edgeObject = edgeObject;
        this.neighbors = Collections.unmodifiableMap(
                new EnumMap<DistanceMode, List<NeighborMeasurement>>(neighbors));
    }

    public int getSourceLabel() {
        return sourceLabel;
    }

    public boolean isEdgeObject() {
        return edgeObject;
    }

    public List<NeighborMeasurement> getNeighbors(DistanceMode mode) {
        List<NeighborMeasurement> values = neighbors.get(mode);
        return values == null ? Collections.<NeighborMeasurement>emptyList() : values;
    }

    public Map<DistanceMode, List<NeighborMeasurement>> getNeighborsByMode() {
        return neighbors;
    }
}
