/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package sc.fiji.opa.core.geometry;

import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ImageProcessor;
import sc.fiji.opa.core.AnalysisCancelledException;
import sc.fiji.opa.core.CalibrationInfo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Extracts calibrated centroids and exposed voxel faces from a label image.
 */
public final class LabelGeometryExtractor {

    private static final int[][] FACE_DIRECTIONS = {
            {-1, 0, 0}, {1, 0, 0},
            {0, -1, 0}, {0, 1, 0},
            {0, 0, -1}, {0, 0, 1}
    };

    private LabelGeometryExtractor() {
    }

    public static ChannelGeometry extract(ImagePlus image) {
        String name = image == null || image.getTitle() == null || image.getTitle().trim().isEmpty()
                ? "Channel"
                : image.getTitle();
        return extract(image, name);
    }

    public static ChannelGeometry extract(ImagePlus image, String channelName) {
        if (image == null || image.getStack() == null) {
            throw new IllegalArgumentException("Label image must contain an image stack.");
        }
        if (image.getNChannels() != 1 || image.getNFrames() != 1) {
            throw new IllegalArgumentException(
                    "Each label input must contain exactly one channel and one time frame; "
                            + "split channels or frames into separate label images first.");
        }
        int width = image.getWidth();
        int height = image.getHeight();
        int depth = Math.max(1, image.getNSlices());
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Label image dimensions must be positive.");
        }
        // Voxels are addressed by a single int linear index. Rejecting the
        // overflow explicitly beats wrapping to a negative index and failing
        // later with an array bounds error from deep inside the distance loop.
        if ((long) width * (long) height * (long) depth > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                    "Label image has more than " + Integer.MAX_VALUE
                            + " voxels (" + width + " x " + height + " x " + depth
                            + "); crop or downsample the input first.");
        }

        CalibrationInfo calibration = CalibrationInfo.from(image);
        int[][] labels = readLabels(image.getStack(), width, height, depth);
        Map<Integer, Accumulator> accumulators = accumulate(
                labels, width, height, depth, calibration);
        addSurfaces(labels, width, height, depth, calibration, accumulators);

        List<Integer> sortedLabels = new ArrayList<Integer>(accumulators.keySet());
        Collections.sort(sortedLabels);
        List<ObjectGeometry> objects = new ArrayList<ObjectGeometry>(sortedLabels.size());
        for (Integer label : sortedLabels) {
            objects.add(accumulators.get(label).build());
        }

        String name = channelName == null || channelName.trim().isEmpty()
                ? "Channel"
                : channelName.trim();
        return new ChannelGeometry(name, width, height, depth, calibration, labels, objects);
    }

    private static int[][] readLabels(ImageStack stack, int width, int height, int depth) {
        int[][] labels = new int[depth][width * height];
        for (int z = 0; z < depth; z++) {
            AnalysisCancelledException.check();
            ImageProcessor processor = stack.getProcessor(z + 1);
            for (int y = 0; y < height; y++) {
                AnalysisCancelledException.check();
                int row = y * width;
                for (int x = 0; x < width; x++) {
                    labels[z][row + x] = parseLabel(processor.getf(x, y), x, y, z);
                }
            }
        }
        return labels;
    }

    private static Map<Integer, Accumulator> accumulate(int[][] labels,
                                                        int width,
                                                        int height,
                                                        int depth,
                                                        CalibrationInfo calibration) {
        Map<Integer, Accumulator> map = new LinkedHashMap<Integer, Accumulator>();
        for (int z = 0; z < depth; z++) {
            AnalysisCancelledException.check();
            for (int y = 0; y < height; y++) {
                AnalysisCancelledException.check();
                int row = y * width;
                for (int x = 0; x < width; x++) {
                    int label = labels[z][row + x];
                    if (label == 0) continue;
                    Accumulator accumulator = map.get(label);
                    if (accumulator == null) {
                        accumulator = new Accumulator(label);
                        map.put(label, accumulator);
                    }
                    accumulator.addVoxel(
                            calibration.x(x), calibration.y(y), calibration.z(z),
                            z * width * height + row + x,
                            x == 0 || x == width - 1
                                    || y == 0 || y == height - 1
                                    || (depth > 1 && (z == 0 || z == depth - 1)));
                }
            }
        }
        return map;
    }

    private static void addSurfaces(int[][] labels,
                                    int width,
                                    int height,
                                    int depth,
                                    CalibrationInfo calibration,
                                    Map<Integer, Accumulator> accumulators) {
        for (int z = 0; z < depth; z++) {
            AnalysisCancelledException.check();
            for (int y = 0; y < height; y++) {
                AnalysisCancelledException.check();
                int row = y * width;
                for (int x = 0; x < width; x++) {
                    int label = labels[z][row + x];
                    if (label == 0) continue;
                    Accumulator accumulator = accumulators.get(label);
                    int directionCount = depth == 1 ? 4 : FACE_DIRECTIONS.length;
                    for (int directionIndex = 0;
                         directionIndex < directionCount;
                         directionIndex++) {
                        int[] direction = FACE_DIRECTIONS[directionIndex];
                        int nx = x + direction[0];
                        int ny = y + direction[1];
                        int nz = z + direction[2];
                        if (labelAt(labels, width, height, depth, nx, ny, nz) == label) {
                            continue;
                        }
                        accumulator.surface.add(createSurfaceElement(
                                x, y, z, direction, calibration, depth > 1));
                    }
                }
            }
        }
    }

    private static SurfaceElement createSurfaceElement(int x,
                                                       int y,
                                                       int z,
                                                       int[] direction,
                                                       CalibrationInfo calibration,
                                                       boolean threeDimensional) {
        // Every face plane is evaluated from its integer voxel-edge index
        // rather than from the voxel centre plus half a voxel. The plane two
        // touching voxels share is then bit-identical from either side for any
        // pixel size, so a shared face measures exactly zero distance. Deriving
        // it as centre +/- half a voxel disagrees by one unit in the last place
        // for pixel sizes that are not dyadic rationals (0.1, 0.2, 0.1625 um
        // and so on), which loses genuine contacts.
        double lowX = calibration.xEdge(x);
        double highX = calibration.xEdge(x + 1);
        double lowY = calibration.yEdge(y);
        double highY = calibration.yEdge(y + 1);
        double lowZ = calibration.zEdge(z);
        double highZ = calibration.zEdge(z + 1);

        double minX = direction[0] > 0 ? highX : lowX;
        double maxX = direction[0] == 0 ? highX : minX;
        double minY = direction[1] > 0 ? highY : lowY;
        double maxY = direction[1] == 0 ? highY : minY;
        double minZ;
        double maxZ;
        if (!threeDimensional) {
            minZ = calibration.z(z);
            maxZ = minZ;
        } else {
            minZ = direction[2] > 0 ? highZ : lowZ;
            maxZ = direction[2] == 0 ? highZ : minZ;
        }

        double centreX = direction[0] == 0 ? calibration.x(x) : minX;
        double centreY = direction[1] == 0 ? calibration.y(y) : minY;
        double centreZ = !threeDimensional || direction[2] == 0
                ? calibration.z(z)
                : minZ;

        double area;
        if (direction[0] != 0) {
            area = threeDimensional
                    ? calibration.getPixelHeight() * calibration.getPixelDepth()
                    : calibration.getPixelHeight();
        } else if (direction[1] != 0) {
            area = threeDimensional
                    ? calibration.getPixelWidth() * calibration.getPixelDepth()
                    : calibration.getPixelWidth();
        } else {
            area = calibration.getPixelWidth() * calibration.getPixelHeight();
        }
        return new SurfaceElement(
                x, y, z,
                direction[0], direction[1], direction[2],
                centreX, centreY, centreZ, area,
                minX, maxX,
                minY, maxY,
                minZ, maxZ);
    }

    private static int labelAt(int[][] labels,
                               int width,
                               int height,
                               int depth,
                               int x,
                               int y,
                               int z) {
        if (x < 0 || x >= width || y < 0 || y >= height || z < 0 || z >= depth) {
            return 0;
        }
        return labels[z][y * width + x];
    }

    private static int parseLabel(float value, int x, int y, int z) {
        if (value == 0.0f) return 0;
        if (!Float.isFinite(value) || value < 0.0f) {
            throw invalidLabel(value, x, y, z);
        }
        double numericValue = value;
        if (numericValue > Integer.MAX_VALUE) {
            throw invalidLabel(value, x, y, z);
        }
        double rounded = Math.rint(numericValue);
        if (rounded <= 0.0 || numericValue != rounded) {
            throw invalidLabel(value, x, y, z);
        }
        return (int) rounded;
    }

    private static IllegalArgumentException invalidLabel(float value, int x, int y, int z) {
        return new IllegalArgumentException("Label images must contain positive integer labels; found "
                + value + " at (" + x + ", " + y + ", " + z + ").");
    }

    private static final class Accumulator {
        private final int label;
        private int count;
        private double sumX;
        private double sumY;
        private double sumZ;
        private boolean edgeObject;
        private final List<SurfaceElement> surface = new ArrayList<SurfaceElement>();
        private final List<Integer> voxelIndices = new ArrayList<Integer>();

        private Accumulator(int label) {
            this.label = label;
        }

        private void addVoxel(double x,
                              double y,
                              double z,
                              int voxelIndex,
                              boolean edge) {
            count++;
            sumX += x;
            sumY += y;
            sumZ += z;
            voxelIndices.add(voxelIndex);
            edgeObject |= edge;
        }

        private ObjectGeometry build() {
            int[] indices = new int[voxelIndices.size()];
            for (int i = 0; i < indices.length; i++) {
                indices[i] = voxelIndices.get(i);
            }
            return new ObjectGeometry(
                    label,
                    count,
                    sumX / count,
                    sumY / count,
                    sumZ / count,
                    edgeObject,
                    surface,
                    indices);
        }
    }
}
