package app

import java.nio.file.Files
import java.nio.file.Path

class AppConfig {
    var appPort: Int = 8080
    var dbUser: String = "kotlin_blazing_app"
    var dbPassword: String = "devops123"
    var dbHost: String = "localhost"
    var dbDatabase: String = "store"
    var dbMaxConnections: Int = 100

    companion object {
        fun load(pathStr: String): AppConfig {
            val cfg = AppConfig()
            val path = Path.of(pathStr)
            if (!Files.exists(path)) {
                return cfg
            }
            for (rawLine in Files.readAllLines(path)) {
                val line = rawLine.trim()
                if (line.isEmpty() || line.startsWith("#") || line == "---" || line == "db:") {
                    continue
                }
                val sep = line.indexOf(':')
                if (sep <= 0) continue
                val key = line.substring(0, sep).trim()
                val value = line.substring(sep + 1).trim().trim('"', '\'')
                when (key) {
                    "appPort" -> cfg.appPort = value.toInt()
                    "user" -> cfg.dbUser = value
                    "password" -> cfg.dbPassword = value
                    "host" -> cfg.dbHost = value
                    "database" -> cfg.dbDatabase = value
                    "maxConnections" -> cfg.dbMaxConnections = value.toInt()
                }
            }
            return cfg
        }
    }
}
