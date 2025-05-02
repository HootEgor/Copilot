package com.github.hootegor.copilot.toolWindow

import com.github.hootegor.copilot.ai.Assistant
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBPanel
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.jcef.JBCefBrowser
import com.vladsch.flexmark.html.HtmlRenderer
import com.vladsch.flexmark.parser.Parser
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.*
import javax.swing.text.html.HTMLDocument
import javax.swing.text.html.HTMLEditorKit

class MyToolWindowFactory : ToolWindowFactory {
    private val assistant: Assistant
    private var currentProjectContext: VirtualFile? = null

    init {
        val openAiKey = System.getenv("OPENAI_KEY") ?: throw IllegalStateException("Missing OpenAI Key in environment variables")
        val assistantId = System.getenv("ASSISTANT_ID") ?: throw IllegalStateException("Missing OpenAI Key in environment variables")
        assistant = Assistant(openAiKey, assistantId)
    }

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
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

        val content = ContentFactory.getInstance().createContent(panel, "AI Assistant", false)
        toolWindow.contentManager.addContent(content)
    }


    private fun sendMessage(
        userMessage: String,
        browser: JBCefBrowser,
        loadingLabel: JLabel,
        htmlBuilder: StringBuilder
    ) {
        val chatId = 12345L

        loadingLabel.isVisible = true

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val userHtml = """
                    <div style="margin-bottom:15px; padding:10px; background-color:#1e1e1e; color:white; border-radius:10px; box-shadow:0 4px 10px rgba(0, 0, 0, 0.3); max-width: 100%; word-wrap: break-word; overflow-wrap: break-word;">
                        <b style="color:#4CAF50;">You:</b>
                        <br>
                        ${userMessage.replace("\n", "<br>")}
                    </div>
                """.trimIndent()
                htmlBuilder.append(userHtml)
                ApplicationManager.getApplication().invokeLater {
                    browser.loadHTML("$htmlBuilder</body></html>")
                }

                val aiResponse = if (currentProjectContext != null) {
                    assistant.handleAssistantFlow(chatId, userMessage, currentProjectContext)
                } else {
                    assistant.handleAssistantFlow(chatId, userMessage)
                }

                val aiHtml = markdownToHtml(aiResponse)
                val fullHtml = """
                    <div style="margin-bottom:15px; padding:10px; background-color:#333; color:white; border-radius:10px; box-shadow:0 4px 10px rgba(0, 0, 0, 0.3); max-width: 100%; word-wrap: break-word; overflow-wrap: break-word; overflow: hidden; word-break: break-word; overflow-x: auto;">
                        <b style="color:#1a73e8;">AI:</b>
                        <br>
                        $aiHtml
                    </div>
                """.trimIndent()

                htmlBuilder.append(fullHtml)

                ApplicationManager.getApplication().invokeLater {
                    browser.loadHTML("$htmlBuilder</body></html>")
                    loadingLabel.isVisible = false
                }
            } catch (e: Exception) {
                val error = "<div style='color:red;'>Error: ${e.message}</div>"
                htmlBuilder.append(error)
                ApplicationManager.getApplication().invokeLater {
                    browser.loadHTML(htmlBuilder.toString() + "</body></html>")
                    loadingLabel.isVisible = false
                }
            }
        }
    }


    // Converts Markdown to HTML using Flexmark
    private fun markdownToHtml(markdown: String): String {
        val parser = Parser.builder().build()
        val document = parser.parse(markdown)
        val renderer = HtmlRenderer.builder().build()
        return renderer.render(document)
    }

    private fun addTextToPane(textPane: JEditorPane, html: String) {
        val doc = textPane.document as HTMLDocument
        val kit = textPane.editorKit as HTMLEditorKit
        try {
            kit.insertHTML(doc, doc.length, html, 0, 0, null)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun shouldBeAvailable(project: Project) = true
}
