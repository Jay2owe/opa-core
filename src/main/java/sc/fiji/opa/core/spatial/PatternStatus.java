/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package sc.fiji.opa.core.spatial;

/**
 * Whether a requested point-pattern curve and global test were estimable.
 */
public enum PatternStatus {
    OK,
    INSUFFICIENT_POINTS,
    NO_VALID_RADII,
    INCOMPLETE_MONTE_CARLO
}
