/*
 * Copyright (c) 2026 Jamie Malcolm
 *
 * Released under the BSD 3-Clause License. See LICENSE for terms.
 */
package sc.fiji.opa.core;

/**
 * Optional progress callback for long-running engine work.
 *
 * <p>The engine reports; it does not display. A consumer decides whether that
 * becomes an ImageJ status bar, a log line, or nothing at all.</p>
 */
public interface ProgressListener {

    /**
     * Receives a fraction from 0 to 1 and a short description of the current
     * operation.
     */
    void onProgress(double fraction, String message);
}
