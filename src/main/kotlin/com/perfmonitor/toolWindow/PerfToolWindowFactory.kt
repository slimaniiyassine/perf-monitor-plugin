package com.perfmonitor.toolWindow

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.content.ContentFactory
import com.perfmonitor.services.PerfMonitorService
import com.perfmonitor.ui.MonitorPanel
import java.awt.BorderLayout
import javax.swing.JPanel

class PerfToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val service = project.service<PerfMonitorService>()
        val contentFactory = ContentFactory.getInstance()
        val tabs = JBTabbedPane()
        val detectedPackage = detectPackageName(project)

        tabs.addTab("Memory",   MonitorPanel(project, "Memory", detectedPackage)
        { pkg -> service.captureSession(pkg).memory }.panel)

        tabs.addTab("CPU",      MonitorPanel(project, "CPU", detectedPackage)
        { pkg -> service.captureSession(pkg).cpu }.panel)

        tabs.addTab("Network",  MonitorPanel(project, "Network", detectedPackage)
        { pkg -> service.captureSession(pkg).network }.panel)

        tabs.addTab("Battery",  MonitorPanel(project, "Battery", detectedPackage)
        { pkg -> service.captureSession(pkg).battery }.panel)

        tabs.addTab("UI / FPS", MonitorPanel(project, "UI / FPS", detectedPackage)
        { pkg -> service.captureSession(pkg).uiFps }.panel)

        val mainPanel = JPanel(BorderLayout())
        mainPanel.add(tabs, BorderLayout.CENTER)

        val content = contentFactory.createContent(mainPanel, "", false)
        toolWindow.contentManager.addContent(content)
    }

    private fun detectPackageName(project: Project): String {
        val basePath = project.basePath ?: return ""
        val buildFile = java.io.File("$basePath/app/build.gradle.kts")
            .takeIf { it.exists() }
            ?: java.io.File("$basePath/app/build.gradle")
                .takeIf { it.exists() }
            ?: return ""

        return buildFile.readLines()
            .firstOrNull { it.contains("applicationId") }
            ?.trim()
            ?.removePrefix("applicationId")
            ?.replace("=", "")
            ?.replace("\"", "")
            ?.trim()
            ?: ""
    }
}