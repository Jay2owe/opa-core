# Validation stage V3, comparison half.
#
# Reads the patterns and curves written by SpatstatExport, recomputes every
# curve with spatstat, and reports the largest disagreement per function and
# correction.
#
# Usage:
#   Rscript compare-spatstat.R <export-directory>
#
# The export directory is whatever -Dopa.spatstat.out pointed at, by default
# opa-core/validation/v3-spatstat. Results are written to v3-report.txt in
# that same directory and echoed to stdout.
#
# Correction mapping, fixed deliberately rather than guessed (see the Javadoc
# on SpatstatExport):
#
#   OPA TRANSLATION -> spatstat Kest column "trans"
#   OPA BORDER      -> spatstat Kest column "border"
#   OPA NONE        -> spatstat Kest column "un"
#   OPA G           -> spatstat Gest column "raw"   (no edge correction)
#   OPA crossG      -> spatstat Gcross column "raw"
#
# Pair correlation is NOT compared against spatstat's pcf. OPA derives g(r) by
# ring-normalising increments of K; spatstat's pcf is kernel-smoothed. They are
# different estimators and disagreeing pointwise would mean nothing. Instead
# OPA's own ring normalisation is applied to spatstat's K and compared against
# OPA's g, which isolates whether the two K estimates agree.

suppressPackageStartupMessages({
  ok <- require(spatstat.explore, quietly = TRUE) &&
        require(spatstat.geom, quietly = TRUE)
})
if (!ok) {
  stop("spatstat is not installed. Run: install.packages('spatstat')")
}

args <- commandArgs(trailingOnly = TRUE)
root <- if (length(args) > 0) args[1] else "validation/v3-spatstat"
if (!dir.exists(root)) stop("export directory not found: ", root)

manifest <- read.csv(file.path(root, "manifest.csv"), stringsAsFactors = FALSE)

# Tolerance for curves that should agree to floating-point noise.
TOL <- 1e-6

report <- c()
say <- function(...) {
  line <- paste0(...)
  report <<- c(report, line)
  cat(line, "\n", sep = "")
}

read_pts <- function(name, role) {
  read.csv(file.path(root, "patterns", paste0(name, "_", role, ".csv")))
}
read_curve <- function(name, fn, corr) {
  f <- file.path(root, "opa", paste0(name, "__", fn, "__", corr, ".csv"))
  if (!file.exists(f)) return(NULL)
  read.csv(f)
}

# OPA's ring normalisation, replicated exactly.
ring_pcf <- function(k, r) {
  prev_k <- 0; prev_r <- 0; out <- numeric(length(r))
  for (i in seq_along(r)) {
    area <- pi * (r[i]^2 - prev_r^2)
    out[i] <- if (area <= 0 || is.na(k[i]) || is.na(prev_k)) NA_real_
              else max(0, k[i] - prev_k) / area
    prev_k <- if (is.na(prev_k) || is.na(k[i])) NA_real_ else max(prev_k, k[i])
    prev_r <- r[i]
  }
  out
}

# Largest absolute and relative gap between two curves, ignoring positions
# where either side is undefined.
gap <- function(a, b) {
  keep <- is.finite(a) & is.finite(b)
  if (!any(keep)) return(c(abs = NA_real_, rel = NA_real_, n = 0))
  d <- abs(a[keep] - b[keep])
  scale <- pmax(abs(a[keep]), abs(b[keep]), 1e-300)
  c(abs = max(d), rel = max(d / scale), n = sum(keep))
}

results <- list()
record <- function(fn, corr, g) {
  key <- paste(fn, corr)
  prev <- results[[key]]
  if (is.null(prev)) results[[key]] <<- g
  else results[[key]] <<- c(abs = max(prev["abs"], g["abs"], na.rm = TRUE),
                            rel = max(prev["rel"], g["rel"], na.rm = TRUE),
                            n = prev["n"] + g["n"])
}

say("V3 - agreement with spatstat")
say("===========================")
say("spatstat.explore ", as.character(packageVersion("spatstat.explore")))
say("cases: ", nrow(manifest))
say("")

