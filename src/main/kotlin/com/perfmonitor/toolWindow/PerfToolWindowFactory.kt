package com.perfmonitor.toolWindow

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.content.ContentFactory
import com.perfmonitor.adb.AdbRunner
import com.perfmonitor.services.PerfMonitorService
import com.perfmonitor.ui.MonitorPanel
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import javax.swing.*

class PerfToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val service        = project.service<PerfMonitorService>()
        val contentFactory = ContentFactory.getInstance()
        val detectedPkg    = detectPackageName(project)

        // ── Single shared process combo used by ALL tabs ──────────────
        val sharedProcessCombo = JComboBox<String>().apply {
            preferredSize = Dimension(240, 28)
            toolTipText   = "Select the app process to monitor"
            font          = font.deriveFont(12f)
        }
        val sharedRefreshButton = JButton("⟳").apply {
            toolTipText   = "Refresh process list"
            font          = font.deriveFont(11f)
            preferredSize = Dimension(32, 28)
        }
        val sharedStatusLabel = JLabel("Checking for device...").apply {
            font = font.deriveFont(Font.PLAIN, 11f)
        }

        // ── Build panels — they receive the shared combo ──────────────
        val memoryPanel  = MonitorPanel(project, "Memory",   sharedProcessCombo) { pkg -> service.captureSession(pkg).memory  }
        val cpuPanel     = MonitorPanel(project, "CPU",      sharedProcessCombo) { pkg -> service.captureSession(pkg).cpu     }
        val networkPanel = MonitorPanel(project, "Network",  sharedProcessCombo) { pkg -> service.captureSession(pkg).network }
        val batteryPanel = MonitorPanel(project, "Battery",  sharedProcessCombo) { pkg -> service.captureSession(pkg).battery }
        val uiPanel      = MonitorPanel(project, "UI / FPS", sharedProcessCombo) { pkg -> service.captureSession(pkg).uiFps   }

        // ── Process picker row shared at the top ──────────────────────
        val processRow = JPanel(FlowLayout(FlowLayout.LEFT, 6, 4)).apply {
            isOpaque = false
            add(JLabel("Process:").apply { font = font.deriveFont(12f) })
            add(sharedProcessCombo)
            add(sharedRefreshButton)
            add(sharedStatusLabel)
        }

        // ── Tabs ──────────────────────────────────────────────────────
        val tabs = JBTabbedPane()
        tabs.addTab("Memory",   memoryPanel.panel)
        tabs.addTab("CPU",      cpuPanel.panel)
        tabs.addTab("Network",  networkPanel.panel)
        tabs.addTab("Battery",  batteryPanel.panel)
        tabs.addTab("UI / FPS", uiPanel.panel)

        val mainPanel = JPanel(BorderLayout()).apply {
            add(processRow, BorderLayout.NORTH)
            add(tabs, BorderLayout.CENTER)
        }

        // ── Load processes once for all tabs ──────────────────────────
        fun loadProcesses() {
            sharedRefreshButton.isEnabled  = false
            sharedStatusLabel.text         = "Checking for device..."
            sharedProcessCombo.removeAllItems()

            Thread {
                if (!AdbRunner.isDeviceConnected()) {
                    SwingUtilities.invokeLater {
                        sharedProcessCombo.addItem("No device connected")
                        sharedStatusLabel.text        = "⚠ No device — start emulator then click ⟳"
                        sharedRefreshButton.isEnabled = true
                    }
                    return@Thread
                }

                val processes = try {
                    ProcessBuilder(AdbRunner.resolvedAdbPath(), "shell", "ps", "-e")
                        .redirectErrorStream(true).start()
                        .inputStream.bufferedReader().readLines()
                        .drop(1)
                        .mapNotNull { line ->
                            val parts = line.trim().split("\\s+".toRegex())
                            parts.lastOrNull()?.takeIf {
                                it.contains(".") &&
                                        !it.startsWith("[") &&
                                        !it.startsWith("/") &&
                                        it.length < 100
                            }
                        }
                        .distinct().sorted()
                } catch (e: Exception) { emptyList() }

                if (processes.isEmpty()) {
                    SwingUtilities.invokeLater {
                        sharedStatusLabel.text        = "⚠ No processes found — is the app running?"
                        sharedRefreshButton.isEnabled = true
                    }
                    return@Thread
                }

                val foreground = AdbRunner.getForegroundPackage()

                SwingUtilities.invokeLater {
                    processes.forEach { sharedProcessCombo.addItem(it) }
                    val toSelect = foreground ?: detectedPkg.takeIf { it.isNotBlank() }
                    if (toSelect != null) {
                        val match = processes.indexOfFirst { it.contains(toSelect) }
                        if (match >= 0) sharedProcessCombo.selectedIndex = match
                    }
                    sharedRefreshButton.isEnabled = true
                    sharedStatusLabel.text        = if (foreground != null)
                        "${processes.size} processes — foreground app selected"
                    else
                        "${processes.size} processes found"
                }
            }.start()
        }

        sharedRefreshButton.addActionListener { loadProcesses() }
        loadProcesses()

        val content = contentFactory.createContent(mainPanel, "", false)
        toolWindow.contentManager.addContent(content)
    }

    private fun detectPackageName(project: Project): String {
        val basePath  = project.basePath ?: return ""
        val buildFile = java.io.File("$basePath/app/build.gradle.kts").takeIf { it.exists() }
            ?: java.io.File("$basePath/app/build.gradle").takeIf { it.exists() }
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