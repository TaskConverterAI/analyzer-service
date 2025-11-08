package com.taskconvertai

import org.jetbrains.exposed.sql.Database

object AppContext {
    lateinit var database: Database
}

