package app;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class AppConfig {
    public int appPort = 8080;
    public String dbUser = "java_blazing_app";
    public String dbPassword = "devops123";
    public String dbHost = "localhost";
    public String dbDatabase = "store";
    public int dbMaxConnections = 100;

    public static AppConfig load(String pathStr) {
        AppConfig cfg = new AppConfig();
        Path path = Path.of(pathStr);
        if (!Files.exists(path)) {
            return cfg;
        }
        try {
            for (String rawLine : Files.readAllLines(path)) {
                String line = rawLine.trim();
                if (line.isEmpty() || line.startsWith("#") || line.equals("---") || line.equals("db:")) {
                    continue;
                }
                int sep = line.indexOf(':');
                if (sep <= 0) {
                    continue;
                }
                String key = line.substring(0, sep).trim();
                String value = stripQuotes(line.substring(sep + 1).trim());
                switch (key) {
                    case "appPort" -> cfg.appPort = Integer.parseInt(value);
                    case "user" -> cfg.dbUser = value;
                    case "password" -> cfg.dbPassword = value;
                    case "host" -> cfg.dbHost = value;
                    case "database" -> cfg.dbDatabase = value;
                    case "maxConnections" -> cfg.dbMaxConnections = Integer.parseInt(value);
                    default -> { }
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to read config: " + pathStr, e);
        }
        return cfg;
    }

    private static String stripQuotes(String s) {
        if (s.length() >= 2) {
            char first = s.charAt(0);
            char last = s.charAt(s.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return s.substring(1, s.length() - 1);
            }
        }
        return s;
    }
}
