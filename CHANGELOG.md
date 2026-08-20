# Changelog

## [0.3.0] - 2026-08-20

### Fixed

- **The pointwise Monte Carlo envelope was anti-conservative.** It was built
  from the linearly interpolated 2.5th and 97.5th percentiles of the simulated
  values, then compared against an observed curve that was not among them. That
  band was labelled 95% but escaped 6.9% of the time at 99 simulations and 9.6%
  at 39, because an interpolated percentile of S values does not land on the
  rank boundary a 5% escape rate requires.

  It is now a rank envelope: the k-th smallest and k-th largest simulated
  value, whose pointwise escape probability is exactly `2k / (S + 1)` when the
  observed curve is exchangeable with the simulations.

  Measured, not reasoned about. `EnvelopeCalibrationStudy` in the test sources
  runs 1,000 complete-spatial-randomness patterns through the analyzer and
  reports per-radius escape rates, binned uniformity of the global p-value and
  empirical Type I error. Before the fix, pooled per-radius escape was 0.061 to
  0.072 against a nominal 0.050 across K/translation, K/border, L/translation,
  G/border and pair-correlation/translation. After it, at 119 simulations, the
  four non-saturating cases sit at 0.045 to 0.054.

  The global maximum-deviation p-value was already correctly calibrated —
  empirical Type I error 0.038 to 0.048 across five function/correction pairs —
  and is unchanged.

### Added

- **`MonteCarloResult.getEnvelopeLevel()`**, `getEnvelopeRank()` and
  `getEnvelopeConfidencePercent()`. The level a rank envelope delivers is
  `2k / (S + 1)`, which equals the requested 5% only when `S + 1` is a multiple
  of 40 — that is, at 39, 79, 119, 159 or 199 simulations. Callers must display
  the delivered level rather than assuming 95%, so it is now part of the result
  rather than something a caller has to know.

- `MonteCarloAnalyzer.NOMINAL_ENVELOPE_ALPHA`, the level the rank aims for.

- **`MonteCarloResult.getSaturationRadius()`**, `getSaturatedRadiusCount()` and
  `hasSaturatedRadii()`. Nearest-neighbour G is a cumulative distribution, so it
  climbs to 1 and stops. Past that point every simulated curve takes the same
  value, the pointwise band collapses to a point and the radius can neither be
  escaped nor contribute to the global test. The results are not wrong, they are
  empty, and a caller now has the means to say so instead of presenting a flat
  envelope as a finding.

  Under complete spatial randomness G(r) = 1 - exp(-lambda*pi*r^2), so the
  radius at which it reaches 0.99 is `sqrt(ln(100) / (lambda * pi))`. Only G and
  cross-G report one; K and its derived curves grow without bound and return
  NaN.

  Radii past saturation are **warned about, not dropped**. Every requested
  radius still comes back, because silently changing what someone asked for is
  worse than telling them it will not help.

### Changed

- The envelope rank rounds **down**, so where the requested level cannot be
  expressed the envelope errs wide rather than narrow. At 99 simulations it
  delivers 4%, not 6%.

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
