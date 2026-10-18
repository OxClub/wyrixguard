package com.wyrix.guard.core

object Links {
    private const val DEFAULT_DASHBOARD = "https://oxclub.github.io/wyrix-guard-dashboard/"
    /** The CI variable DASHBOARD_URL wins if set; otherwise the default above is used. */
    fun dashboard(configured: String): String = configured.trim().ifBlank { DEFAULT_DASHBOARD }
}
