/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package sc.fiji.opa.core.geometry;

import sc.fiji.opa.core.AnalysisCancelledException;
import sc.fiji.opa.core.DistanceMode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Exact brute-force distance engine for calibrated object geometry.
 *
 * <p>The implementation favours transparent, testable geometry for the first
 * release. A spatial index can replace the pair search later without changing
 * the public result model. Cost grows with the square of the object count and
 * the square of the per-object surface size.</p>
 *
 * <p>A self-comparison is detected by reference identity, so pass the very same
 * {@link ChannelGeometry} instance as both source and target to exclude each
 * object from its own neighbour list. Two separately extracted instances of the
 * same image are treated as different channels, and every object then matches
 * itself at distance zero.</p>
 */
public final class ProximityEngine {

    private ProximityEngine() {
    }

    public static DirectionResult analyze(ChannelGeometry source,
                                          ChannelGeometry target,
                                          EnumSet<DistanceMode> modes,
                                          int neighborCount,
                                          double contactDistance) {
        validate(source, target, modes, neighborCount, contactDistance);
        AnalysisCancelledException.check();
        final boolean self = source == target;
        List<ObjectGeometry> sourceObjects = source.getObjects();
        List<ObjectMeasurement> output =
                new ArrayList<ObjectMeasurement>(sourceObjects.size());
        int workers = workerCount(sourceObjects.size());

        if (workers == 1) {
            for (ObjectGeometry sourceObject : sourceObjects) {
                output.add(measureSource(sourceObject, source, target, modes,
                        neighborCount, contactDistance, self));
            }
        } else {
            ExecutorService executor = Executors.newFixedThreadPool(workers);
            List<Future<ObjectMeasurement>> futures =
                    new ArrayList<Future<ObjectMeasurement>>(sourceObjects.size());
            try {
                for (final ObjectGeometry sourceObject : sourceObjects) {
                    futures.add(executor.submit(new Callable<ObjectMeasurement>() {
                        @Override
                        public ObjectMeasurement call() {
                            return measureSource(sourceObject, source, target, modes,
                                    neighborCount, contactDistance, self);
                        }
                    }));
                }
                for (Future<ObjectMeasurement> future : futures) {
                    output.add(measurement(future, futures));
                }
            } finally {
                stop(executor);
            }
        }

        AnalysisCancelledException.check();
        return new DirectionResult(
                source.getName(),
                target.getName(),
                self,
                source.getCalibration().getUnit(),
                source.getDepth() > 1
                        ? source.getCalibration().getUnit() + "^2"
                        : source.getCalibration().getUnit(),
                source.getObjects().size(),
                target.getObjects().size(),
                output);
    }

    private static ObjectMeasurement measureSource(
            ObjectGeometry sourceObject,
            ChannelGeometry source,
            ChannelGeometry target,
            EnumSet<DistanceMode> modes,
            int neighborCount,
            double contactDistance,
            boolean self) {
        AnalysisCancelledException.check();
        List<PairMeasurement> pairs = new ArrayList<PairMeasurement>();
        for (ObjectGeometry targetObject : target.getObjects()) {
            AnalysisCancelledException.check();
            if (self && sourceObject.getLabel() == targetObject.getLabel()) continue;
            pairs.add(measurePair(
                    sourceObject, targetObject, source, target, contactDistance));
        }

        Map<DistanceMode, List<NeighborMeasurement>> byMode =
                new EnumMap<DistanceMode, List<NeighborMeasurement>>(DistanceMode.class);
        for (DistanceMode mode : modes) {
            AnalysisCancelledException.check();
            List<PairMeasurement> ranked = new ArrayList<PairMeasurement>(pairs);
            Collections.sort(ranked, comparator(mode));
            AnalysisCancelledException.check();
            int count = Math.min(neighborCount, ranked.size());
            List<NeighborMeasurement> neighbors =
                    new ArrayList<NeighborMeasurement>(count);
            for (int i = 0; i < count; i++) {
                AnalysisCancelledException.check();
                PairMeasurement pair = ranked.get(i);
                neighbors.add(new NeighborMeasurement(
                        mode, i + 1, pair.partnerLabel, pair.value(mode),
                        pair.edgeToEdge <= contactDistance,
                        pair.exactContactArea, pair.apposedSurfaceArea));
            }
            byMode.put(mode, Collections.unmodifiableList(neighbors));
        }
        AnalysisCancelledException.check();
        return new ObjectMeasurement(
                sourceObject.getLabel(), sourceObject.isEdgeObject(), byMode);
    }

    private static int workerCount(int tasks) {
        if (tasks < 2) return 1;
        int configured = Integer.getInteger("opa.parallelism", 0).intValue();
        int available = Runtime.getRuntime().availableProcessors();
        int desired = configured > 0 ? configured : Math.min(available, 8);
        return Math.max(1, Math.min(tasks, desired));
    }

