package com.perfmonitor

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.perfmonitor.toolWindow.PerfToolWindowFactory

class PerfMonitorPluginTest : BasePlatformTestCase() {

    fun testToolWindowFactoryExists() {
        val factory = PerfToolWindowFactory()
        assertNotNull(factory)
    }

    fun testProjectServiceExists() {
        assertNotNull(project)
    }
}