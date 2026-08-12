/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package sc.fiji.opa.core.spatial;

/**
 * Edge treatment for Ripley K and derived curves in a rectangular window.
 */
public enum EdgeCorrection {
    NONE,
    BORDER,
    TRANSLATION
}
