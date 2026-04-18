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

        general.addEntry(entries.startBooleanToggle(Component.literal("Allow Everyone /gs give"), config.allowEveryoneGiveCommand)
                .setDefaultValue(false)
                .setTooltip(Component.literal("When enabled and LuckPerms is disabled or unavailable, everyone can use /gs give."))
                .setSaveConsumer(value -> config.allowEveryoneGiveCommand = value)
                .build());

        general.addEntry(entries.startBooleanToggle(Component.literal("Allow Everyone /gs types"), config.allowEveryoneTypesCommand)
                .setDefaultValue(false)
                .setTooltip(Component.literal("When enabled and LuckPerms is disabled or unavailable, everyone can use /gs types."))
                .setSaveConsumer(value -> config.allowEveryoneTypesCommand = value)
                .build());

        general.addEntry(entries.startBooleanToggle(Component.literal("Allow Everyone /gs set"), config.allowEveryoneSetCommand)
                .setDefaultValue(false)
                .setTooltip(Component.literal("When enabled and LuckPerms is disabled or unavailable, everyone can use /gs set."))
                .setSaveConsumer(value -> config.allowEveryoneSetCommand = value)
                .build());

        return builder.build();
    }
}
