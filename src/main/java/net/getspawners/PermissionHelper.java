package net.getspawners;

import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;

import java.lang.reflect.Method;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class PermissionHelper {
    private static final boolean LUCKPERMS_AVAILABLE = isLuckPermsPresent();

    private PermissionHelper() {
    }

    public static boolean isLuckPermsAvailable() {
        return LUCKPERMS_AVAILABLE;
    }

    public static boolean isUsingLuckPerms(GetSpawnersConfig config) {
        return config.useLuckPerms && LUCKPERMS_AVAILABLE;
    }

    public static boolean canUseCommand(ServerCommandSource source, String permission, boolean useLuckPerms) {
        if (!useLuckPerms || !LUCKPERMS_AVAILABLE) {
            return CommandManager.ADMINS_CHECK.allows(source.getPermissions());
        }

        ServerPlayerEntity player = source.getPlayer();
        if (player == null) {
            return true;
        }

        boolean fallback = CommandManager.ADMINS_CHECK.allows(source.getPermissions());
        return checkLuckPerms(player.getUuid(), permission, fallback);
    }

    public static boolean canMineSpawner(ServerPlayerEntity player, boolean useLuckPerms) {
        if (!useLuckPerms || !LUCKPERMS_AVAILABLE) {
            return true;
        }

        return checkLuckPerms(player.getUuid(), "getspawners.mine", false);
    }

    public static boolean canBypassSilk(ServerPlayerEntity player, boolean useLuckPerms) {
        boolean fallback = CommandManager.ADMINS_CHECK.allows(player.getPermissions());
        if (!useLuckPerms || !LUCKPERMS_AVAILABLE) {
            return fallback;
        }

        return checkLuckPerms(player.getUuid(), "getspawners.nosilk", fallback);
    }

    private static boolean isLuckPermsPresent() {
        try {
            Class.forName("net.luckperms.api.LuckPermsProvider");
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean checkLuckPerms(UUID uuid, String node, boolean fallback) {
        try {
            Class<?> providerClass = Class.forName("net.luckperms.api.LuckPermsProvider");
            Object luckPerms = providerClass.getMethod("get").invoke(null);

            Object userManager = luckPerms.getClass().getMethod("getUserManager").invoke(luckPerms);
            Method getUser = userManager.getClass().getMethod("getUser", UUID.class);
            Object user = getUser.invoke(userManager, uuid);

            if (user == null) {
                Method loadUser = userManager.getClass().getMethod("loadUser", UUID.class);
                @SuppressWarnings("unchecked")
                CompletableFuture<Object> future = (CompletableFuture<Object>) loadUser.invoke(userManager, uuid);
                user = future.join();
            }

            if (user == null) {
                return fallback;
            }

            Object cachedData = user.getClass().getMethod("getCachedData").invoke(user);
            Object permissionData = cachedData.getClass().getMethod("getPermissionData").invoke(cachedData);
            Object tristate = permissionData.getClass().getMethod("checkPermission", String.class).invoke(permissionData, node);

            String tristateName = tristate.toString();
            if ("TRUE".equalsIgnoreCase(tristateName)) {
                return true;
            }
            if ("FALSE".equalsIgnoreCase(tristateName)) {
                return false;
            }

            return fallback;
        } catch (Throwable ignored) {
            return fallback;
        }
    }
}
