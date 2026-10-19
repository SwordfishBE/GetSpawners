package net.getspawners;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.getspawners.mixin.BaseSpawnerAccessor;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.IdentifierArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Prediction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SpawnerBlockEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

public class GetSpawnersMod implements ModInitializer {
    public static final String MOD_ID = "getspawners";
    public static final String MOD_NAME = FabricLoader.getInstance()
            .getModContainer(MOD_ID)
            .map(container -> container.getMetadata().getName())
            .orElse("GetSpawners");
    public static final String MOD_VERSION = FabricLoader.getInstance()
            .getModContainer(MOD_ID)
            .map(container -> container.getMetadata().getVersion().getFriendlyString())
            .orElse("unknown");
    public static final Logger LOGGER = LoggerFactory.getLogger("GetSpawners");
    public static final String LOG_PREFIX = "[" + MOD_NAME + "] ";

    private static final ConcurrentHashMap<CachedSpawnerKey, EntityType<?>> BROKEN_SPAWNER_TYPES = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<CachedSpawnerKey, Boolean> SUPPRESSED_XP_BREAKS = new ConcurrentHashMap<>();

    private static volatile GetSpawnersConfig config;
    private static SpawnerTypeRegistry typeRegistry;

    @Override
    public void onInitialize() {
        config = GetSpawnersConfig.load();
        typeRegistry = SpawnerTypeRegistry.create();
        PermissionHelper.refreshState(config);

        registerCommands();
        registerBreakListeners();
        registerPlaceCheck();
        // Break callbacks and loot generation are synchronous; leftovers must not outlive the tick.
        ServerTickEvents.END_SERVER_TICK.register(server -> clearTransientState());
        ServerLifecycleEvents.SERVER_STARTING.register(server -> clearTransientState());
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> clearTransientState());
        ServerLifecycleEvents.SERVER_STARTED.register(server -> ModrinthUpdateChecker.checkOnceAsync());

