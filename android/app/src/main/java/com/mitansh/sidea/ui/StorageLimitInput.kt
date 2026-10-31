package com.mitansh.sidea.ui

internal fun storageLimitBytes(input: String): Long? =
    input.takeIf { it.isNotEmpty() && it.all { digit -> digit in '0'..'9' } }
        ?.toLongOrNull()?.takeIf { it in 100L..51200L }?.times(1024L * 1024L)
