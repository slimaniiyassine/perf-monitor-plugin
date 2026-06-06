package com.perfmonitor.startup

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

class PerfMonitorActivity : ProjectActivity {

    override suspend fun execute(project: Project) {
        // Will be used later to check adb connection on startup
        // and initialise the session manager
    }
}