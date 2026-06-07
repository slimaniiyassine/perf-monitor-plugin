package com.perfmonitor.toolWindow

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
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

        // Detect package name from open project
        val packageName = detectPackageName(project)

        val tabs = JBTabbedPane()

        tabs.addTab("Memory",   MonitorPanel(project, "Memory")
        { service.captureSession(packageName).memory }.panel)

        tabs.addTab("CPU",      MonitorPanel(project, "CPU")
        { service.captureSession(packageName).cpu }.panel)

        tabs.addTab("Network",  MonitorPanel(project, "Network")
        { service.captureSession(packageName).network }.panel)

        tabs.addTab("Battery",  MonitorPanel(project, "Battery")
        { service.captureSession(packageName).battery }.panel)

        tabs.addTab("UI / FPS", MonitorPanel(project, "UI / FPS")
        { service.captureSession(packageName).uiFps }.panel)

        val mainPanel = JPanel(BorderLayout())
        mainPanel.add(tabs, BorderLayout.CENTER)

        val content = contentFactory.createContent(mainPanel, "", false)
        toolWindow.contentManager.addContent(content)
    }

    private fun detectPackageName(project: Project): String {
        // Try to read applicationId from the open project's build files
        val basePath = project.basePath ?: return "com.example"
        val buildFile = java.io.File("$basePath/app/build.gradle.kts")
            .takeIf { it.exists() }
            ?: java.io.File("$basePath/app/build.gradle")
                .takeIf { it.exists() }
            ?: return "com.example"

        return buildFile.readLines()
            .firstOrNull { it.contains("applicationId") }
            ?.trim()
            ?.removePrefix("applicationId")
            ?.replace("=", "")
            ?.replace("\"", "")
            ?.trim()
            ?: "com.example"
    }
}