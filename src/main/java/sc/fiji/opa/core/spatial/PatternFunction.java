/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package sc.fiji.opa.core.spatial;

/**
 * Supported 2D point-pattern functions.
 */
public enum PatternFunction {
    K(false),
    L(false),
    L_MINUS_R(false),
    G(false),
    PAIR_CORRELATION(false),
    CROSS_K(true),
    CROSS_L(true),
    CROSS_G(true),
    // Appended, not inserted. Ordinal order is observable: it is the order
    // EnumSet iterates, therefore the order dialog checkboxes appear, results
    // are produced, and EnumSet.toString() renders into the provenance table.
    // Inserting this next to PAIR_CORRELATION where it reads better would
    // renumber four constants and move output that nothing had broken.
    CROSS_PAIR_CORRELATION(true);

    private final boolean bivariate;

    PatternFunction(boolean bivariate) {
        this.bivariate = bivariate;
    }

    public boolean isBivariate() {
        return bivariate;
    }
}
