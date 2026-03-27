package net.getspawners;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

public final class GetSpawnersConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public boolean useLuckPerms = false;

    private static final Path CONFIG_PATH = FabricLoader.getInstance()
            .getConfigDir()
            .resolve("getspawners.json");

    public static GetSpawnersConfig load() {
        if (Files.notExists(CONFIG_PATH)) {
            GetSpawnersConfig config = new GetSpawnersConfig();
            config.save();
            return config;
        }

        try (Reader reader = Files.newBufferedReader(CONFIG_PATH)) {
            GetSpawnersConfig config = GSON.fromJson(reader, GetSpawnersConfig.class);
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
            try (Writer writer = Files.newBufferedWriter(CONFIG_PATH)) {
                GSON.toJson(this, writer);
            }
        } catch (IOException exception) {
            throw new RuntimeException("[GetSpawners] Failed to save config", exception);
        }
    }
}
