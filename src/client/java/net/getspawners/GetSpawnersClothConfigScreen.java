package net.getspawners;

import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

final class GetSpawnersClothConfigScreen {
    private GetSpawnersClothConfigScreen() {
    }

    static Screen create(Screen parent) {
        GetSpawnersConfig config = GetSpawnersMod.loadConfigForEditing();

        ConfigBuilder builder = ConfigBuilder.create()
                .setParentScreen(parent)
                .setTitle(Component.literal("GetSpawners Config"))
                .setSavingRunnable(() -> GetSpawnersMod.applyEditedConfig(config));

        ConfigCategory general = builder.getOrCreateCategory(Component.literal("General"));
        ConfigEntryBuilder entries = builder.entryBuilder();

        general.addEntry(entries.startBooleanToggle(Component.literal("Use LuckPerms"), config.useLuckPerms)
                .setDefaultValue(false)
                .setTooltip(Component.literal("When enabled, GetSpawners checks LuckPerms permission nodes if LuckPerms is installed."))
                .setSaveConsumer(value -> config.useLuckPerms = value)
                .build());

        general.addEntry(entries.startBooleanToggle(Component.literal("No Silk Touch Spawners"), config.noSilkTouchSpawners)
                .setDefaultValue(false)
                .setTooltip(Component.literal("When enabled, everyone can mine spawners without Silk Touch."))
                .setSaveConsumer(value -> config.noSilkTouchSpawners = value)
                .build());

        return builder.build();
    }
}
