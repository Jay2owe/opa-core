/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package sc.fiji.opa.core;

import ij.IJ;

/**
 * Signals an ImageJ Escape-key cancellation without classifying it as an
 * analysis error.
 */
public final class AnalysisCancelledException extends RuntimeException {

    public AnalysisCancelledException() {
        super("Object Proximity Analysis was cancelled.");
    }

    public static void check() {
        if (IJ.escapePressed()) {
            throw new AnalysisCancelledException();
        }
    }
}
