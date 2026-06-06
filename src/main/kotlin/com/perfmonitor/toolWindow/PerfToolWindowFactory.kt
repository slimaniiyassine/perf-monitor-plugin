package com.perfmonitor.toolWindow

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.content.ContentFactory
import java.awt.BorderLayout
import javax.swing.JPanel

class PerfToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val contentFactory = ContentFactory.getInstance()

        // Create the main panel
        val mainPanel = JPanel(BorderLayout())

        // Create tabs — one per monitor
        val tabs = JBTabbedPane()
        tabs.addTab("Memory",   createPlaceholderPanel("Memory monitor — coming soon"))
        tabs.addTab("CPU",      createPlaceholderPanel("CPU monitor — coming soon"))
        tabs.addTab("Network",  createPlaceholderPanel("Network monitor — coming soon"))
        tabs.addTab("Battery",  createPlaceholderPanel("Battery monitor — coming soon"))
        tabs.addTab("UI / FPS", createPlaceholderPanel("UI rendering monitor — coming soon"))

        mainPanel.add(tabs, BorderLayout.CENTER)

        // Register the panel as the tool window content
        val content = contentFactory.createContent(mainPanel, "", false)
        toolWindow.contentManager.addContent(content)
    }

    // Temporary placeholder for each tab until we build the real UI
    private fun createPlaceholderPanel(message: String): JPanel {
        val panel = JPanel(BorderLayout())
        panel.add(JBLabel(message), BorderLayout.CENTER)
        return panel
    }
}