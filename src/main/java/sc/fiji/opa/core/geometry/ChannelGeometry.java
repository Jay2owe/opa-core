/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package sc.fiji.opa.core.geometry;

import sc.fiji.opa.core.CalibrationInfo;
import sc.fiji.opa.core.spatial.RectangularWindow;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Extracted objects and label lookup for one input channel.
 */
public final class ChannelGeometry {

    private final String name;
    private final int width;
    private final int height;
    private final int depth;
    private final CalibrationInfo calibration;
    private final int[][] labels;
    private final List<ObjectGeometry> objects;
    private final Map<Integer, ObjectGeometry> byLabel;

    ChannelGeometry(String name,
                    int width,
                    int height,
                    int depth,
                    CalibrationInfo calibration,
                    int[][] labels,
                    List<ObjectGeometry> objects) {
        this.name = name;
        this.width = width;
        this.height = height;
        this.depth = depth;
        this.calibration = calibration;
        this.labels = labels;
        this.objects = Collections.unmodifiableList(new ArrayList<ObjectGeometry>(objects));
        Map<Integer, ObjectGeometry> map = new LinkedHashMap<Integer, ObjectGeometry>();
        for (ObjectGeometry object : objects) map.put(object.getLabel(), object);
        this.byLabel = Collections.unmodifiableMap(map);
    }

    public String getName() {
        return name;
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public int getDepth() {
        return depth;
    }

    public CalibrationInfo getCalibration() {
        return calibration;
    }

    public List<ObjectGeometry> getObjects() {
        return objects;
    }

    public ObjectGeometry getObject(int label) {
        return byLabel.get(label);
    }

    public int labelAt(int x, int y, int z) {
        if (x < 0 || x >= width || y < 0 || y >= height || z < 0 || z >= depth) {
            return 0;
        }
        return labels[z][y * width + x];
    }

    int labelAtIndex(int linearIndex) {
        int sliceSize = width * height;
        int z = linearIndex / sliceSize;
        int withinSlice = linearIndex % sliceSize;
        return labels[z][withinSlice];
    }

    int labelAtCalibrated(double x, double y, double z) {
        return labelAt(
                calibration.pixelX(x),
                calibration.pixelY(y),
                calibration.pixelZ(z));
    }

    public double[][] centroidPoints2D() {
        double[][] points = new double[objects.size()][2];
        for (int i = 0; i < objects.size(); i++) {
            points[i][0] = objects.get(i).getCentroidX();
            points[i][1] = objects.get(i).getCentroidY();
        }
        return points;
    }

    public ChannelGeometry within(RectangularWindow window) {
        if (window == null) return this;
        List<ObjectGeometry> included = new ArrayList<ObjectGeometry>();
        for (ObjectGeometry object : objects) {
            if (window.contains(
                    object.getCentroidX(), object.getCentroidY())) {
                included.add(object.withEffectiveWindow(window));
            }
        }
        return new ChannelGeometry(
                name,
                width,
                height,
                depth,
                calibration,
                labels,
                included);
    }
}
