package com.github.hootegor.copilot.ai

import com.github.hootegor.copilot.model.AssistantData
import com.intellij.openapi.vfs.VirtualFile
import com.jetbrains.rd.generator.nova.PredefinedType
import org.json.JSONObject
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.ConcurrentHashMap

class Assistant() {
    private val client = HttpClient.newHttpClient()
    private val threads = ConcurrentHashMap<Long, String>() // Store thread IDs by chat ID
    private val fileNamesInContext = mutableSetOf<String>() // Store file names in context
    private var apiKey: String? = null
    private var assistantId: String? = null

    fun setApiKey(key: String) {
        apiKey = key
    }

    fun setAssistantId(id: String) {
        assistantId = id
    }

    fun getAvailableAssistants(): List<AssistantData> {
        if (apiKey == null) {
            throw IllegalStateException("API Key not set.")
        }

        val request = HttpRequest.newBuilder()
            .uri(URI.create("https://api.openai.com/v1/assistants?order=desc"))
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .header("OpenAI-Beta", "assistants=v2")
            .build()

        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        val json = JSONObject(response.body())
        println("ResponseAssistants: $json")
        val dataArray = json.getJSONArray("data")
        return List(dataArray.length()) { i ->
            val obj = dataArray.getJSONObject(i)
            AssistantData(
                id = obj.optString("id", null),
                name = obj.optString("name", null),
                description = obj.optString("description", null),
                model = obj.optString("model", null),
                instructions = obj.optString("instructions", null)
            )
        }
    }

    fun handleAssistantFlow(chatId: Long, userMessage: String, projectContext: VirtualFile? = null): String {

        if (apiKey == null) {
            throw IllegalStateException("API Key not set.")
        }

        val fileName = projectContext?.name

        val messageToSend = if (fileName != null && !fileNamesInContext.contains(fileName)) {
            fileNamesInContext.add(fileName)
            val projectContextString = getProjectContextFromFile(projectContext)
            "Project context:\n$projectContextString\n\nUser: $userMessage"
        } else {
            userMessage
        }

        println("messageToSend: $messageToSend")

        if (assistantId == null) {
            throw IllegalStateException("Assistant ID not set.")
        }

        val threadId = threads[chatId] ?: run {
            val newThreadId = createNewThread()
            threads[chatId] = newThreadId
            newThreadId
        }

        sendUserMessage(threadId, messageToSend) // 🔄 Send message first
        val runId = createRun(threadId)          // ✅ Create run only after message exists
        waitForRunCompletion(threadId, runId)
        return fetchLastAssistantMessage(threadId, runId)
    }

    fun getAnswerFromCompletion(
        userMessage: String,
        projectContext: VirtualFile? = null
    ): String {
        val prompt = buildString {
            append(userMessage)
            projectContext?.let {
                append("\n\nContext: ${it.name}")
            }
        }

        val requestBody = """
        {
            "model": "gpt-4o",
            "messages": [
                {"role": "system", "content": "Serve as a virtual assistant within an Integrated Development Environment (IDE) to assist developers during the software development process."},
                {"role": "user", "content": ${JSONObject.quote(prompt)}}
            ]
        }
    """.trimIndent()

        val request = HttpRequest.newBuilder()
            .uri(URI.create("https://api.openai.com/v1/chat/completions"))
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(requestBody))
            .build()

        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        val json = JSONObject(response.body())

        return json
            .getJSONArray("choices")
            .getJSONObject(0)
            .getJSONObject("message")
            .getString("content")
    }



    private fun createNewThread(): String {
        val request = HttpRequest.newBuilder()
            .uri(URI.create("https://api.openai.com/v1/threads"))
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .header("OpenAI-Beta", "assistants=v2")
            .POST(HttpRequest.BodyPublishers.ofString("{}"))
            .build()

        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        val json = JSONObject(response.body())
        println("ResponseThread: $json")
        return json.getString("id")
    }

    private fun sendUserMessage(threadId: String, content: String): String {
        val bodyJson = JSONObject()
            .put("role", "user")
            .put("content", content)

        val request = HttpRequest.newBuilder()
            .uri(URI.create("https://api.openai.com/v1/threads/$threadId/messages"))
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .header("OpenAI-Beta", "assistants=v2")
            .POST(HttpRequest.BodyPublishers.ofString(bodyJson.toString()))
            .build()

        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        val json = JSONObject(response.body())
        println("ResponseMessage: $json")
        return json.getString("id")
    }


    private fun createRun(threadId: String): String {
        val bodyJson = JSONObject()
            .put("assistant_id", assistantId)

        val request = HttpRequest.newBuilder()
            .uri(URI.create("https://api.openai.com/v1/threads/$threadId/runs"))
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .header("OpenAI-Beta", "assistants=v2")
            .POST(HttpRequest.BodyPublishers.ofString(bodyJson.toString()))
            .build()

        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        val json = JSONObject(response.body())
        println("ResponseRun: $json")
        return json.getString("id")
    }

    private fun waitForRunCompletion(threadId: String, runId: String) {
        var status = ""
        while (status != "completed" && status != "failed" && status != "cancelled") {
            Thread.sleep(500)

            val request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.openai.com/v1/threads/$threadId/runs/$runId"))
                .header("Authorization", "Bearer $apiKey")
                .header("OpenAI-Beta", "assistants=v2")
                .build()

            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            val json = JSONObject(response.body())
            status = json.getString("status")
        }
    }

    private fun fetchLastAssistantMessage(threadId: String, runId: String): String {
        val request = HttpRequest.newBuilder()
            .uri(URI.create("https://api.openai.com/v1/threads/$threadId/messages?order=desc&run_id=$runId"))
            .header("Authorization", "Bearer $apiKey")
            .header("OpenAI-Beta", "assistants=v2")
            .build()

        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        val json = JSONObject(response.body())
        val messages = json.getJSONArray("data")

        for (i in 0 until messages.length()) {
            val msg = messages.getJSONObject(i)
            if (msg.getString("role") == "assistant") {
                val content = msg.getJSONArray("content")
                return content.getJSONObject(0).getJSONObject("text").getString("value")
            }
        }
        return "No assistant reply found."
    }

    fun clearContext(chatId: Long) {
        threads.remove(chatId)
        fileNamesInContext.clear()
    }

    // Function to retrieve the project context (package or code) from the file
    private fun getProjectContextFromFile(file: VirtualFile): String? {
        if (!file.isValid) {
            return null
        }

        // Read the entire content of the file
        return try {
            val fileContent = file.inputStream.bufferedReader().use { it.readText() }
            fileContent // Return the full content of the file
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
