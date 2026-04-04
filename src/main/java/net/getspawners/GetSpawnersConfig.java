package net.getspawners;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class GetSpawnersConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public boolean useLuckPerms = false;
    public boolean noSilkTouchSpawners = false;

    private static final Path CONFIG_PATH = FabricLoader.getInstance()
            .getConfigDir()
            .resolve("getspawners.json");

    public GetSpawnersConfig copy() {
        GetSpawnersConfig copy = new GetSpawnersConfig();
        copy.useLuckPerms = useLuckPerms;
        copy.noSilkTouchSpawners = noSilkTouchSpawners;
        return copy;
    }

    public static GetSpawnersConfig load() {
        if (Files.notExists(CONFIG_PATH)) {
            GetSpawnersConfig config = new GetSpawnersConfig();
            config.save();
            return config;
        }

        try {
            String rawConfig = Files.readString(CONFIG_PATH);
            String json = stripJsonComments(rawConfig);
            GetSpawnersConfig config = GSON.fromJson(json, GetSpawnersConfig.class);
            if (config == null) {
                config = new GetSpawnersConfig();
            }
            config.save();
            return config;
        } catch (IOException exception) {
            throw new RuntimeException("[GetSpawners] Failed to load config", exception);
        }
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            Files.writeString(CONFIG_PATH, toCommentedJson(this));
        } catch (IOException exception) {
            throw new RuntimeException("[GetSpawners] Failed to save config", exception);
        }
    }

    private static String toCommentedJson(GetSpawnersConfig config) {
        StringBuilder builder = new StringBuilder();
        builder.append("{\n");
        appendComment(builder, "When true, GetSpawners checks LuckPerms permission nodes if LuckPerms is installed.");
        appendComment(builder, "If false, GetSpawners uses the config-based behavior instead.");
        appendProperty(builder, "useLuckPerms", config.useLuckPerms, true);

        appendComment(builder, "When true, everyone can bypass the Silk Touch requirement and still collect spawners.");
        appendComment(builder, "When false, mining a spawner without Silk Touch uses normal vanilla behavior.");
        appendProperty(builder, "noSilkTouchSpawners", config.noSilkTouchSpawners, false);
        builder.append("}\n");
        return builder.toString();
    }

    private static void appendComment(StringBuilder builder, String comment) {
        builder.append("  // ").append(comment).append('\n');
    }

    private static void appendProperty(StringBuilder builder, String key, boolean value, boolean trailingComma) {
        builder.append("  \"").append(key).append("\": ").append(value);
        if (trailingComma) {
            builder.append(',');
        }
        builder.append('\n').append('\n');
    }

    private static String stripJsonComments(String input) {
        StringBuilder builder = new StringBuilder(input.length());
        boolean inString = false;
        boolean escaping = false;
        boolean inLineComment = false;
        boolean inBlockComment = false;

        for (int index = 0; index < input.length(); index++) {
            char current = input.charAt(index);
            char next = index + 1 < input.length() ? input.charAt(index + 1) : '\0';

            if (inLineComment) {
                if (current == '\n' || current == '\r') {
                    inLineComment = false;
                    builder.append(current);
                }
                continue;
            }

            if (inBlockComment) {
                if (current == '*' && next == '/') {
                    inBlockComment = false;
                    index++;
                }
                continue;
            }

            if (inString) {
                builder.append(current);
                if (escaping) {
                    escaping = false;
                } else if (current == '\\') {
                    escaping = true;
                } else if (current == '"') {
                    inString = false;
                }
                continue;
            }

            if (current == '"') {
                inString = true;
                builder.append(current);
                continue;
            }

            if (current == '/' && next == '/') {
                inLineComment = true;
                index++;
                continue;
            }

            if (current == '/' && next == '*') {
                inBlockComment = true;
                index++;
                continue;
            }

            builder.append(current);
        }

        return builder.toString();
    }
}
