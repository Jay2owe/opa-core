/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package sc.fiji.opa.core.spatial;

import org.junit.Assume;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * Validation stage V3, export half — writes fixed point patterns and this
 * engine's curves for them, so an independent implementation can be run over
 * exactly the same inputs.
 *
 * <p>The companion R script {@code compare-spatstat.R} reads what this writes,
 * recomputes every curve with spatstat, and reports the largest disagreement
 * per function and correction. Nothing here talks to R; the two halves meet
 * only through files, so either can be rerun alone.</p>
 *
 * <p>Correction names do not line up across the two implementations and the
 * mapping is not guesswork, so it is recorded here and used by the R side:</p>
 *
 * <pre>
 *   OPA EdgeCorrection.TRANSLATION  -&gt; spatstat correction="translate"
 *   OPA EdgeCorrection.BORDER       -&gt; spatstat correction="border"
 *   OPA EdgeCorrection.NONE         -&gt; spatstat correction="none"
 *   OPA computeG (no correction)    -&gt; spatstat Gest column "raw"
 *   OPA computeCrossG               -&gt; spatstat Gcross column "raw"
 * </pre>
 *
 * <p>Pair correlation is deliberately excluded from the equality check.
 * {@code SpatialStatistics.computePairCorrelation} is ring-normalised from
 * increments of K; spatstat's {@code pcf} is kernel-smoothed. They are
 * different estimators of the same quantity and will not agree pointwise, so
 * comparing them directly would be a false test. The R script instead applies
 * OPA's own ring normalisation to spatstat's K and compares that, which asks
 * the question actually worth asking: do the two K estimates agree?</p>
 *
 * <p>Skipped unless {@code -Dopa.spatstat=true}. Output directory defaults to
 * {@code validation/v3-spatstat} and is set with {@code -Dopa.spatstat.out}.</p>
 */
public class SpatstatExport {

    private static final int RADIUS_COUNT = 20;

    /** Largest radius as a fraction of the shorter window side. */
    private static final double MAX_RADIUS_FRACTION = 0.2;

    /** One exported pattern: univariate when {@code target} is null. */
    private static final class Case {
        final String name;
        final double[][] source;
        final double[][] target;
        final RectangularWindow window;

        Case(String name,
             double[][] source,
             double[][] target,
             RectangularWindow window) {
            this.name = name;
            this.source = source;
            this.target = target;
            this.window = window;
        }

        boolean isBivariate() {
            return target != null;
        }
    }

    @Test
    public void exportPatternsAndCurvesForSpatstat() throws IOException {
        Assume.assumeTrue(
                "Set -Dopa.spatstat=true to export the V3 comparison inputs.",
                Boolean.getBoolean("opa.spatstat"));
        export(Paths.get(System.getProperty(
                "opa.spatstat.out", "validation/v3-spatstat")));
    }

    public static void main(String[] args) throws IOException {
        export(Paths.get(args.length > 0 ? args[0] : "validation/v3-spatstat"));
    }

    private static void export(Path out) throws IOException {
        Path patterns = out.resolve("patterns");
        Path curves = out.resolve("opa");
        Files.createDirectories(patterns);
        Files.createDirectories(curves);

        List<Case> cases = cases();
        StringBuilder manifest = new StringBuilder(
                "name,kind,n_source,n_target,xmin,ymin,xmax,ymax\n");
        StringBuilder index = new StringBuilder("file,name,function,correction\n");

        for (Case testCase : cases) {
            RectangularWindow w = testCase.window;
            manifest.append(String.format(
                    Locale.ROOT,
                    "%s,%s,%d,%d,%.10f,%.10f,%.10f,%.10f%n",
                    testCase.name,
                    testCase.isBivariate() ? "bivariate" : "univariate",
                    testCase.source.length,
                    testCase.isBivariate() ? testCase.target.length : 0,
                    w.getMinX(), w.getMinY(), w.getMaxX(), w.getMaxY()));

            writePoints(patterns.resolve(testCase.name + "_source.csv"),
                    testCase.source);
            if (testCase.isBivariate()) {
                writePoints(patterns.resolve(testCase.name + "_target.csv"),
                        testCase.target);
            }

            double[] radii = radii(w);
            writeRadii(patterns.resolve(testCase.name + "_radii.csv"), radii);

            if (testCase.isBivariate()) {
                exportBivariate(curves, index, testCase, radii);
            } else {
                exportUnivariate(curves, index, testCase, radii);
            }
        }

        Files.write(out.resolve("manifest.csv"),
                manifest.toString().getBytes(StandardCharsets.UTF_8));
        Files.write(out.resolve("opa-curves.csv"),
                index.toString().getBytes(StandardCharsets.UTF_8));
        System.out.println("Exported " + cases.size()
                + " patterns to " + out.toAbsolutePath());
    }

