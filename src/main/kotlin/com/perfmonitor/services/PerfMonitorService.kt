package com.perfmonitor.services

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.perfmonitor.adb.AdbRunner
import com.perfmonitor.model.SessionData

@Service(Service.Level.PROJECT)
class PerfMonitorService(private val project: Project) {

    var lastSession: SessionData? = null
        private set

    fun captureSession(packageName: String): SessionData {
        val session = SessionData(
            packageName = packageName,
            memory  = AdbRunner.captureMemory(packageName),
            cpu     = AdbRunner.captureCpu(packageName),
            network = AdbRunner.captureNetwork(),
            battery = AdbRunner.captureBattery(),
            uiFps   = AdbRunner.captureUiFps(packageName)
        )
        lastSession = session
        return session
    }

    fun getConnectedDevice(): String? = AdbRunner.getConnectedDevice()


}