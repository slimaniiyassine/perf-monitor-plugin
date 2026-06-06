package com.perfmonitor.services

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project

@Service(Service.Level.PROJECT)
class PerfMonitorService(private val project: Project) {

    // Will hold captured session data for each monitor
    // We'll expand this in Phase 2 when we add adb capture

}