    private static void exportUnivariate(Path curves,
                                         StringBuilder index,
                                         Case testCase,
                                         double[] radii) throws IOException {
        for (EdgeCorrection correction : new EdgeCorrection[]{
                EdgeCorrection.TRANSLATION,
                EdgeCorrection.BORDER,
                EdgeCorrection.NONE}) {
            double[] k = SpatialStatistics.computeK(
                    testCase.source, testCase.window, radii, correction);
            emit(curves, index, testCase.name, "K", correction.name(), radii, k);
            emit(curves, index, testCase.name, "L", correction.name(),
                    radii, SpatialStatistics.computeL(k));
            emit(curves, index, testCase.name, "LmR", correction.name(),
                    radii, SpatialStatistics.computeLMinusR(k, radii));
        }
        // G carries no correction in this engine at all.
        emit(curves, index, testCase.name, "G", "RAW", radii,
                SpatialStatistics.computeG(testCase.source, radii));
        // Exported for the record, compared only against a like-for-like
        // ring-normalised curve derived from spatstat's K.
        emit(curves, index, testCase.name, "PCF", "TRANSLATION", radii,
                SpatialStatistics.computePairCorrelation(
                        testCase.source, testCase.window, radii,
                        EdgeCorrection.TRANSLATION));
    }

    private static void exportBivariate(Path curves,
                                        StringBuilder index,
                                        Case testCase,
                                        double[] radii) throws IOException {
        for (EdgeCorrection correction : new EdgeCorrection[]{
                EdgeCorrection.TRANSLATION,
                EdgeCorrection.BORDER,
                EdgeCorrection.NONE}) {
            double[] k = SpatialStatistics.computeCrossK(
                    testCase.source, testCase.target, testCase.window,
                    radii, correction);
            emit(curves, index, testCase.name, "crossK", correction.name(),
                    radii, k);
            emit(curves, index, testCase.name, "crossL", correction.name(),
                    radii, SpatialStatistics.computeL(k));
        }
        emit(curves, index, testCase.name, "crossG", "RAW", radii,
                SpatialStatistics.computeCrossG(
                        testCase.source, testCase.target, radii));
    }

    private static void emit(Path curves,
                             StringBuilder index,
                             String name,
                             String function,
                             String correction,
                             double[] radii,
                             double[] values) throws IOException {
        String file = name + "__" + function + "__" + correction + ".csv";
        StringBuilder text = new StringBuilder("r,value\n");
        for (int i = 0; i < radii.length; i++) {
            text.append(String.format(
                    Locale.ROOT, "%.17g,%.17g%n", radii[i], values[i]));
        }
        Files.write(curves.resolve(file),
                text.toString().getBytes(StandardCharsets.UTF_8));
        index.append(file).append(',').append(name).append(',')
                .append(function).append(',').append(correction).append('\n');
    }

