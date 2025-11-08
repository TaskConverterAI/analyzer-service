package com.taskconvertai

import io.ktor.server.application.*
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction

fun Application.configureDatabases() {
    // Получаем конфиг из application.yaml или переменных окружения с дефолтами
    val url = environment.config.propertyOrNull("postgres.url")?.getString()
        ?: System.getenv("POSTGRES_URL")
        ?: "jdbc:postgresql://localhost:5432/analyzer"
    val user = environment.config.propertyOrNull("postgres.user")?.getString()
        ?: System.getenv("POSTGRES_USER")
        ?: "analyzer_user"
    val password = environment.config.propertyOrNull("postgres.password")?.getString()
        ?: System.getenv("POSTGRES_PASSWORD")
        ?: "analyzer_pass"

    val database = Database.connect(
        url = url,
        driver = "org.postgresql.Driver",
        user = user,
        password = password
    )
    AppContext.database = database

    transaction(database) {
        SchemaUtils.createMissingTablesAndColumns(Jobs, Transcriptions, Analyses)
    }
}
