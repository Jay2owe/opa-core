/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package sc.fiji.opa.core.geometry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import sc.fiji.opa.core.spatial.RectangularWindow;

/**
 * Calibrated geometry extracted for one non-zero label.
 */
public final class ObjectGeometry {

    private final int label;
    private final int voxelCount;
    private final double centroidX;
    private final double centroidY;
    private final double centroidZ;
    private final boolean edgeObject;
    private final List<SurfaceElement> surface;
    private final double surfaceArea;
    private final int[] voxelIndices;

    ObjectGeometry(int label,
                   int voxelCount,
                   double centroidX,
                   double centroidY,
                   double centroidZ,
                   boolean edgeObject,
                   List<SurfaceElement> surface,
                   int[] voxelIndices) {
        this.label = label;
        this.voxelCount = voxelCount;
        this.centroidX = centroidX;
        this.centroidY = centroidY;
        this.centroidZ = centroidZ;
        this.edgeObject = edgeObject;
        this.surface = Collections.unmodifiableList(new ArrayList<SurfaceElement>(surface));
        this.voxelIndices = voxelIndices.clone();
        double area = 0.0;
        for (SurfaceElement element : surface) area += element.area;
        this.surfaceArea = area;
    }

    public int getLabel() {
        return label;
    }

    public int getVoxelCount() {
        return voxelCount;
    }

    public double getCentroidX() {
        return centroidX;
    }

    public double getCentroidY() {
        return centroidY;
    }

    public double getCentroidZ() {
        return centroidZ;
    }

    public boolean isEdgeObject() {
        return edgeObject;
    }

    public double getSurfaceArea() {
        return surfaceArea;
    }

    List<SurfaceElement> surface() {
        return surface;
    }

    int[] voxelIndices() {
        return voxelIndices;
    }

    ObjectGeometry withEffectiveWindow(RectangularWindow window) {
        boolean touchesWindow = false;
        for (SurfaceElement element : surface) {
            if (element.minX <= window.getMinX()
                    || element.maxX >= window.getMaxX()
                    || element.minY <= window.getMinY()
                    || element.maxY >= window.getMaxY()) {
                touchesWindow = true;
                break;
            }
        }
        if (!touchesWindow || edgeObject) return this;
        return new ObjectGeometry(
                label,
                voxelCount,
                centroidX,
                centroidY,
                centroidZ,
                true,
                surface,
                voxelIndices);
    }
}