    private static ObjectMeasurement measurement(
            Future<ObjectMeasurement> future,
            List<? extends Future<?>> futures) {
        try {
            return future.get();
        } catch (InterruptedException interrupted) {
            cancel(futures);
            Thread.currentThread().interrupt();
            throw new AnalysisCancelledException();
        } catch (ExecutionException failed) {
            cancel(futures);
            Throwable cause = failed.getCause();
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            if (cause instanceof Error) throw (Error) cause;
            throw new IllegalStateException("Object proximity worker failed.", cause);
        }
    }

    private static void cancel(List<? extends Future<?>> futures) {
        for (Future<?> future : futures) future.cancel(true);
    }

    private static void stop(ExecutorService executor) {
        executor.shutdownNow();
        try {
            executor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static PairMeasurement measurePair(ObjectGeometry source,
                                               ObjectGeometry target,
                                               ChannelGeometry sourceChannel,
                                               ChannelGeometry targetChannel,
                                               double contactDistance) {
        double centreToCentre = distance(
                source.getCentroidX(), source.getCentroidY(), source.getCentroidZ(),
                target.getCentroidX(), target.getCentroidY(), target.getCentroidZ());
        double centreToEdge = pointToSurface(
                source.getCentroidX(), source.getCentroidY(), source.getCentroidZ(),
                target.surface());
        if (targetChannel.labelAtCalibrated(
                source.getCentroidX(),
                source.getCentroidY(),
                source.getCentroidZ()) == target.getLabel()) {
            centreToEdge = 0.0;
        }
        double edgeToCentre = pointToSurface(
                target.getCentroidX(), target.getCentroidY(), target.getCentroidZ(),
                source.surface());
        if (sourceChannel.labelAtCalibrated(
                target.getCentroidX(),
                target.getCentroidY(),
                target.getCentroidZ()) == source.getLabel()) {
            edgeToCentre = 0.0;
        }

        double edgeToEdge = Double.POSITIVE_INFINITY;
        for (SurfaceElement sourceFace : source.surface()) {
            AnalysisCancelledException.check();
            for (SurfaceElement targetFace : target.surface()) {
                AnalysisCancelledException.check();
                double value = surfaceDistance(sourceFace, targetFace);
                if (value < edgeToEdge) edgeToEdge = value;
            }
        }
        if (overlaps(source, target.getLabel(), targetChannel)) {
            edgeToEdge = 0.0;
        }
        if (!Double.isFinite(edgeToEdge)) edgeToEdge = centreToCentre;

        double exactContactArea = 0.0;
        double apposedSurfaceArea = 0.0;
        for (SurfaceElement sourceFace : source.surface()) {
            AnalysisCancelledException.check();
            int adjacentLabel = targetChannel.labelAt(
                    sourceFace.voxelX + sourceFace.normalX,
                    sourceFace.voxelY + sourceFace.normalY,
                    sourceFace.voxelZ + sourceFace.normalZ);
            if (adjacentLabel == target.getLabel()) {
                exactContactArea += sourceFace.area;
            }
            if (distanceToSurface(sourceFace, target.surface()) <= contactDistance) {
                apposedSurfaceArea += sourceFace.area;
            }
        }

        return new PairMeasurement(
                target.getLabel(),
                centreToCentre,
                centreToEdge,
                edgeToCentre,
                edgeToEdge,
                exactContactArea,
                apposedSurfaceArea);
    }

    private static double pointToSurface(double x,
                                         double y,
                                         double z,
                                         List<SurfaceElement> surface) {
        double minimum = Double.POSITIVE_INFINITY;
        for (SurfaceElement face : surface) {
            AnalysisCancelledException.check();
            double value = pointToSurfaceDistance(x, y, z, face);
            if (value < minimum) minimum = value;
        }
        return minimum;
    }

    private static double distanceToSurface(SurfaceElement source,
                                            List<SurfaceElement> target) {
        double minimum = Double.POSITIVE_INFINITY;
        for (SurfaceElement face : target) {
            AnalysisCancelledException.check();
            if (source.normalX + face.normalX != 0
                    || source.normalY + face.normalY != 0
                    || source.normalZ + face.normalZ != 0) {
                continue;
            }
            double value = surfaceDistance(source, face);
            if (value < minimum) minimum = value;
        }
        return minimum;
    }

    private static boolean overlaps(ObjectGeometry source,
                                    int targetLabel,
                                    ChannelGeometry targetChannel) {
        for (int voxelIndex : source.voxelIndices()) {
            AnalysisCancelledException.check();
            if (targetChannel.labelAtIndex(voxelIndex) == targetLabel) return true;
        }
        return false;
    }

    private static double pointToSurfaceDistance(double x,
                                                 double y,
                                                 double z,
                                                 SurfaceElement face) {
        double dx = intervalDistance(x, face.minX, face.maxX);
        double dy = intervalDistance(y, face.minY, face.maxY);
        double dz = intervalDistance(z, face.minZ, face.maxZ);
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static double surfaceDistance(SurfaceElement first,
                                          SurfaceElement second) {
        double dx = intervalDistance(
                first.minX, first.maxX, second.minX, second.maxX);
        double dy = intervalDistance(
                first.minY, first.maxY, second.minY, second.maxY);
        double dz = intervalDistance(
                first.minZ, first.maxZ, second.minZ, second.maxZ);
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static double intervalDistance(double value,
                                           double minimum,
                                           double maximum) {
        if (value < minimum) return minimum - value;
        if (value > maximum) return value - maximum;
        return 0.0;
    }

    private static double intervalDistance(double firstMinimum,
                                           double firstMaximum,
                                           double secondMinimum,
                                           double secondMaximum) {
        if (firstMaximum < secondMinimum) return secondMinimum - firstMaximum;
        if (secondMaximum < firstMinimum) return firstMinimum - secondMaximum;
        return 0.0;
    }

    private static Comparator<PairMeasurement> comparator(final DistanceMode mode) {
        return new Comparator<PairMeasurement>() {
            @Override
            public int compare(PairMeasurement first, PairMeasurement second) {
                int comparison;
                if (mode == DistanceMode.SURFACE_CONTACT) {
                    comparison = -Double.compare(
                            first.apposedSurfaceArea, second.apposedSurfaceArea);
                    if (comparison == 0) {
                        comparison = -Double.compare(
                                first.exactContactArea, second.exactContactArea);
                    }
                } else {
                    comparison = Double.compare(first.value(mode), second.value(mode));
                }
                return comparison != 0
                        ? comparison
                        : Integer.compare(first.partnerLabel, second.partnerLabel);
            }
        };
    }

    private static double distance(double x1, double y1, double z1,
                                   double x2, double y2, double z2) {
        double dx = x1 - x2;
        double dy = y1 - y2;
        double dz = z1 - z2;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static void validate(ChannelGeometry source,
                                 ChannelGeometry target,
                                 EnumSet<DistanceMode> modes,
                                 int neighborCount,
                                 double contactDistance) {
        if (source == null || target == null) {
            throw new IllegalArgumentException("Source and target geometry must not be null.");
        }
        if (source.getWidth() != target.getWidth()
                || source.getHeight() != target.getHeight()
                || source.getDepth() != target.getDepth()) {
            throw new IllegalArgumentException(
                    "Source and target label images must have identical dimensions.");
        }
        if (!source.getCalibration().isCompatibleWith(target.getCalibration())) {
            throw new IllegalArgumentException(
                    "Source and target label images must have identical voxel calibration.");
        }
        if (modes == null || modes.isEmpty()) {
            throw new IllegalArgumentException("At least one distance mode is required.");
        }
        if (neighborCount < 1) {
            throw new IllegalArgumentException("Neighbor count must be at least 1.");
        }
        if (!Double.isFinite(contactDistance) || contactDistance < 0.0) {
            throw new IllegalArgumentException(
                    "Contact distance must be a finite non-negative value.");
        }
    }

    private static final class PairMeasurement {
        private final int partnerLabel;
        private final double centreToCentre;
        private final double centreToEdge;
        private final double edgeToCentre;
        private final double edgeToEdge;
        private final double exactContactArea;
        private final double apposedSurfaceArea;

        private PairMeasurement(int partnerLabel,
                                double centreToCentre,
                                double centreToEdge,
                                double edgeToCentre,
                                double edgeToEdge,
                                double exactContactArea,
                                double apposedSurfaceArea) {
            this.partnerLabel = partnerLabel;
            this.centreToCentre = centreToCentre;
            this.centreToEdge = centreToEdge;
            this.edgeToCentre = edgeToCentre;
            this.edgeToEdge = edgeToEdge;
            this.exactContactArea = exactContactArea;
            this.apposedSurfaceArea = apposedSurfaceArea;
        }

        private double value(DistanceMode mode) {
            switch (mode) {
                case CENTRE_TO_CENTRE:
                    return centreToCentre;
                case CENTRE_TO_EDGE:
                    return centreToEdge;
                case EDGE_TO_CENTRE:
                    return edgeToCentre;
                case EDGE_TO_EDGE:
                    return edgeToEdge;
                case SURFACE_CONTACT:
                    return apposedSurfaceArea;
                default:
                    throw new IllegalArgumentException("Unsupported distance mode: " + mode);
            }
        }
    }
}
