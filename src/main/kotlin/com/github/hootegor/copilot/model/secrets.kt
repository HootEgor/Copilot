package com.github.hootegor.copilot.model

import java.io.File
import java.io.FileNotFoundException
import java.util.Properties

fun loadSecrets(): Properties {
    val properties = Properties()

    val inputStream = object {}.javaClass.classLoader.getResourceAsStream("local.properties")
        ?: throw IllegalStateException("local.properties not found in resources")
    inputStream.use { properties.load(it) }
    return properties
}

