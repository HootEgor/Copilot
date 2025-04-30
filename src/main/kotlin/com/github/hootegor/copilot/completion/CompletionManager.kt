package com.github.hootegor.copilot.completion


import com.github.hootegor.copilot.ai.Assistant
import com.github.hootegor.copilot.model.loadSecrets
import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProvider
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorCustomElementRenderer
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.editor.event.CaretEvent
import com.intellij.openapi.editor.event.CaretListener
import com.intellij.openapi.editor.impl.EditorImpl
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.patterns.PlatformPatterns
import com.intellij.util.ProcessingContext
import org.json.JSONObject
import java.awt.Color
import java.awt.Graphics
import java.awt.Rectangle
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.Properties
import java.util.Timer
import java.util.TimerTask

class AICodeCompletionManager(private val project: Project) {

    private var openAiKey = ""

    private var isCompleting = false
    private var lastSuggestion: String? = null

    init {
        // Load secrets from local.properties
        val secrets = loadSecrets()
        openAiKey = secrets.getProperty("openai.key") ?: throw IllegalStateException("Missing OpenAI Key")
    }

    fun start() {
        val factory = com.intellij.openapi.editor.EditorFactory.getInstance()
        factory.eventMulticaster.addCaretListener(object : CaretListener {
            override fun caretPositionChanged(event: CaretEvent) {
                if (isCompleting) return

                val editor = event.editor
                val caret = event.caret ?: return
                val offset = caret.offset
                val document = editor.document

                isCompleting = true
                Timer().schedule(object : TimerTask() {
                    override fun run() {
                        ApplicationManager.getApplication().invokeLater {
                            // Run completion generation on a background thread
                            ApplicationManager.getApplication().executeOnPooledThread {
                                val context = document.getText(TextRange(0, offset))
                                val suggestion = getAISuggestion(context)

                                // Once the suggestion is generated, show it in the editor
                                ApplicationManager.getApplication().invokeLater {
                                    showInlineSuggestion(editor, offset, suggestion)
                                }
                            }
                            isCompleting = false
                        }
                    }
                }, 2000) // 800ms delay to simulate the user typing delay
            }
        }, project)
    }

    private fun getAISuggestion(context: String): String {

        // Escape special characters in context for proper JSON formatting
        val escapedContext = context.replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r")

        val client = HttpClient.newHttpClient()
        val requestBody = """
        {
          "model": "gpt-4",
          "messages": [{"role": "user", "content": "$escapedContext"}]
        }
    """.trimIndent()

        val request = HttpRequest.newBuilder()
            .uri(URI.create("https://api.openai.com/v1/chat/completions"))
            .header("Authorization", "Bearer $openAiKey")  // Replace with your actual API key
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(requestBody))
            .build()

        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        val json = JSONObject(response.body())

        // Log the response for debugging purposes
        println("Response: $json")

        // Handle the response safely to avoid JSON parsing issues
        return try {
            json.getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
        } catch (e: Exception) {
            println("Error parsing response: ${e.message}")
            "Error generating suggestion"
        }
    }


    private fun showInlineSuggestion(editor: Editor, offset: Int, suggestion: String) {
        val inlayModel = editor.inlayModel
        val document = editor.document

        val safeOffset = offset.coerceIn(0, document.textLength)
        val endOffset = (safeOffset + suggestion.length).coerceAtMost(document.textLength)

        // Remove old suggestion if any
        if (lastSuggestion != null) {
            WriteCommandAction.runWriteCommandAction(project) {
                if (endOffset <= document.textLength) {
                    document.deleteString(safeOffset, endOffset)
                }
            }
        }

        // Add inline ghost text
        val renderer = object : EditorCustomElementRenderer {
            override fun calcWidthInPixels(inlay: Inlay<*>): Int {
                val fontMetrics = editor.contentComponent.getFontMetrics(editor.colorsScheme.getFont(EditorFontType.PLAIN))
                return fontMetrics.stringWidth(suggestion)
            }

            override fun paint(inlay: Inlay<*>, g: Graphics, targetRegion: Rectangle, textAttributes: TextAttributes) {
                g.color = Color.GRAY
                g.font = editor.colorsScheme.getFont(EditorFontType.PLAIN)
                g.drawString(suggestion, targetRegion.x, targetRegion.y + g.fontMetrics.ascent)
            }
        }

        val inlay = inlayModel.addInlineElement(offset, true, renderer)

        lastSuggestion = suggestion

        // Handle key press to accept (Tab) or dismiss (any other key)
        editor.contentComponent.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                inlay?.dispose()
                if (e.keyCode == KeyEvent.VK_TAB) {
                    // Accept: insert suggestion
                    WriteCommandAction.runWriteCommandAction(project) {
                        if (endOffset <= document.textLength) {
                            document.insertString(safeOffset, suggestion)
                        }
                    }
                }
                // If not Tab: reject (do nothing since it's just overlay)
                editor.contentComponent.removeKeyListener(this)
            }
        })
    }
}

class AICodeCompletionContributor : CompletionContributor() {
    init {
        extend(
            CompletionType.BASIC,
            PlatformPatterns.psiElement(),
            object : CompletionProvider<CompletionParameters>() {
                override fun addCompletions(
                    parameters: CompletionParameters,
                    context: ProcessingContext,
                    resultSet: CompletionResultSet
                ) {
                    val document = parameters.editor.document
                    val offset = parameters.offset
                    val textBefore = document.getText(TextRange(0, offset))

                    val suggestion = callOpenAICompletion(textBefore)
                    resultSet.addElement(LookupElementBuilder.create(suggestion))
                }
            }
        )
    }

    fun callOpenAICompletion(prompt: String): String {
        val client = HttpClient.newHttpClient()
        val requestBody = """
        {
          "model": "gpt-4",
          "prompt": "$prompt",
          "max_tokens": 50
        }
    """.trimIndent()

        val request = HttpRequest.newBuilder()
            .uri(URI.create("https://api.openai.com/v1/chat/completions"))
            .header("Authorization", "Bearer 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")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(requestBody))
            .build()

        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        val json = JSONObject(response.body())
        return json.getJSONArray("choices").getJSONObject(0)
            .getJSONObject("message").getString("content")
    }
}