# opa-core

[![DOI](https://zenodo.org/badge/DOI/10.5281/zenodo.21933299.svg)](https://doi.org/10.5281/zenodo.21933299)

Object Proximity Analysis's engine, as an embeddable module.

**Status (2026-09-30): 0.4.0, released and shipping inside the plugin.**
Exact distances without the all-pairs scan and K at every radius in one pass:
4-66x faster on the benchmark scenes, every output identical bit for bit to
0.3.0. Object Proximity Analysis runs on it with **672 golden dumps green,
bit-for-bit.**

**Pattern:** `../PLUGIN_CORE_PATTERN.md`
**Depends on:** `net.imagej:ij` only. **Not** `oc3d-core` — this engine's input
is a finished label image, and ROI/label ingest stays with the plugin. See
`DECISIONS.md` § 3.
**Never shipped as a jar.**

| Class | Role |
|---|---|
| `spatial.SpatialStatistics` | Ripley K, L, L(r)-r, nearest-neighbour G, pair correlation, cross-K, cross-L, cross-G, cross pair correlation |
| `spatial.MonteCarloAnalyzer` | seeded complete-spatial-randomness envelopes and the global maximum-deviation p-value |
| `spatial.MonteCarloResult` | observed / expected / envelope / p-value model — **no ImageJ tables** |
| `spatial.RectangularWindow` `EdgeCorrection` `PatternFunction` `PatternStatus` | the point-pattern vocabulary |
| `geometry.LabelGeometryExtractor` | label stack to calibrated centroids and exposed voxel faces |
| `geometry.ProximityEngine` | exact centre/edge/surface distances, ranked k-nearest partners |
| `geometry.DirectionResult` `ObjectMeasurement` `NeighborMeasurement` | per-direction result model |
| `CalibrationInfo` `DistanceMode` `ProgressListener` `EngineLimits` `AnalysisCancelledException` | shared value types |

Build and test:

```
mvn package     # BSD-3 only, one dependency, no Swing on any path
mvn install     # needed before a consumer can build
```

## What this is

The distance engine and the 2D point-pattern engine, with the dialog, the entry
class and every `ResultsTable` stripped out, so another plugin can compile them
in without the user installing Object Proximity Analysis.

**Two measures, deliberately kept in one module.** They are not two engines
sharing a chassis: `ChannelGeometry` is the single ingest both consume,
`centroidPoints2D()` is what the point-pattern side runs on, and the calibrated
coordinate convention `(pixel - origin) x voxel size` has to be the same for
both or a window and a distance stop describing the same space. Splitting them
would mean duplicating that convention, which is exactly the failure the pattern
exists to prevent.

## Consuming it

```xml
<dependency>
  <groupId>io.github.jay2owe</groupId>
  <artifactId>opa-core</artifactId>
  <version>0.4.0</version>
</dependency>
```

```xml
<relocation>
  <pattern>sc.fiji.opa.core</pattern>
  <shadedPattern>ocs.internal.opa</shadedPattern>
</relocation>
```

Relocate into **your own** namespace. Two jars must never carry the same
fully-qualified class name — under Fiji's flat classloader that is a
silent-wrong-answer bug, not a crash, and Object Proximity Analysis already
ships this engine as `opa.internal.engine`.

Never relocate a consumer's own documented public API alongside it.

### Consumers

| Plugin | Relocated to |
|---|---|
| `02 - Object Proximity Analysis` | `opa.internal.engine` |
| `06 - Colocalization Suite` | `ocs.internal.opa` (planned, stage 7) |

`minimizeJar` is safe: there is no reflection anywhere in this module, verified
before shading was enabled. It does drop `EngineLimits` and `SpatialCurve` from
a consumer jar — the first because javac inlines its compile-time constants at
every use, the second because nothing references it at all.

## The point-pattern surface

All four cross methods answer an A-versus-B question, and all four run through
one entry point — pass the function, get observed curve, CSR expectation,
envelope and global p-value back:

```java
MonteCarloResult result = MonteCarloAnalyzer.analyzeBivariate(
        PatternFunction.CROSS_K,   // or CROSS_L, CROSS_G, CROSS_PAIR_CORRELATION
        source, target, window, radii,
        EdgeCorrection.TRANSLATION, simulations, seed, progressOrNull);
```

Raw curves, without a null:

```java
double[] k  = SpatialStatistics.computeCrossK(source, target, window, radii, correction);
double[] l  = SpatialStatistics.computeL(k);          // cross-L is L of cross-K
double[] g  = SpatialStatistics.computeCrossG(source, target, radii);
double[] gc = SpatialStatistics.computeCrossPairCorrelation(
        source, target, window, radii, correction);
```

`PAIR_CORRELATION` is **univariate** — use `analyzeUnivariate` for it, and
`CROSS_PAIR_CORRELATION` for the A-versus-B form.

Points are `double[n][2]` in **calibrated** coordinates and must lie inside the
window; a point outside is rejected, not clipped. Radii must be finite,
non-negative and strictly increasing.

The seed is an explicit `long` argument, never drawn from a global source, and
it is carried on the result. Simulated patterns are generated on the coordinator
from the one seeded stream and indexed back into place, so worker count cannot
change the answer — `MonteCarloParallelismTest` pins that. Set
`-Dopa.parallelism=1` for the serial reference path, or a positive integer to
override the eight-worker default.

Both pair-correlation forms refuse border correction: the radius-dependent risk
set can make K increments negative. Use translation, or none. The cross form is
the annulus derivative of cross-K, deliberately the same estimator family as the
univariate form so the two are comparable — see `DECISIONS.md` § 11.

**A pointwise envelope is not a simultaneous band.** At five radii an
independent pair is expected outside the 2.5-97.5 band at some radius roughly
one run in five. `getGlobalPValue()` is the test; the envelope is a picture.

## The distance surface

```java
ChannelGeometry a = LabelGeometryExtractor.extract(labelImage, "Cells");
DirectionResult d = ProximityEngine.analyze(
        a, b, EnumSet.allOf(DistanceMode.class), neighbourCount, contactDistance);
```

A self-comparison is detected by **reference identity** — pass the same
`ChannelGeometry` instance as both arguments to exclude each object from its own
neighbour list. Two separately extracted instances of the same image are treated
as different channels and every object matches itself at distance zero.

Only pairs that can still enter the k nearest are measured face by face:
each object's surface bounding box gives an exact lower bound, so the search
stops as soon as no remaining pair can compete. Cost now grows roughly with the
object count times the surface size of the few nearest partners, plus a cheap
bound per pair; 400 3D objects of about 500 surface faces each take seconds.

## No dialog, no Swing, no `IJ.error`

Must run headless. It throws; the consumer presents.
`HeadlessContractTest` asserts this on the **compiled** classes rather than the
sources, scanning every constant pool for `GenericDialog`, `Plot`,
`ResultsTable`, `WindowManager`, `javax.swing`, AWT windows, and for
`System.exit` or the presentation members of `ij.IJ`.

`ij.IJ` is reached for exactly one thing: `escapePressed()`, the cancellation
poll, which is false headless. That is asserted too.

## Scope

### In

The measurement and the statistics, their result models, and the value types
they need.

### Out — stays with the plugin

Entry class, `plugins.config`, `GenericDialog`, macro option parsing,
`ResultsTable` construction (`ResultTables`), CSV writing (`OPAOutput`), plots
(`OPAPlots`), batch discovery and aggregation (`OPABatch*`), and the documented
public API (`OPA`, `OPAParameters`, `OPAResult`, `OPALabelImages`,
`OPAProgressListener`, `PatternResult`).

### Out — open boundary question

`LabelUtils` — ROI-set to label-image conversion. It is chassis, not engine, and
`oc3d-core` already owns that job in `RoiLabelImages`. It stayed in the plugin
rather than being copied here, because copying it would create the third
implementation of one idea in this family. Reconciling it against `oc3d-core` is
its own change with its own gate. See `DECISIONS.md` § 4.

## Ship gate

`../oc3d-core/EQUIVALENCE_HARNESS.md`. Every field is **Tier 1, bit-identical**;
this migration declares no Tier 2 and no Tier 3, because moving code between
compilation units changes no arithmetic, no traversal order and no tie-break, so
there is no field for which a tolerance could be justified.

Gated by 546 golden dumps captured from the pre-extraction build and immutable
since — 32 corpus cases x 17 configurations, plus the engine surface called
directly and the complete rejection vocabulary asserted by message text.
Goldens live in `../../02 - Object Proximity Analysis/golden/pre-extraction/`.

## Citation

> Malcolm, J. (2026). *opa-core: Embeddable object proximity and spatial
> point-pattern engine* (Version 0.4.0) [Computer software]. Zenodo.
> https://doi.org/10.5281/zenodo.21933300

## Licence

BSD 3-Clause — see `LICENSE`, with attribution in `NOTICE`. Links
`net.imagej:ij` only.
