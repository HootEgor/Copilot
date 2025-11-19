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
        try {
            println("Copilot: Starting tool window creation...")
            val contentManager = toolWindow.contentManager

            // Tab 1: Chat Interface
            println("Copilot: Creating chat panel...")
            val chatPanel = createChatPanel(project)
            println("Copilot: Chat panel created successfully")

            // Tab 2: Configuration
            println("Copilot: Creating config panel...")
            val configPanel = createConfigPanel(project)
            println("Copilot: Config panel created successfully")

            val chatContent = ContentFactory.getInstance().createContent(chatPanel, "Chat", false)
            val configContent = ContentFactory.getInstance().createContent(configPanel, "Settings", false)

            contentManager.addContent(chatContent)
            contentManager.addContent(configContent)
            println("Copilot: Content added to tool window successfully")

            checkKeyAndId(project)
            contentManager.addContentManagerListener(object : ContentManagerListener {
                override fun selectionChanged(event: ContentManagerEvent) {
                    if (event.content === chatContent && event.content.isSelected) {
                        checkKeyAndId(project)
                    }
                }
            })
            println("Copilot: Tool window initialization complete")
        } catch (e: Exception) {
            println("Copilot ERROR during tool window creation: ${e.message}")
            e.printStackTrace()

            // Create a fallback error panel
            val errorPanel = JPanel(BorderLayout())
            val errorLabel = JLabel("<html><body style='padding:20px;'>" +
                    "<h2>Copilot Tool Window Error</h2>" +
                    "<p>Failed to initialize: ${e.message}</p>" +
                    "<p>Check IDE logs (Help → Show Log in Explorer) for details.</p>" +
                    "</body></html>")
            errorPanel.add(errorLabel, BorderLayout.CENTER)

            val errorContent = ContentFactory.getInstance().createContent(errorPanel, "Error", false)
            toolWindow.contentManager.addContent(errorContent)
        }
    }

    private fun checkKeyAndId(project: Project) {
        val props = PropertiesComponent.getInstance()
        val key = props.getValue("OPENAI_KEY")
        val id = props.getValue("ASSISTANT_ID")

        if (key.isNullOrBlank()) {
            showNotification(project, "Please configure OpenAI Key and Assistant ID in the Settings tab.")
        } else {
            assistant.setApiKey(key)
            if (!id.isNullOrBlank()) {
                assistant.setAssistantId(id)
            }
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

        println("Copilot: Creating Swing-based chat panel...")

        // Use JEditorPane instead of JBCefBrowser for better compatibility
        val chatDisplay = JEditorPane().apply {
            contentType = "text/html"
            isEditable = false
            background = UIUtil.getPanelBackground()

            // Set up HTML rendering with better styling
            val kit = editorKit as HTMLEditorKit
            val doc = document as HTMLDocument
            val css = """
                body { font-family: sans-serif; padding: 10px; background-color: ${getColorHex(UIUtil.getPanelBackground())}; }
                .user-message {
                    margin-bottom: 15px;
                    padding: 10px;
                    background-color: #1e1e1e;
                    color: white;
                    border-radius: 10px;
                }
                .user-label { color: #4CAF50; font-weight: bold; }
                .ai-message {
                    margin-bottom: 15px;
                    padding: 10px;
                    background-color: #333;
                    color: white;
                    border-radius: 10px;
                }
                .ai-label { color: #1a73e8; font-weight: bold; }
                pre { background-color: #2b2b2b; padding: 10px; border-radius: 5px; overflow-x: auto; }
                code { font-family: monospace; background-color: #2b2b2b; padding: 2px 4px; border-radius: 3px; }
            """.trimIndent()
            kit.styleSheet.addRule(css)
        }

        val scrollPane = JScrollPane(chatDisplay).apply {
            verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_ALWAYS
            horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED
        }

        val inputField = JTextField(40)
        val sendButton = JButton("Send")
        val clearButton = JButton("Clear")
        val loadingLabel = JLabel("Waiting for response...").apply { isVisible = false }

        val htmlBuilder = StringBuilder("<html><body>")

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
            sendMessage(userMessage, chatDisplay, loadingLabel, htmlBuilder)
        }

        sendButton.addActionListener { doSend() }
        inputField.addActionListener { doSend() }

        clearButton.addActionListener {
            htmlBuilder.clear()
            htmlBuilder.append("<html><body>")
            chatDisplay.text = htmlBuilder.toString() + "</body></html>"
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

        panel.add(scrollPane, BorderLayout.CENTER)
        panel.add(bottomPanel, BorderLayout.SOUTH)

        println("Copilot: Chat panel created successfully with JEditorPane")
        return panel
    }

    private fun getColorHex(color: Color): String {
        return "#%02x%02x%02x".format(color.red, color.green, color.blue)
    }

    private fun sendMessage(userMessage: String, chatDisplay: JEditorPane, loadingLabel: JLabel, htmlBuilder: StringBuilder) {
        val chatId = 12345L

        loadingLabel.isVisible = true

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val userHtml = """
                    <div class="user-message">
                        <span class="user-label">You:</span><br>${userMessage.replace("\n", "<br>")}
                    </div>
                """.trimIndent()
                htmlBuilder.append(userHtml)
                ApplicationManager.getApplication().invokeLater {
                    chatDisplay.text = "$htmlBuilder</body></html>"
                    chatDisplay.caretPosition = chatDisplay.document.length
                }

                val response = if (currentProjectContext != null) {
                    assistant?.handleAssistantFlow(chatId, userMessage, currentProjectContext)
                } else {
                    assistant?.handleAssistantFlow(chatId, userMessage)
                }

                val aiHtml = markdownToHtml(response ?: "No response.")
                val fullHtml = """
                    <div class="ai-message">
                        <span class="ai-label">AI:</span><br>$aiHtml
                    </div>
                """.trimIndent()

                htmlBuilder.append(fullHtml)

                ApplicationManager.getApplication().invokeLater {
                    chatDisplay.text = "$htmlBuilder</body></html>"
                    chatDisplay.caretPosition = chatDisplay.document.length
                    loadingLabel.isVisible = false
                }
            } catch (e: Exception) {
                val error = "<div style='color:red;'>Error: ${e.message}</div>"
                htmlBuilder.append(error)
                ApplicationManager.getApplication().invokeLater {
                    chatDisplay.text = "$htmlBuilder</body></html>"
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

