package com.github.hootegor.copilot.toolWindow

import com.github.hootegor.copilot.ai.Assistant
import com.github.hootegor.copilot.model.AssistantData
import com.intellij.ide.util.PropertiesComponent
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBPanel
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.content.ContentManagerEvent
import com.intellij.ui.content.ContentManagerListener
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.util.ui.UIUtil
import com.vladsch.flexmark.html.HtmlRenderer
import com.vladsch.flexmark.parser.Parser
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*
import javax.swing.text.html.HTMLDocument
import javax.swing.text.html.HTMLEditorKit

class MyToolWindowFactory : ToolWindowFactory {
    private var assistant: Assistant = Assistant()
    private var currentProjectContext: VirtualFile? = null

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val contentManager = toolWindow.contentManager
        // Tab 1: Chat Interface
        val chatPanel = createChatPanel(project)

        // Tab 2: Configuration
        val configPanel = createConfigPanel(project)

        val chatContent = ContentFactory.getInstance().createContent(chatPanel, "Chat", false)
        val configContent = ContentFactory.getInstance().createContent(configPanel, "Settings", false)

        contentManager.addContent(chatContent)
        contentManager.addContent(configContent)

        checkKeyAndId(project)
        contentManager.addContentManagerListener(object : ContentManagerListener {
            override fun selectionChanged(event: ContentManagerEvent) {
                if (event.content === chatContent && event.content.isSelected) {
                    checkKeyAndId(project)
                }
            }
        })
    }

    private fun checkKeyAndId(project: Project) {
        val props = PropertiesComponent.getInstance()
        val key = props.getValue("OPENAI_KEY")
        val id = props.getValue("ASSISTANT_ID")

        if (key.isNullOrBlank() || id.isNullOrBlank()) {
            showNotification(project, "Please configure OpenAI Key and Assistant ID in the Settings tab.")
        } else {
            assistant.setApiKey(key)
            assistant.setAssistantId(id)
        }
    }

    private fun createConfigPanel(project: Project): JPanel {
        val panel = JPanel(BorderLayout())
        val assistantsPanel = JPanel()

        val props = PropertiesComponent.getInstance()
        val savedKey = props.getValue("OPENAI_KEY") ?: ""
        val savedAssistantId = props.getValue("ASSISTANT_ID")
        val openAiKeyField = JTextField(savedKey, 40)

        assistantsPanel.layout = BoxLayout(assistantsPanel, BoxLayout.Y_AXIS)
        val scrollPane = JScrollPane(assistantsPanel)

        var selectedAssistant: AssistantData? = null

        val fetchButton = JButton("Fetch Assistants")
        val selectButton = JButton("Use Selected")

        fetchButton.addActionListener {
            val key = openAiKeyField.text.trim()
            if (key.isNotEmpty()) {
                try {
                    props.setValue("OPENAI_KEY", key)
                    assistant.setApiKey(key)
                    val assistants = assistant.getAvailableAssistants()

                    assistantsPanel.removeAll()
                    selectedAssistant = null

                    for (asst in assistants) {
                        val card = JPanel().apply {
                            layout = BoxLayout(this, BoxLayout.Y_AXIS)
                            border = BorderFactory.createCompoundBorder(
                                BorderFactory.createLineBorder(JBColor.border()),
                                BorderFactory.createEmptyBorder(10, 10, 10, 10)
                            )
                            background = UIUtil.getPanelBackground()
                            alignmentX = Component.LEFT_ALIGNMENT
                            maximumSize = Dimension(Int.MAX_VALUE, Int.MAX_VALUE) // allow full width, natural height
                            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)

                            val labelText = """
                                <html>
                                    <body style='font-family:sans-serif; width:100%;'>
                                        <b>${asst.name}</b><br>
                                        <div style='color:gray; white-space:normal; word-wrap:break-word;'>
                                            ${(asst.instructions ?: "No instructions").replace("\n", "<br>")}
                                        </div>
                                        <i>${asst.model}</i>
                                    </body>
                                </html>
                            """.trimIndent()

                            val label = JLabel(labelText)
                            label.alignmentX = Component.LEFT_ALIGNMENT
                            label.maximumSize = Dimension(Int.MAX_VALUE, Int.MAX_VALUE)

                            add(label)

                            addMouseListener(object : MouseAdapter() {
                                override fun mouseClicked(e: MouseEvent?) {
                                    selectedAssistant = asst
                                    for (comp in assistantsPanel.components) {
                                        if (comp is JPanel) {
                                            comp.background = UIUtil.getPanelBackground()
                                        }
                                    }
                                    background = JBColor(0xCCE4FF, 0x2D3B55)
                                }
                            })
                        }

                        if (asst.id == savedAssistantId) {
                            card.background = JBColor(0xCCE4FF, 0x2D3B55)
                            selectedAssistant = asst
                        }

                        SwingUtilities.invokeLater {
                            card.maximumSize = Dimension(Int.MAX_VALUE, card.preferredSize.height)
                        }

                        assistantsPanel.add(card)
                        assistantsPanel.add(Box.createVerticalStrut(8))
                    }

                    assistantsPanel.revalidate()
                    assistantsPanel.repaint()
                } catch (e: Exception) {
                    assistantsPanel.removeAll()
                    assistantsPanel.add(JLabel("Error: ${e.message}"))
                    assistantsPanel.revalidate()
                    assistantsPanel.repaint()
                }
            }
        }

        selectButton.addActionListener {
            if (selectedAssistant != null && selectedAssistant!!.id != null) {
                props.setValue("ASSISTANT_ID", selectedAssistant!!.id)
                assistant.setAssistantId(selectedAssistant!!.id!!)
                showNotification(project, "Assistant '${selectedAssistant!!.name}' selected.")
            } else {
                showNotification(project, "Please select an assistant.")
            }
        }

        val topPanel = JPanel()
        topPanel.layout = BoxLayout(topPanel, BoxLayout.Y_AXIS)
        topPanel.border = BorderFactory.createEmptyBorder(10, 10, 10, 10)

        openAiKeyField.maximumSize = Dimension(Int.MAX_VALUE, openAiKeyField.preferredSize.height)

        topPanel.add(JLabel("OpenAI Key:"))
        topPanel.add(openAiKeyField)
        topPanel.add(Box.createVerticalStrut(5))
        topPanel.add(fetchButton)
        topPanel.add(Box.createVerticalStrut(10))
        topPanel.add(JLabel("Click an assistant to select:"))
        topPanel.add(Box.createVerticalStrut(5))
        topPanel.add(scrollPane)
        topPanel.add(Box.createVerticalStrut(10))
        topPanel.add(selectButton)

        panel.add(topPanel, BorderLayout.CENTER)
        return panel
    }


    private fun createChatPanel(project: Project): JPanel {
        val panel = JBPanel<JBPanel<*>>(BorderLayout())

        val browser = JBCefBrowser()
        val browserComponent = browser.component

        val inputField = JTextField(40)
        val sendButton = JButton("Send")
        val clearButton = JButton("Clear")
        val loadingLabel = JLabel("Waiting for response...").apply { isVisible = false }

        val htmlBuilder = StringBuilder("<html><body style='font-family:sans-serif;'>")

        val connection = project.messageBus.connect()
        connection.subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, object : FileEditorManagerListener {
            override fun selectionChanged(event: FileEditorManagerEvent) {
                currentProjectContext = event.newFile
            }

            override fun fileOpened(source: FileEditorManager, file: VirtualFile) {
                currentProjectContext = file
            }
        })

        fun doSend() {
            val userMessage = inputField.text.trim()
            if (userMessage.isEmpty()) return
            inputField.text = ""
            if (currentProjectContext == null) {
                currentProjectContext = FileEditorManager.getInstance(project).selectedEditor?.file
            }
            sendMessage(userMessage, browser, loadingLabel, htmlBuilder)
        }

        sendButton.addActionListener { doSend() }
        inputField.addActionListener { doSend() }

        clearButton.addActionListener {
            htmlBuilder.clear()
            htmlBuilder.append("<html><body style='font-family:sans-serif;'>")
            browser.loadHTML(htmlBuilder.toString())
            assistant.clearContext(12345L)
        }

        val buttonPanel = JPanel(FlowLayout()).apply {
            add(sendButton)
            add(clearButton)
            add(loadingLabel)
        }

        val bottomPanel = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            add(inputField, BorderLayout.CENTER)
            add(buttonPanel, BorderLayout.EAST)
        }

        panel.add(browserComponent, BorderLayout.CENTER)
        panel.add(bottomPanel, BorderLayout.SOUTH)

        return panel
    }

    private fun sendMessage(userMessage: String, browser: JBCefBrowser, loadingLabel: JLabel, htmlBuilder: StringBuilder) {
        val chatId = 12345L

        loadingLabel.isVisible = true

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val userHtml = """
                    <div style="margin-bottom:15px; padding:10px; background-color:#1e1e1e; color:white; border-radius:10px; box-shadow:0 4px 10px rgba(0, 0, 0, 0.3);">
                        <b style="color:#4CAF50;">You:</b><br>${userMessage.replace("\n", "<br>")}
                    </div>
                """.trimIndent()
                htmlBuilder.append(userHtml)
                ApplicationManager.getApplication().invokeLater {
                    browser.loadHTML("$htmlBuilder</body></html>")
                }

                val response = if (currentProjectContext != null) {
                    assistant?.handleAssistantFlow(chatId, userMessage, currentProjectContext)
                } else {
                    assistant?.handleAssistantFlow(chatId, userMessage)
                }

                val aiHtml = markdownToHtml(response ?: "No response.")
                val fullHtml = """
                    <div style="margin-bottom:15px; padding:10px; background-color:#333; color:white; border-radius:10px; box-shadow:0 4px 10px rgba(0, 0, 0, 0.3);">
                        <b style="color:#1a73e8;">AI:</b><br>$aiHtml
                    </div>
                """.trimIndent()

                htmlBuilder.append(fullHtml)

                ApplicationManager.getApplication().invokeLater {
                    browser.loadHTML("$htmlBuilder</body><script>window.scrollTo(0, document.body.scrollHeight);</script></html>")
                    loadingLabel.isVisible = false
                }
            } catch (e: Exception) {
                val error = "<div style='color:red;'>Error: ${e.message}</div>"
                htmlBuilder.append(error)
                ApplicationManager.getApplication().invokeLater {
                    browser.loadHTML("$htmlBuilder</body></html>")
                    loadingLabel.isVisible = false
                }
            }
        }
    }

    private fun markdownToHtml(markdown: String): String {
        val parser = Parser.builder().build()
        val document = parser.parse(markdown)
        val renderer = HtmlRenderer.builder().build()
        return renderer.render(document)
    }

    fun showNotification(project: Project, message: String) {
        val group = NotificationGroupManager.getInstance().getNotificationGroup("Copilot Notifications")
        if (group != null) {
            group.createNotification(message, NotificationType.INFORMATION).notify(project)
        } else {
            // fallback: use log or default
            println("Notification group not found")
        }

    }

    override fun shouldBeAvailable(project: Project) = true
}

