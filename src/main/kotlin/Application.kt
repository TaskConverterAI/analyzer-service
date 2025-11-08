package com.taskconvertai

import io.ktor.server.application.*

fun main(args: Array<String>) {
    io.ktor.server.netty.EngineMain.main(args)
}

fun Application.module() {
    configureHTTP()
    configureSerialization()
    configureSecurity()
    configureDatabases()
    AnalysisProcessor.init(this)
    configureRouting()
}
