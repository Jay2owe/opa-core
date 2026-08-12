# Changelog

## [0.2.0] - 2026-08-11

### Added

- **`PatternFunction.CROSS_PAIR_CORRELATION`** — cross pair correlation
  g₁₂(r), the bivariate form of g(r). Completes the spatial family: all four
  cross methods now answer an A-versus-B question.

  ```java
  double[] g = SpatialStatistics.computeCrossPairCorrelation(
          source, target, window, radii, correction);

  MonteCarloResult result = MonteCarloAnalyzer.analyzeBivariate(
          PatternFunction.CROSS_PAIR_CORRELATION, source, target, window,
          radii, EdgeCorrection.TRANSLATION, simulations, seed);
  ```

  Its CSR expectation is 1 at every radius, so envelope, global
  maximum-deviation p-value and status come out of the same machinery as
  cross-K, cross-L and cross-G. It rejects `EdgeCorrection.BORDER` for the same
  reason and in the same words as the univariate form.

  Appended to the enum, not inserted: ordinal order decides EnumSet iteration
  order, and therefore result order and the rendered provenance string.

### Changed

- `MonteCarloAnalyzer.evaluateUnivariateSamples` and
  `evaluateBivariateSamples` are package-private rather than private, so the
  simulation matrix can be asserted directly. See below.

### Testing

- **The parallelism test had a blind spot and now does not.** Comparing two
  finished `MonteCarloResult`s across worker counts cannot detect a scrambled
  indexed merge: every consumer of the simulation matrix is a sort (the
  percentile envelope), a count (the global rank and the p-value), or a sum
  over the same multiset (`exchangeableScales`). Shuffling which row holds
  which permutation leaves the aggregate identical, or different only in the
  last bits of a floating-point sum.

  `MonteCarloParallelismTest` now asserts the matrix itself — identical element
  by element at 1, 2, 3 and 8 workers, and row *i* equal to the curve of the
  *i*-th pattern drawn from the seeded stream, which is what makes the seed
  mean anything. A negative control proves the assertion rejects a
  one-row rotation.

- 60 tests, up from 43. `CrossPairCorrelationTest` adds 12, including a
  co-located pair sitting at the p-value floor, an independent pair the global
  test does not reject, and the border rejection asserted against the
  univariate message.

- **A pointwise envelope is not a simultaneous band**, and that is now a test
  rather than a comment. A pointwise 2.5–97.5 band at five radii is not a 95%
  band over a curve: an independent pair is expected outside it at some radius
  roughly one run in five. This file's own first draft asserted pointwise
  containment for an independent pattern and failed on correct behaviour. The
  global maximum-deviation p-value is the test; the envelope is a picture.

## [0.1.0] - 2026-08-11

### Added

- Initial extraction from `02 - Object Proximity Analysis`: label geometry,
  the distance measures, and the 2D point-pattern statistics with their seeded
  parallel Monte Carlo null.
- `net.imagej:ij` is the whole dependency list. `HeadlessContractTest` asserts
  on the compiled classes that no dialog, plot, table, Swing or AWT window
  reference survives, and that `ij.IJ` is reached only for `escapePressed()`.
- Gated by 546 golden dumps captured from the pre-extraction build and
  immutable since. Zero moved.
