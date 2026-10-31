package com.mitansh.sidea.ui

internal fun searchBackEnabled(focused: Boolean, playerVisible: Boolean): Boolean =
    !playerVisible && focused
