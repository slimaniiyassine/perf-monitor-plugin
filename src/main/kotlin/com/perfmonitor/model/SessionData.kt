package com.perfmonitor.model

data class SessionData(
    val timestamp: Long = System.currentTimeMillis(),
    val packageName: String = "",
    val memory: String = "",
    val cpu: String = "",
    val network: String = "",
    val battery: String = "",
    val uiFps: String = ""
)