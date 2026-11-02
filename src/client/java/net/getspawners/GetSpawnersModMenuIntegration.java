package net.getspawners;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.VersionParsingException;
import net.fabricmc.loader.api.metadata.version.VersionPredicate;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.AlertScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class GetSpawnersModMenuIntegration implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        if (!FabricLoader.getInstance().isModLoaded("cloth-config")) {
            return parent -> notice(parent, "Install Cloth Config for Minecraft 26.4 to edit the configuration here. "
                    + "You can also edit config/getspawners.json and use /gs reload.");
        }
        if (!hasCompatibleClothConfig()) {
            String installed = FabricLoader.getInstance().getModContainer("cloth-config").orElseThrow()
                    .getMetadata().getVersion().getFriendlyString();
            return parent -> notice(parent, "The configuration menu requires Cloth Config for Minecraft 26.4 or newer. "
                    + "The installed version (" + installed + ") cannot render this screen safely. "
                    + "Until then, edit config/getspawners.json and use /gs reload.");
        }

        return GetSpawnersClothConfigScreen::create;
    }

    static boolean hasCompatibleClothConfig() {
        var cloth = FabricLoader.getInstance().getModContainer("cloth-config");
        if (cloth.isEmpty()) {
            return false;
        }
        try {
            return VersionPredicate.parse(">=26.4-").test(cloth.get().getMetadata().getVersion());
        } catch (VersionParsingException exception) {
            return false;
        }
    }

    private static Screen notice(Screen parent, String message) {
        return new AlertScreen(() -> Minecraft.getInstance().setScreenAndShow(parent),
                Component.literal("GetSpawners"), Component.literal(message));
    }
}