        LOGGER.info("{}Mod initialized. Version: {}", LOG_PREFIX, MOD_VERSION);
        logLuckPermsMode();
    }

    private static void logLuckPermsMode() {
        if (config.useLuckPerms && !PermissionHelper.isLuckPermsAvailable()) {
            LOGGER.warn("{}useLuckPerms is true, but LuckPerms is not installed. Falling back to config-based behavior.", LOG_PREFIX);
            return;
        }

        if (PermissionHelper.isUsingLuckPerms(config)) {
            LOGGER.debug("{}LuckPerms permission mode enabled.", LOG_PREFIX);
        } else {
            LOGGER.debug(
                    "{}Non-LuckPerms permission mode enabled. noSilkTouchSpawners={}, allowEveryoneGiveCommand={}, allowEveryoneTypesCommand={}, allowEveryoneSetCommand={}",
                    LOG_PREFIX,
                    config.noSilkTouchSpawners,
                    config.allowEveryoneGiveCommand,
                    config.allowEveryoneTypesCommand,
                    config.allowEveryoneSetCommand
            );
        }
    }

    public static GetSpawnersConfig loadConfigForEditing() {
        return config.copy();
    }

    public static void applyEditedConfig(GetSpawnersConfig editedConfig) {
        config = editedConfig.copy();
        config.save();
        PermissionHelper.refreshState(config);
        LOGGER.debug(
                "{}Config updated from client config screen. useLuckPerms={}, noSilkTouchSpawners={}, allowEveryoneGiveCommand={}, allowEveryoneTypesCommand={}, allowEveryoneSetCommand={}",
                LOG_PREFIX,
                config.useLuckPerms,
                config.noSilkTouchSpawners,
                config.allowEveryoneGiveCommand,
                config.allowEveryoneTypesCommand,
                config.allowEveryoneSetCommand
        );
        logLuckPermsMode();
    }

    private static MutableComponent prefixed(String message) {
        return Component.literal(LOG_PREFIX + message);
    }

    private static void registerCommands() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            var root = literal("getspawners")
                    .then(literal("types")
                            .requires(source -> PermissionHelper.canUseCommand(source, "getspawners.types", config, config.allowEveryoneTypesCommand))
                            .executes(GetSpawnersMod::executeTypes))
                    .then(literal("reload")
                            .requires(source -> PermissionHelper.canUseCommand(source, "getspawners.reload", config, false))
                            .executes(GetSpawnersMod::executeReload))
                    .then(literal("give")
                            .requires(source -> PermissionHelper.canUseCommand(source, "getspawners.give", config, config.allowEveryoneGiveCommand))
                            .then(argument("player", EntityArgument.player())
                                    .then(argument("type", IdentifierArgument.id())
                                            .suggests(GetSpawnersMod::suggestTypes)
                                            .executes(context -> executeGive(context, 1))
                                            .then(argument("amount", IntegerArgumentType.integer(1, 64))
                                                    .executes(context -> executeGive(context, IntegerArgumentType.getInteger(context, "amount")))))))
                    .then(literal("set")
                            .requires(source -> PermissionHelper.canUseCommand(source, "getspawners.set", config, config.allowEveryoneSetCommand))
                            .then(argument("type", IdentifierArgument.id())
                                    .suggests(GetSpawnersMod::suggestTypes)
                                    .executes(GetSpawnersMod::executeSet)));

            var rootNode = dispatcher.register(root);
            dispatcher.register(literal("gs").redirect(rootNode));
        });
    }

    private static int executeTypes(CommandContext<CommandSourceStack> context) {
        List<String> keys = typeRegistry.keys();
        context.getSource().sendSuccess(() -> prefixed("Available types (" + keys.size() + "): " + String.join(", ", keys)), false);
        return 1;
    }

    private static int executeReload(CommandContext<CommandSourceStack> context) {
        config = GetSpawnersConfig.load();
        typeRegistry = SpawnerTypeRegistry.create();
        PermissionHelper.refreshState(config);
        context.getSource().sendSuccess(() -> prefixed("Config reloaded."), false);
        LOGGER.info("{}Config reloaded via command.", LOG_PREFIX);
        LOGGER.debug("{}Config reloaded by {}", LOG_PREFIX, context.getSource().getTextName());
        logLuckPermsMode();
        return 1;
    }

    private static int executeGive(CommandContext<CommandSourceStack> context, int amount) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer target = EntityArgument.getPlayer(context, "player");
        String typeInput = IdentifierArgument.getId(context, "type").toString();

        Optional<EntityType<?>> type = typeRegistry.resolve(typeInput);
        if (type.isEmpty()) {
            context.getSource().sendFailure(prefixed("Unknown spawner type: " + typeInput));
            return 0;
        }

        ItemStack stack = SpawnerItemUtil.createSpawnerItem(type.get(), amount);
        boolean inserted = target.getInventory().add(stack);
        if (!inserted && !stack.isEmpty()) {
            target.drop(stack, false, Prediction.SERVER_ONLY);
        }

        String resolvedType = BuiltInRegistries.ENTITY_TYPE.getKey(type.get()).toString();
        context.getSource().sendSuccess(() -> prefixed("Gave " + amount + " spawner(s) of type " + resolvedType + " to " + target.getName().getString() + "."), true);
        target.sendSystemMessage(prefixed("You received " + amount + " spawner(s) of type " + resolvedType + ".").withStyle(ChatFormatting.GREEN));
        return 1;
    }

    private static int executeSet(CommandContext<CommandSourceStack> context) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        String typeInput = IdentifierArgument.getId(context, "type").toString();

        Optional<EntityType<?>> type = typeRegistry.resolve(typeInput);
        if (type.isEmpty()) {
            context.getSource().sendFailure(prefixed("Unknown spawner type: " + typeInput));
            return 0;
        }

        ItemStack heldStack = player.getMainHandItem();
        if (heldStack.isEmpty() || heldStack.getItem() != Items.SPAWNER) {
            context.getSource().sendFailure(prefixed("Hold a spawner stack in your active slot first."));
            return 0;
        }

        ItemStack updatedStack = SpawnerItemUtil.withSpawnerItemType(heldStack, type.get());
        player.setItemInHand(InteractionHand.MAIN_HAND, updatedStack);
        player.getInventory().setChanged();
        player.containerMenu.broadcastChanges();

        String resolvedType = BuiltInRegistries.ENTITY_TYPE.getKey(type.get()).toString();
        context.getSource().sendSuccess(() -> prefixed("Set the spawner stack in your active slot to type " + resolvedType + "."), false);
        return 1;
    }

    private static CompletableFuture<Suggestions> suggestTypes(CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
        return net.minecraft.commands.SharedSuggestionProvider.suggest(typeRegistry.keys(), builder);
    }

    private static void registerBreakListeners() {
        PlayerBlockBreakEvents.BEFORE.register((world, player, pos, state, blockEntity) -> {
            if (!state.is(Blocks.SPAWNER) || !(player instanceof ServerPlayer serverPlayer)) {
                return true;
            }

            CachedSpawnerKey key = new CachedSpawnerKey(world, pos);
            clearBreakState(key);
            if (!PermissionHelper.canMineSpawner(serverPlayer, config.useLuckPerms)) {
                serverPlayer.sendSystemMessage(prefixed("You do not have permission to mine spawners.").withStyle(ChatFormatting.RED), true);
                return false;
            }

            if (!serverPlayer.isCreative()
                    && (hasSilkTouch(serverPlayer, serverPlayer.getMainHandItem())
                    || PermissionHelper.canBypassSilk(serverPlayer, config))) {
                readSpawnerType(world, pos, blockEntity).ifPresent(type -> BROKEN_SPAWNER_TYPES.put(key, type));
            }
            return true;
        });

        PlayerBlockBreakEvents.AFTER.register((world, player, pos, state, blockEntity) ->
                clearBreakState(new CachedSpawnerKey(world, pos)));
        PlayerBlockBreakEvents.CANCELED.register((world, player, pos, state, blockEntity) ->
                clearBreakState(new CachedSpawnerKey(world, pos)));
    }

    private static void registerPlaceCheck() {
        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            if (!world.isClientSide() && player instanceof ServerPlayer serverPlayer
                    && player.getItemInHand(hand).is(Items.SPAWNER)
                    && !PermissionHelper.canMineSpawner(serverPlayer, config.useLuckPerms)) {
                serverPlayer.sendSystemMessage(prefixed("You do not have permission to place spawners.").withStyle(ChatFormatting.RED), true);
                return InteractionResult.FAIL;
            }
            return InteractionResult.PASS;
        });
    }

    private static void clearBreakState(CachedSpawnerKey key) {
        BROKEN_SPAWNER_TYPES.remove(key);
        SUPPRESSED_XP_BREAKS.remove(key);
    }

    private static void clearTransientState() {
        BROKEN_SPAWNER_TYPES.clear();
        SUPPRESSED_XP_BREAKS.clear();
    }

    public static boolean shouldSuppressExperience(ServerLevel world, BlockPos pos) {
        return SUPPRESSED_XP_BREAKS.remove(new CachedSpawnerKey(world, pos)) != null;
    }

    public static Optional<ItemStack> getDirectTypedSpawnerDrop(
            ServerLevel world, BlockPos pos, BlockEntity blockEntity, Player player, ItemStack tool
    ) {
        if (!(player instanceof ServerPlayer serverPlayer) || serverPlayer.isCreative()
                || !PermissionHelper.canMineSpawner(serverPlayer, config.useLuckPerms)) {
            return Optional.empty();
        }

        // Vanilla passes the pre-damage tool copy, even when the last hit breaks the held tool.
        if (!hasSilkTouch(serverPlayer, tool) && !PermissionHelper.canBypassSilk(serverPlayer, config)) {
            return Optional.empty();
        }

        CachedSpawnerKey key = new CachedSpawnerKey(world, pos);
        EntityType<?> type = readSpawnerType(world, pos, blockEntity).orElse(BROKEN_SPAWNER_TYPES.get(key));
        if (type == null) {
            return Optional.empty();
        }

        SUPPRESSED_XP_BREAKS.put(key, Boolean.TRUE);
        return Optional.of(SpawnerItemUtil.createSpawnerItem(type, 1));
    }

    private static Optional<EntityType<?>> readSpawnerType(Level world, BlockPos pos, BlockEntity blockEntity) {
        if (!(blockEntity instanceof SpawnerBlockEntity spawner)) {
            return Optional.empty();
        }

        var nextSpawnData = ((BaseSpawnerAccessor) spawner.getSpawner()).getspawners$getNextSpawnData();
        if (nextSpawnData != null) {
            String rawId = nextSpawnData.getEntityToSpawn().getString("id").orElse("");
            var id = net.minecraft.resources.Identifier.tryParse(rawId);
            if (id != null && BuiltInRegistries.ENTITY_TYPE.containsKey(id)) {
                return Optional.of(BuiltInRegistries.ENTITY_TYPE.getValue(id));
            }
        }

        return SpawnerItemUtil.readEntityTypeFromBlockEntityNbt(spawner.saveWithFullMetadata(world.registryAccess()));
    }

    private static boolean hasSilkTouch(ServerPlayer player, ItemStack tool) {
        if (tool.isEmpty()) {
            return false;
        }
        var enchantRegistry = player.level().registryAccess().lookupOrThrow(Registries.ENCHANTMENT);
        return EnchantmentHelper.getItemEnchantmentLevel(enchantRegistry.getOrThrow(Enchantments.SILK_TOUCH), tool) > 0;
    }

    private record CachedSpawnerKey(ResourceKey<Level> worldKey, BlockPos pos) {
        private CachedSpawnerKey(Level world, BlockPos pos) {
            this(world.dimension(), pos.immutable());
        }
    }
}
