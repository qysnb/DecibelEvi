package com.decibelevidence.app.model
/** A snapshot may be processed later; pair wall time with its own monotonic instant. */
internal fun wallTimeAt(atNs: Long, referenceNs: Long, referenceWallMs: Long): Long =
    referenceWallMs - (referenceNs - atNs) / 1_000_000L
