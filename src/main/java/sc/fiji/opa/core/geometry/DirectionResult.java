/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package sc.fiji.opa.core.geometry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Measurements in one source-to-target direction.
 */
public final class DirectionResult {

    private final String sourceChannel;
    private final String targetChannel;
    private final boolean selfComparison;
    private final String unit;
    private final String surfaceMeasureUnit;
    private final int sourceObjectCount;
    private final int targetObjectCount;
    private final List<ObjectMeasurement> measurements;

    DirectionResult(String sourceChannel,
                    String targetChannel,
                    boolean selfComparison,
                     String unit,
                     String surfaceMeasureUnit,
                     int sourceObjectCount,
                     int targetObjectCount,
                     List<ObjectMeasurement> measurements) {
        this.sourceChannel = sourceChannel;
        this.targetChannel = targetChannel;
        this.selfComparison = selfComparison;
        this.unit = unit;
        this.surfaceMeasureUnit = surfaceMeasureUnit;
        this.sourceObjectCount = sourceObjectCount;
        this.targetObjectCount = targetObjectCount;
        this.measurements = Collections.unmodifiableList(
                new ArrayList<ObjectMeasurement>(measurements));
    }

    public String getSourceChannel() {
        return sourceChannel;
    }

    public String getTargetChannel() {
        return targetChannel;
    }

    public boolean isSelfComparison() {
        return selfComparison;
    }

    public String getUnit() {
        return unit;
    }

    public String getSurfaceMeasureUnit() {
        return surfaceMeasureUnit;
    }

    public int getSourceObjectCount() {
        return sourceObjectCount;
    }

    public int getTargetObjectCount() {
        return targetObjectCount;
    }

    public List<ObjectMeasurement> getMeasurements() {
        return measurements;
    }
}