    private static List<Case> cases() {
        List<Case> cases = new ArrayList<>();
        RectangularWindow square = new RectangularWindow(0, 0, 1000, 1000);
        RectangularWindow wide = new RectangularWindow(0, 0, 2000, 500);
        RectangularWindow shifted = new RectangularWindow(-250, 137.5, 750, 1137.5);
        RectangularWindow small = new RectangularWindow(0, 0, 100, 100);

        cases.add(new Case("csr_n50_square", csr(50, square, 11L), null, square));
        cases.add(new Case("csr_n200_square", csr(200, square, 12L), null, square));
        cases.add(new Case("csr_n500_square", csr(500, square, 13L), null, square));
        cases.add(new Case("csr_n200_wide", csr(200, wide, 14L), null, wide));
        // A window whose origin is neither zero nor axis-aligned with the data
        // catches an implementation that quietly assumes a corner at (0,0).
        cases.add(new Case("csr_n200_shifted", csr(200, shifted, 15L), null, shifted));
        cases.add(new Case("csr_n200_small", csr(200, small, 16L), null, small));
        cases.add(new Case("clustered_n240_square",
                clustered(20, 12, 40.0, square, 17L), null, square));
        cases.add(new Case("regular_n196_square",
                jitteredGrid(14, square, 8.0, 18L), null, square));

        cases.add(new Case("cross_csr_csr",
                csr(150, square, 21L), csr(150, square, 22L), square));
        cases.add(new Case("cross_csr_clustered",
                csr(150, square, 23L), clustered(15, 10, 35.0, square, 24L), square));
        cases.add(new Case("cross_uneven_counts",
                csr(40, square, 25L), csr(400, square, 26L), square));
        cases.add(new Case("cross_wide_window",
                csr(150, wide, 27L), csr(150, wide, 28L), wide));
        return cases;
    }

    private static double[][] csr(int count, RectangularWindow w, long seed) {
        Random random = new Random(seed);
        double[][] points = new double[count][2];
        for (int i = 0; i < count; i++) {
            points[i][0] = w.getMinX() + random.nextDouble() * w.width();
            points[i][1] = w.getMinY() + random.nextDouble() * w.height();
        }
        return points;
    }

    /** Parent-offspring cluster process, offspring kept inside the window. */
    private static double[][] clustered(int parents,
                                        int perParent,
                                        double spread,
                                        RectangularWindow w,
                                        long seed) {
        Random random = new Random(seed);
        List<double[]> points = new ArrayList<>();
        for (int p = 0; p < parents; p++) {
            double px = w.getMinX() + random.nextDouble() * w.width();
            double py = w.getMinY() + random.nextDouble() * w.height();
            for (int c = 0; c < perParent; c++) {
                double x = px + random.nextGaussian() * spread;
                double y = py + random.nextGaussian() * spread;
                if (w.contains(x, y)) points.add(new double[]{x, y});
            }
        }
        return points.toArray(new double[0][]);
    }

    /** Grid with bounded jitter: regular at short range, not degenerate. */
    private static double[][] jitteredGrid(int side,
                                           RectangularWindow w,
                                           double jitter,
                                           long seed) {
        Random random = new Random(seed);
        double stepX = w.width() / (side + 1);
        double stepY = w.height() / (side + 1);
        double[][] points = new double[side * side][2];
        int index = 0;
        for (int i = 1; i <= side; i++) {
            for (int j = 1; j <= side; j++) {
                double x = w.getMinX() + i * stepX
                        + (random.nextDouble() - 0.5) * 2.0 * jitter;
                double y = w.getMinY() + j * stepY
                        + (random.nextDouble() - 0.5) * 2.0 * jitter;
                points[index][0] = Math.min(Math.max(x, w.getMinX()), w.getMaxX());
                points[index][1] = Math.min(Math.max(y, w.getMinY()), w.getMaxY());
                index++;
            }
        }
        return points;
    }

    private static double[] radii(RectangularWindow w) {
        double max = Math.min(w.width(), w.height()) * MAX_RADIUS_FRACTION;
        double step = max / RADIUS_COUNT;
        double[] radii = new double[RADIUS_COUNT];
        for (int i = 0; i < RADIUS_COUNT; i++) radii[i] = step * (i + 1);
        return radii;
    }

    private static void writePoints(Path file, double[][] points)
            throws IOException {
        StringBuilder text = new StringBuilder("x,y\n");
        for (double[] point : points) {
            text.append(String.format(
                    Locale.ROOT, "%.17g,%.17g%n", point[0], point[1]));
        }
        Files.write(file, text.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static void writeRadii(Path file, double[] radii)
            throws IOException {
        StringBuilder text = new StringBuilder("r\n");
        for (double r : radii) {
            text.append(String.format(Locale.ROOT, "%.17g%n", r));
        }
        Files.write(file, text.toString().getBytes(StandardCharsets.UTF_8));
    }
}
