package com.perfmonitor.ui

import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.perfmonitor.detector.SourceFileDetector
import java.awt.*
import javax.swing.*
import javax.swing.border.EmptyBorder

class SourceFilePicker(
    private val project: Project,
    private val monitorName: String,
    private val packageName: String
) {

    val panel = JPanel(BorderLayout()).apply {
        border = BorderFactory.createTitledBorder(
            BorderFactory.createLineBorder(JBColor(Color(220, 220, 220), Color(60, 60, 60)), 1, true),
            "📁  Source Context",
            0, 0,
            Font("Helvetica Neue", Font.BOLD, 11),
            JBColor(Color(100, 100, 100), Color(160, 160, 160))
        )
        preferredSize = Dimension(0, 170)
        maximumSize   = Dimension(Int.MAX_VALUE, 170)
    }

    private val fileListPanel = JPanel().apply {
        layout     = BoxLayout(this, BoxLayout.Y_AXIS)
        background = JBColor(Color(245, 245, 245), Color(30, 30, 30))
    }

    private val statusLabel = JLabel("Click ⟳ Detect to find relevant source files").apply {
        font       = font.deriveFont(Font.PLAIN, 11f)
        foreground = JBColor(Color(120, 120, 120), Color(150, 150, 150))
        border     = JBUI.Borders.emptyLeft(4)
    }

    private val detectBtn    = JButton("⟳ Detect").apply { font = font.deriveFont(11f) }
    private val addBtn       = JButton("+ Add").apply { font = font.deriveFont(11f) }
    private val selectAllBtn = JButton("✓ All").apply { font = font.deriveFont(11f); toolTipText = "Select all" }
    private val deselectBtn  = JButton("✗ None").apply { font = font.deriveFont(11f); toolTipText = "Deselect all" }
    private val clearBtn     = JButton("Clear").apply { font = font.deriveFont(11f); toolTipText = "Remove all files" }

    val selectedFiles: List<VirtualFile>
        get() = checkboxItems.filter { it.first.isSelected }.map { it.second }

    val sendFullContent: Boolean
        get() = fullRadio.isSelected

    // Exposed so MonitorPanel can pass it to PromptBuilder
    var foregroundScreen: String = ""
        private set

    private val fullRadio       = JRadioButton("Full", true).apply { font = font.deriveFont(11f); isOpaque = false }
    private val signaturesRadio = JRadioButton("Signatures").apply { font = font.deriveFont(11f); isOpaque = false }
    private val modeGroup       = ButtonGroup().also { it.add(fullRadio); it.add(signaturesRadio) }

    private val checkboxItems = mutableListOf<Pair<JCheckBox, VirtualFile>>()

    init {
        val toolbar = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2)).apply {
            isOpaque = false
            add(detectBtn)
            add(addBtn)
            add(vSep())
            add(selectAllBtn)
            add(deselectBtn)
            add(clearBtn)
            add(vSep())
            add(JLabel("Send:").apply { font = font.deriveFont(11f) })
            add(fullRadio)
            add(signaturesRadio)
            add(statusLabel)
        }

        val scroll = JScrollPane(fileListPanel).apply {
            border = BorderFactory.createEmptyBorder()
            verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
            background = fileListPanel.background
        }

        panel.add(toolbar, BorderLayout.NORTH)
        panel.add(scroll, BorderLayout.CENTER)

        detectBtn.addActionListener { detectFiles() }

        addBtn.addActionListener {
            val desc = FileChooserDescriptorFactory.createMultipleFilesNoJarsDescriptor().apply {
                title = "Select Source Files"
            }
            FileChooser.chooseFiles(desc, project, null) { files ->
                files.forEach { addFileRow(it, "Manually added", checked = true) }
                updateStatus()
                fileListPanel.revalidate()
                fileListPanel.repaint()
            }
        }

        selectAllBtn.addActionListener {
            checkboxItems.forEach { it.first.isSelected = true }
            updateStatus()
        }

        deselectBtn.addActionListener {
            checkboxItems.forEach { it.first.isSelected = false }
            updateStatus()
        }

        clearBtn.addActionListener {
            fileListPanel.removeAll()
            checkboxItems.clear()
            fileListPanel.revalidate()
            fileListPanel.repaint()
            updateStatus()
        }
    }

    private fun detectFiles() {
        detectBtn.isEnabled = false
        statusLabel.text    = "Detecting..."
        fileListPanel.removeAll()
        checkboxItems.clear()

        Thread {
            // Capture foreground screen at detection time
            val screen   = SourceFileDetector.getForegroundScreen()
            val detected = SourceFileDetector.detectRelevantFiles(project, monitorName, packageName)

            SwingUtilities.invokeLater {
                foregroundScreen = screen ?: ""

                if (detected.isEmpty()) {
                    statusLabel.text = if (screen == null)
                        "No foreground screen detected — start your app then click ⟳ Detect"
                    else
                        "No files found for $screen — use + Add to add manually"
                } else {
                    detected.forEach { addFileRow(it.virtualFile, it.reason, checked = it.selected) }
                    updateStatus()
                    // Append screen name to status so the user knows what was used
                    if (screen != null) {
                        val cur = statusLabel.text
                        statusLabel.text = "$cur  •  from $screen"
                    }
                }

                detectBtn.isEnabled = true
                fileListPanel.revalidate()
                fileListPanel.repaint()
            }
        }.also { it.isDaemon = true }.start()
    }

    private fun addFileRow(file: VirtualFile, reason: String, checked: Boolean) {
        val cb = JCheckBox("${file.name}  —  $reason", checked).apply {
            font        = font.deriveFont(11f)
            isOpaque    = false
            toolTipText = file.path
            border      = EmptyBorder(2, 4, 2, 4)
            addActionListener { updateStatus() }
        }
        checkboxItems.add(cb to file)

        val removeBtn = JButton("✕").apply {
            font                = font.deriveFont(9f)
            preferredSize       = Dimension(20, 20)
            isBorderPainted     = false
            isContentAreaFilled = false
            foreground          = JBColor(Color(150, 150, 150), Color(120, 120, 120))
            toolTipText         = "Remove"
        }

        val row = JPanel(BorderLayout()).apply {
            isOpaque    = false
            maximumSize = Dimension(Int.MAX_VALUE, 26)
            add(cb, BorderLayout.CENTER)
            add(removeBtn, BorderLayout.EAST)
        }

        removeBtn.addActionListener {
            checkboxItems.removeIf { it.first == cb }
            fileListPanel.remove(row)
            fileListPanel.revalidate()
            fileListPanel.repaint()
            updateStatus()
        }

        fileListPanel.add(row)
    }

    private fun updateStatus() {
        val total    = checkboxItems.size
        val selected = checkboxItems.count { it.first.isSelected }
        statusLabel.text = if (total == 0) "No files — use ⟳ Detect or + Add"
        else "$selected / $total selected"
    }

    private fun vSep() = JSeparator(SwingConstants.VERTICAL).apply {
        preferredSize = Dimension(2, 18)
    }

    fun buildSourceContext(): String {
        if (selectedFiles.isEmpty()) return ""

        val MAX_TOTAL_CHARS = 12_000   // safe for all providers
        val MAX_PER_FILE    = 3_000

        val sb = StringBuilder()
        sb.appendLine("\n--- SOURCE CONTEXT (${selectedFiles.size} files from ${foregroundScreen.ifBlank { "manual selection" }}) ---")

        var remaining = MAX_TOTAL_CHARS

        selectedFiles.forEach { file ->
            if (remaining <= 0) return@forEach
            sb.appendLine("\n### ${file.name}  [${file.path}]")
            try {
                val content = String(file.contentsToByteArray())
                val excerpt = if (sendFullContent) {
                    content.take(MAX_PER_FILE).let {
                        if (content.length > MAX_PER_FILE)
                            "$it\n// ... (${content.length - MAX_PER_FILE} chars truncated)"
                        else it
                    }
                } else {
                    content.lines()
                        .filter { line ->
                            val t = line.trim()
                            t.startsWith("class ")       || t.startsWith("object ")      ||
                                    t.startsWith("interface ")   || t.startsWith("fun ")         ||
                                    t.startsWith("override fun") || t.startsWith("private fun")  ||
                                    t.startsWith("val ")         || t.startsWith("var ")         ||
                                    t.startsWith("@")            || t.contains("suspend fun")
                        }
                        .joinToString("\n")
                        .take(MAX_PER_FILE)
                }
                sb.appendLine(excerpt)
                remaining -= excerpt.length
            } catch (_: Exception) {
                sb.appendLine("// Could not read file")
            }
        }

        sb.appendLine("\n--- END SOURCE CONTEXT ---")
        return sb.toString()
    }}