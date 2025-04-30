package com.github.hootegor.copilot.startup

import com.github.hootegor.copilot.completion.AICodeCompletionManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

class MyProjectActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
//        val copilotManager = AICodeCompletionManager(project)
//        copilotManager.start()
    }
}