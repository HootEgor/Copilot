package com.github.hootegor.copilot.model

data class AssistantData(
    val id: String?,
    val name: String?,
    val description: String?,
    val model: String?,
    val instructions: String? = null,
)
