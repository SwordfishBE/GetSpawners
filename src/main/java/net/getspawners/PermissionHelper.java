package net.getspawners;

import me.lucko.fabric.api.permissions.v0.Permissions;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;

public final class PermissionHelper {
    private static boolean luckPermsInstalled;
    private static boolean luckPermsActive;

    private PermissionHelper() {
    }

    public static void refreshState(GetSpawnersConfig config) {
        luckPermsInstalled = FabricLoader.getInstance().isModLoaded("luckperms");
        luckPermsActive = config.useLuckPerms && luckPermsInstalled;
    }

    public static boolean isLuckPermsAvailable() {
        return luckPermsInstalled;
    }

    public static boolean isUsingLuckPerms(GetSpawnersConfig config) {
        return config.useLuckPerms && luckPermsActive;
    }

    public static boolean canUseCommand(
            CommandSourceStack source,
            String permission,
            GetSpawnersConfig config,
            boolean allowEveryoneWhenConfigMode
    ) {
        if (!isUsingLuckPerms(config)) {
            return allowEveryoneWhenConfigMode || Commands.LEVEL_ADMINS.check(source.permissions());
        }

        ServerPlayer player = source.getPlayer();
        if (player == null) {
            return true;
        }

        return Permissions.check(player, permission, Commands.LEVEL_ADMINS.check(source.permissions()));
    }

    public static boolean canUseAnyCommand(
            CommandSourceStack source,
            GetSpawnersConfig config,
            boolean allowEveryoneWhenConfigMode,
            String... permissions
    ) {
        if (!isUsingLuckPerms(config)) {
            return allowEveryoneWhenConfigMode || Commands.LEVEL_ADMINS.check(source.permissions());
        }

        ServerPlayer player = source.getPlayer();
        if (player == null) {
            return true;
        }

        boolean opFallback = Commands.LEVEL_ADMINS.check(source.permissions());
        for (String permission : permissions) {
            if (Permissions.check(player, permission, opFallback)) {
                return true;
            }
        }

        return false;
    }

    public static boolean canMineSpawner(ServerPlayer player, boolean useLuckPerms) {
        if (!useLuckPerms || !luckPermsActive) {
            return true;
        }

        return Permissions.check(player, "getspawners.mine", false);
    }

    public static boolean canBypassSilk(ServerPlayer player, GetSpawnersConfig config) {
        if (!config.useLuckPerms || !luckPermsActive) {
            return config.noSilkTouchSpawners;
        }

        return Permissions.check(player, "getspawners.nosilk", false);
    }
}
