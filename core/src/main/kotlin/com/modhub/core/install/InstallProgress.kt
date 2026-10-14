package com.modhub.core.install

/**
 * Progress callback for installing an archive. Called from the installing coroutine (an
 * I/O thread); implementations should be cheap and thread-safe, and throttle UI updates.
 *
 * [onProgress] reports files written so far ([done]) out of [total] files; `total == 0`
 * means the count is not known yet (e.g. while the archive is being scanned).
 * [currentPath] is the archive-relative path of the file just written, or null for phase
 * changes. [onPhase] is optional and lets UIs label the slower non-extraction steps.
 */
fun interface InstallProgress {
    fun onProgress(done: Int, total: Int, currentPath: String?)

    fun onPhase(phase: Phase) {}

    enum class Phase {
        /** Reading the archive's entry list. */
        SCANNING,
        /** Writing files into the mods folder. */
        EXTRACTING,
        /** Moving into place and reading manifests. */
        FINALIZING,
    }
}
