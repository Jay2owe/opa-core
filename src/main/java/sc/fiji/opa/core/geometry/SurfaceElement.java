/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package sc.fiji.opa.core.geometry;

/**
 * One exposed voxel face, represented at its calibrated face centre.
 */
final class SurfaceElement {
    final int voxelX;
    final int voxelY;
    final int voxelZ;
    final int normalX;
    final int normalY;
    final int normalZ;
    final double x;
    final double y;
    final double z;
    final double area;
    final double minX;
    final double maxX;
    final double minY;
    final double maxY;
    final double minZ;
    final double maxZ;

    SurfaceElement(int voxelX,
                   int voxelY,
                   int voxelZ,
                   int normalX,
                   int normalY,
                   int normalZ,
                   double x,
                   double y,
                   double z,
                   double area,
                   double minX,
                   double maxX,
                   double minY,
                   double maxY,
                   double minZ,
                   double maxZ) {
        this.voxelX = voxelX;
        this.voxelY = voxelY;
        this.voxelZ = voxelZ;
        this.normalX = normalX;
        this.normalY = normalY;
        this.normalZ = normalZ;
        this.x = x;
        this.y = y;
        this.z = z;
        this.area = area;
        this.minX = minX;
        this.maxX = maxX;
        this.minY = minY;
        this.maxY = maxY;
        this.minZ = minZ;
        this.maxZ = maxZ;
    }
}