for (row in seq_len(nrow(manifest))) {
  m <- manifest[row, ]
  win <- owin(c(m$xmin, m$xmax), c(m$ymin, m$ymax))
  rr <- read.csv(file.path(root, "patterns", paste0(m$name, "_radii.csv")))$r
  # spatstat requires the radius vector to start at zero.
  r0 <- c(0, rr)
  keep <- seq_along(rr) + 1

  src <- read_pts(m$name, "source")
  X <- ppp(src$x, src$y, window = win, checkdup = FALSE)

  if (m$kind == "univariate") {
    K <- Kest(X, r = r0, correction = c("translate", "border", "none"))
    cols <- list(TRANSLATION = "trans", BORDER = "border", NONE = "un")
    for (corr in names(cols)) {
      col <- cols[[corr]]
      if (!col %in% names(K)) next
      ref <- as.numeric(K[[col]])[keep]
      o <- read_curve(m$name, "K", corr)
      if (!is.null(o)) record("K", corr, gap(o$value, ref))
      o <- read_curve(m$name, "L", corr)
      if (!is.null(o)) record("L", corr, gap(o$value, sqrt(ref / pi)))
      o <- read_curve(m$name, "LmR", corr)
      if (!is.null(o)) record("LmR", corr, gap(o$value, sqrt(ref / pi) - rr))
      if (corr == "TRANSLATION") {
        o <- read_curve(m$name, "PCF", "TRANSLATION")
        if (!is.null(o)) record("PCF(ring-normalised K)", corr,
                                gap(o$value, ring_pcf(ref, rr)))
      }
    }
    G <- Gest(X, r = r0, correction = "none")
    o <- read_curve(m$name, "G", "RAW")
    if (!is.null(o) && "raw" %in% names(G)) {
      record("G", "RAW", gap(o$value, as.numeric(G$raw)[keep]))
    }
  } else {
    tgt <- read_pts(m$name, "target")
    Y <- ppp(tgt$x, tgt$y, window = win, checkdup = FALSE)
    XY <- superimpose(a = X, b = Y, W = win)
    KC <- Kcross(XY, "a", "b", r = r0,
                 correction = c("translate", "border", "none"))
    cols <- list(TRANSLATION = "trans", BORDER = "border", NONE = "un")
    for (corr in names(cols)) {
      col <- cols[[corr]]
      if (!col %in% names(KC)) next
      ref <- as.numeric(KC[[col]])[keep]
      o <- read_curve(m$name, "crossK", corr)
      if (!is.null(o)) record("crossK", corr, gap(o$value, ref))
      o <- read_curve(m$name, "crossL", corr)
      if (!is.null(o)) record("crossL", corr, gap(o$value, sqrt(ref / pi)))
    }
    GC <- Gcross(XY, "a", "b", r = r0, correction = "none")
    o <- read_curve(m$name, "crossG", "RAW")
    if (!is.null(o) && "raw" %in% names(GC)) {
      record("crossG", "RAW", gap(o$value, as.numeric(GC$raw)[keep]))
    }
  }
}

say(sprintf("%-26s %-12s %14s %14s %8s  %s",
            "function", "correction", "max abs diff", "max rel diff",
            "points", "verdict"))
failures <- 0
for (key in names(results)) {
  g <- results[[key]]
  parts <- strsplit(key, " (?=[^ ]+$)", perl = TRUE)[[1]]
  fn <- parts[1]; corr <- parts[length(parts)]
  pass <- is.finite(g["rel"]) && g["rel"] <= TOL
  if (!pass) failures <- failures + 1
  say(sprintf("%-26s %-12s %14.3e %14.3e %8d  %s",
              fn, corr, g["abs"], g["rel"], as.integer(g["n"]),
              if (pass) "PASS" else "FAIL"))
}
say("")
say("tolerance: relative difference <= ", format(TOL, scientific = TRUE))
say("overall: ", if (failures == 0) "PASS" else paste0("FAIL (", failures, ")"))

writeLines(report, file.path(root, "v3-report.txt"))
cat("\nReport written to", file.path(root, "v3-report.txt"), "\n")
