/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package sc.fiji.opa.core.geometry;

import sc.fiji.opa.core.AnalysisCancelledException;
import sc.fiji.opa.core.DistanceMode;
import sc.fiji.opa.core.ProgressListener;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Exact distance engine for calibrated object geometry.
 *
 * <p>Every reported value is the exact face-by-face measurement. Pairs that
 * cannot reach the k nearest are skipped using lower bounds taken from each
 * object's surface bounding box: a bound is computed with the same
 * floating-point operations as the exact distance, on coordinates that
 * enclose every face, so it never exceeds the exact value. Candidates are
 * visited in ascending (bound, label) order and the search stops at the first
 * bound strictly above the k-th best value, so equal distances still break
 * ties on label exactly as a full sort would. Results are bit-identical to
 * the brute-force engine this replaced.</p>
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
        return analyze(source, target, modes, neighborCount, contactDistance, null);
    }

    /**
     * As {@link #analyze(ChannelGeometry, ChannelGeometry, EnumSet, int, double)},
     * reporting the fraction of source objects finished to {@code progress}
     * (may be null). Calls to the listener are serialised and never go
     * backwards, even when several worker threads are measuring.
     */
    public static DirectionResult analyze(final ChannelGeometry source,
                                          final ChannelGeometry target,
                                          final EnumSet<DistanceMode> modes,
                                          final int neighborCount,
                                          final double contactDistance,
                                          ProgressListener progress) {
        validate(source, target, modes, neighborCount, contactDistance);
        AnalysisCancelledException.check();
        final boolean self = source == target;
        List<ObjectGeometry> sourceObjects = source.getObjects();
        List<ObjectMeasurement> output =
                new ArrayList<ObjectMeasurement>(sourceObjects.size());
        int workers = workerCount(sourceObjects.size());
        final Progress tracker = new Progress(progress, sourceObjects.size(),
                "Distances " + source.getName() + " -> " + target.getName());

        if (workers == 1) {
            for (ObjectGeometry sourceObject : sourceObjects) {
                output.add(measureSource(sourceObject, source, target, modes,
                        neighborCount, contactDistance, self));
                tracker.finishedOne();
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
                            ObjectMeasurement measured = measureSource(
                                    sourceObject, source, target, modes,
                                    neighborCount, contactDistance, self);
                            tracker.finishedOne();
                            return measured;
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
        SourceContext context = new SourceContext(
                sourceObject, source, target, contactDistance);
        List<Pair> pairs = new ArrayList<Pair>(target.getObjects().size());
        for (ObjectGeometry targetObject : target.getObjects()) {
            if (self && sourceObject.getLabel() == targetObject.getLabel()) continue;
            pairs.add(new Pair(context, targetObject));
        }

        Map<DistanceMode, List<NeighborMeasurement>> byMode =
                new EnumMap<DistanceMode, List<NeighborMeasurement>>(DistanceMode.class);
        for (DistanceMode mode : modes) {
            AnalysisCancelledException.check();
            List<Pair> ranked = mode == DistanceMode.SURFACE_CONTACT
                    ? rankByContact(pairs, neighborCount)
                    : rankByDistance(pairs, mode, neighborCount);
            List<NeighborMeasurement> neighbors =
                    new ArrayList<NeighborMeasurement>(ranked.size());
            for (int i = 0; i < ranked.size(); i++) {
                AnalysisCancelledException.check();
                Pair pair = ranked.get(i);
                neighbors.add(new NeighborMeasurement(
                        mode, i + 1, pair.label, pair.value(mode),
                        pair.edgeToEdge() <= contactDistance,
                        pair.exactContactArea(), pair.apposedSurfaceArea()));
            }
            byMode.put(mode, Collections.unmodifiableList(neighbors));
        }
        AnalysisCancelledException.check();
        return new ObjectMeasurement(
                sourceObject.getLabel(), sourceObject.isEdgeObject(), byMode);
    }

    /**
     * The first {@code count} pairs of the full (value, label) order, found by
     * evaluating exact values only while a pair's lower bound can still reach
     * the current k-th best.
     */
    private static List<Pair> rankByDistance(List<Pair> pairs,
                                             final DistanceMode mode,
                                             int count) {
        int size = pairs.size();
        final double[] bounds = new double[size];
        Integer[] order = new Integer[size];
        for (int i = 0; i < size; i++) {
            bounds[i] = pairs.get(i).lowerBound(mode);
            order[i] = i;
        }
        final List<Pair> all = pairs;
        Arrays.sort(order, new Comparator<Integer>() {
            @Override
            public int compare(Integer first, Integer second) {
                int comparison = Double.compare(bounds[first], bounds[second]);
                return comparison != 0
                        ? comparison
                        : Integer.compare(all.get(first).label, all.get(second).label);
            }
        });

        int limit = Math.min(count, size);
        List<Pair> best = new ArrayList<Pair>(limit + 1);
        Comparator<Pair> byValue = valueOrder(mode);
        for (Integer index : order) {
            if (best.size() == limit && limit > 0
                    && Double.compare(bounds[index],
                            best.get(limit - 1).value(mode)) > 0) {
                break;
            }
            AnalysisCancelledException.check();
            Pair candidate = pairs.get(index);
            if (best.size() == limit) {
                if (limit == 0
                        || byValue.compare(candidate, best.get(limit - 1)) >= 0) {
                    continue;
                }
                best.remove(limit - 1);
            }
            int position = Collections.binarySearch(best, candidate, byValue);
            best.add(position < 0 ? -position - 1 : position, candidate);
        }
        return best;
    }

    /**
     * Surface contact ranks by apposed area, then exact contact area, then
     * label. A pair whose box gap exceeds the contact distance has no apposed
     * area, and a pair not adjacent to any source face has no exact contact,
     * so neither needs the face-by-face scan.
     */
    private static List<Pair> rankByContact(List<Pair> pairs, int count) {
        List<Pair> ranked = new ArrayList<Pair>(pairs);
        Collections.sort(ranked, new Comparator<Pair>() {
            @Override
            public int compare(Pair first, Pair second) {
                int comparison = -Double.compare(
                        first.apposedSurfaceArea(), second.apposedSurfaceArea());
                if (comparison == 0) {
                    comparison = -Double.compare(
                            first.exactContactArea(), second.exactContactArea());
                }
                return comparison != 0
                        ? comparison
                        : Integer.compare(first.label, second.label);
            }
        });
        return new ArrayList<Pair>(ranked.subList(0, Math.min(count, ranked.size())));
    }

    private static Comparator<Pair> valueOrder(final DistanceMode mode) {
        return new Comparator<Pair>() {
            @Override
            public int compare(Pair first, Pair second) {
                int comparison = Double.compare(first.value(mode), second.value(mode));
                return comparison != 0
                        ? comparison
                        : Integer.compare(first.label, second.label);
            }
        };
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

    // ------------------------------------------------------------ per source

    /** Everything about one source object that every pair shares. */
    private static final class SourceContext {
        private final ObjectGeometry source;
        private final ChannelGeometry sourceChannel;
        private final ChannelGeometry targetChannel;
        private final double contactDistance;
        private final int targetLabelAtSourceCentroid;
        private Map<Integer, Double> exactContactByLabel;
        private Set<Integer> overlappingLabels;

        private SourceContext(ObjectGeometry source,
                              ChannelGeometry sourceChannel,
                              ChannelGeometry targetChannel,
                              double contactDistance) {
            this.source = source;
            this.sourceChannel = sourceChannel;
            this.targetChannel = targetChannel;
            this.contactDistance = contactDistance;
            this.targetLabelAtSourceCentroid = targetChannel.labelAtCalibrated(
                    source.getCentroidX(),
                    source.getCentroidY(),
                    source.getCentroidZ());
        }

        /**
         * Exact contact area per adjacent target label. Each label's faces are
         * added in source-face order starting from zero, the same terms in the
         * same order as a per-pair sum, so each total is bit-identical. No sum
         * ever runs across labels.
         */
        private double exactContactArea(int targetLabel) {
            if (exactContactByLabel == null) {
                Map<Integer, Double> areas = new HashMap<Integer, Double>();
                for (SurfaceElement face : source.surface()) {
                    int adjacent = targetChannel.labelAt(
                            face.voxelX + face.normalX,
                            face.voxelY + face.normalY,
                            face.voxelZ + face.normalZ);
                    if (adjacent == 0) continue;
                    Double sum = areas.get(adjacent);
                    areas.put(adjacent, (sum == null ? 0.0 : sum.doubleValue()) + face.area);
                }
                exactContactByLabel = areas;
            }
            Double area = exactContactByLabel.get(targetLabel);
            return area == null ? 0.0 : area.doubleValue();
        }

        private boolean overlaps(int targetLabel) {
            if (overlappingLabels == null) {
                Set<Integer> labels = new HashSet<Integer>();
                for (int voxelIndex : source.voxelIndices()) {
                    int label = targetChannel.labelAtIndex(voxelIndex);
                    if (label != 0) labels.add(label);
                }
                overlappingLabels = labels;
            }
            return overlappingLabels.contains(targetLabel);
        }
    }

    /** One source-target pair; each quantity is computed at most once. */
    private static final class Pair {
        private final SourceContext context;
        private final ObjectGeometry target;
        private final int label;
        private final double centreToCentre;
        private double centreToEdge = Double.NaN;
        private double edgeToCentre = Double.NaN;
        private double edgeToEdge = Double.NaN;
        private double apposedSurfaceArea = Double.NaN;
        private double boxGap = Double.NaN;

        private Pair(SourceContext context, ObjectGeometry target) {
            this.context = context;
            this.target = target;
            this.label = target.getLabel();
            ObjectGeometry source = context.source;
            this.centreToCentre = distance(
                    source.getCentroidX(), source.getCentroidY(), source.getCentroidZ(),
                    target.getCentroidX(), target.getCentroidY(), target.getCentroidZ());
        }

        private double value(DistanceMode mode) {
            switch (mode) {
                case CENTRE_TO_CENTRE:
                    return centreToCentre;
                case CENTRE_TO_EDGE:
                    return centreToEdge();
                case EDGE_TO_CENTRE:
                    return edgeToCentre();
                case EDGE_TO_EDGE:
                    return edgeToEdge();
                case SURFACE_CONTACT:
                    return apposedSurfaceArea();
                default:
                    throw new IllegalArgumentException("Unsupported distance mode: " + mode);
            }
        }

        private double lowerBound(DistanceMode mode) {
            ObjectGeometry source = context.source;
            switch (mode) {
                case CENTRE_TO_CENTRE:
                    return centreToCentre;
                case CENTRE_TO_EDGE:
                    if (centroidInsideTarget() || !target.hasSurface()) return 0.0;
                    return pointToBox(source.getCentroidX(), source.getCentroidY(),
                            source.getCentroidZ(), target);
                case EDGE_TO_CENTRE:
                    if (targetCentroidInsideSource() || !source.hasSurface()) return 0.0;
                    return pointToBox(target.getCentroidX(), target.getCentroidY(),
                            target.getCentroidZ(), source);
                case EDGE_TO_EDGE:
                    if (!source.hasSurface() || !target.hasSurface()
                            || context.overlaps(label)) {
                        return 0.0;
                    }
                    return boxGap();
                default:
                    throw new IllegalArgumentException("Unsupported distance mode: " + mode);
            }
        }

        private boolean centroidInsideTarget() {
            return context.targetLabelAtSourceCentroid == label;
        }

        private boolean targetCentroidInsideSource() {
            return context.sourceChannel.labelAtCalibrated(
                    target.getCentroidX(),
                    target.getCentroidY(),
                    target.getCentroidZ()) == context.source.getLabel();
        }

        private double boxGap() {
            if (Double.isNaN(boxGap)) {
                ObjectGeometry source = context.source;
                boxGap = boxDistance(
                        source.boxMinX(), source.boxMaxX(),
                        source.boxMinY(), source.boxMaxY(),
                        source.boxMinZ(), source.boxMaxZ(),
                        target.boxMinX(), target.boxMaxX(),
                        target.boxMinY(), target.boxMaxY(),
                        target.boxMinZ(), target.boxMaxZ());
            }
            return boxGap;
        }

        private double centreToEdge() {
            if (Double.isNaN(centreToEdge)) {
                ObjectGeometry source = context.source;
                centreToEdge = centroidInsideTarget()
                        ? 0.0
                        : pointToSurface(source.getCentroidX(), source.getCentroidY(),
                                source.getCentroidZ(), target.surface());
            }
            return centreToEdge;
        }

        private double edgeToCentre() {
            if (Double.isNaN(edgeToCentre)) {
                edgeToCentre = targetCentroidInsideSource()
                        ? 0.0
                        : pointToSurface(target.getCentroidX(), target.getCentroidY(),
                                target.getCentroidZ(), context.source.surface());
            }
            return edgeToCentre;
        }

        private double edgeToEdge() {
            if (Double.isNaN(edgeToEdge)) {
                double value = context.overlaps(label)
                        ? 0.0
                        : surfaceToSurface(context.source.surface(), target);
                if (!Double.isFinite(value)) value = centreToCentre;
                edgeToEdge = value;
            }
            return edgeToEdge;
        }

        private double exactContactArea() {
            return context.exactContactArea(label);
        }

        private double apposedSurfaceArea() {
            if (Double.isNaN(apposedSurfaceArea)) {
                double contact = context.contactDistance;
                double area = 0.0;
                if (context.source.hasSurface() && target.hasSurface()
                        && boxGap() <= contact) {
                    for (SurfaceElement sourceFace : context.source.surface()) {
                        AnalysisCancelledException.check();
                        if (withinContact(sourceFace, target, contact)) {
                            area += sourceFace.area;
                        }
                    }
                }
                apposedSurfaceArea = area;
            }
            return apposedSurfaceArea;
        }
    }

    // ------------------------------------------------------------- geometry

    /**
     * Minimum face-to-face distance. A source face whose distance to the
     * target's box is already no less than the running minimum cannot lower
     * it, and a minimum of zero cannot be lowered at all.
     */
    private static double surfaceToSurface(List<SurfaceElement> sourceSurface,
                                           ObjectGeometry target) {
        double minimum = Double.POSITIVE_INFINITY;
        List<SurfaceElement> targetSurface = target.surface();
        for (SurfaceElement sourceFace : sourceSurface) {
            AnalysisCancelledException.check();
            if (faceToBox(sourceFace, target) >= minimum) continue;
            for (SurfaceElement targetFace : targetSurface) {
                double value = surfaceDistance(sourceFace, targetFace);
                if (value < minimum) {
                    minimum = value;
                    if (minimum == 0.0) return minimum;
                }
            }
        }
        return minimum;
    }

    /**
     * Whether an opposite-facing target face lies within the contact
     * distance; equivalent to the minimum over such faces being within it.
     */
    private static boolean withinContact(SurfaceElement source,
                                         ObjectGeometry target,
                                         double contactDistance) {
        if (faceToBox(source, target) > contactDistance) return false;
        for (SurfaceElement face : target.surface()) {
            if (source.normalX + face.normalX != 0
                    || source.normalY + face.normalY != 0
                    || source.normalZ + face.normalZ != 0) {
                continue;
            }
            if (surfaceDistance(source, face) <= contactDistance) return true;
        }
        return false;
    }

    private static double pointToSurface(double x,
                                         double y,
                                         double z,
                                         List<SurfaceElement> surface) {
        double minimum = Double.POSITIVE_INFINITY;
        for (SurfaceElement face : surface) {
            double value = pointToSurfaceDistance(x, y, z, face);
            if (value < minimum) {
                minimum = value;
                if (minimum == 0.0) return minimum;
            }
        }
        return minimum;
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

    private static double pointToBox(double x, double y, double z,
                                     ObjectGeometry object) {
        double dx = intervalDistance(x, object.boxMinX(), object.boxMaxX());
        double dy = intervalDistance(y, object.boxMinY(), object.boxMaxY());
        double dz = intervalDistance(z, object.boxMinZ(), object.boxMaxZ());
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

    private static double faceToBox(SurfaceElement face, ObjectGeometry box) {
        return boxDistance(
                face.minX, face.maxX, face.minY, face.maxY, face.minZ, face.maxZ,
                box.boxMinX(), box.boxMaxX(),
                box.boxMinY(), box.boxMaxY(),
                box.boxMinZ(), box.boxMaxZ());
    }

    private static double boxDistance(double firstMinX, double firstMaxX,
                                      double firstMinY, double firstMaxY,
                                      double firstMinZ, double firstMaxZ,
                                      double secondMinX, double secondMaxX,
                                      double secondMinY, double secondMaxY,
                                      double secondMinZ, double secondMaxZ) {
        double dx = intervalDistance(firstMinX, firstMaxX, secondMinX, secondMaxX);
        double dy = intervalDistance(firstMinY, firstMaxY, secondMinY, secondMaxY);
        double dz = intervalDistance(firstMinZ, firstMaxZ, secondMinZ, secondMaxZ);
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

    /** Thread-safe count of finished source objects. */
    private static final class Progress {
        private final ProgressListener listener;
        private final int total;
        private final String message;
        private int finished;

        private Progress(ProgressListener listener, int total, String message) {
            this.listener = listener;
            this.total = total;
            this.message = message;
        }

        /** Counts and reports under one lock, so fractions never go backwards. */
        private synchronized void finishedOne() {
            finished++;
            if (listener != null) {
                listener.onProgress(finished / (double) total, message);
            }
        }
    }
}
