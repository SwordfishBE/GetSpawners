package net.getspawners;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.MobSpawnerBlockEntity;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.ExperienceOrbEntity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

public class GetSpawnersMod implements ModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("GetSpawners");
    private static final String PREFIX = "[GetSpawners] ";

    private static final ConcurrentHashMap<CachedSpawnerKey, EntityType<?>> BROKEN_SPAWNER_TYPES = new ConcurrentHashMap<>();
    private static final ConcurrentLinkedQueue<PendingPlacement> PENDING_PLACEMENTS = new ConcurrentLinkedQueue<>();
    private static final ConcurrentLinkedQueue<PendingXpCleanup> PENDING_XP_CLEANUPS = new ConcurrentLinkedQueue<>();
    private static final ConcurrentLinkedQueue<PendingSpawnerDropFix> PENDING_SPAWNER_DROP_FIXES = new ConcurrentLinkedQueue<>();

    private static GetSpawnersConfig config;
    private static SpawnerTypeRegistry typeRegistry;

    @Override
    public void onInitialize() {
        config = GetSpawnersConfig.load();
        typeRegistry = SpawnerTypeRegistry.create();

        registerCommands();
        registerBreakListeners();
        registerPlaceCheck();
        registerTickProcessors();

        LOGGER.info("{}Mod initialized. useLuckPerms={}", PREFIX, config.useLuckPerms);
        logLuckPermsMode();
    }

    private static void logLuckPermsMode() {
        if (config.useLuckPerms && !PermissionHelper.isLuckPermsAvailable()) {
            LOGGER.warn("{}useLuckPerms is true, but LuckPerms is not installed. Falling back to non-LuckPerms behavior.", PREFIX);
            return;
        }

        if (PermissionHelper.isUsingLuckPerms(config)) {
            LOGGER.info("{}LuckPerms permission mode enabled.", PREFIX);
        } else {
            LOGGER.info("{}Non-LuckPerms permission mode enabled.", PREFIX);
        }
    }

    private static MutableText prefixed(String message) {
        return Text.literal(PREFIX + message);
    }

    private static void registerCommands() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            var root = literal("getspawners")
                    .then(literal("types")
                            .requires(source -> PermissionHelper.canUseCommand(source, "getspawners.types", config.useLuckPerms))
                            .executes(GetSpawnersMod::executeTypes))
                    .then(literal("reload")
                            .requires(source -> PermissionHelper.canUseCommand(source, "getspawners.reload", config.useLuckPerms))
                            .executes(GetSpawnersMod::executeReload))
                    .then(literal("give")
                            .requires(source -> PermissionHelper.canUseCommand(source, "getspawners.give", config.useLuckPerms))
                            .then(argument("player", EntityArgumentType.player())
                                    .then(argument("type", StringArgumentType.word())
                                            .suggests(GetSpawnersMod::suggestTypes)
                                            .executes(context -> executeGive(context, 1))
                                            .then(argument("amount", IntegerArgumentType.integer(1, 64))
                                                    .executes(context -> executeGive(context, IntegerArgumentType.getInteger(context, "amount")))))));

            var rootNode = dispatcher.register(root);
            dispatcher.register(literal("gs").redirect(rootNode));
        });
    }

    private static int executeTypes(CommandContext<ServerCommandSource> context) {
        List<String> keys = typeRegistry.keys();
        context.getSource().sendFeedback(() -> prefixed("Available types (" + keys.size() + "): " + String.join(", ", keys)), false);
        return 1;
    }

    private static int executeReload(CommandContext<ServerCommandSource> context) {
        config = GetSpawnersConfig.load();
        typeRegistry = SpawnerTypeRegistry.create();
        context.getSource().sendFeedback(() -> prefixed("Config reloaded."), false);
        LOGGER.info("{}Config reloaded by {}", PREFIX, context.getSource().getName());
        logLuckPermsMode();
        return 1;
    }

    private static int executeGive(CommandContext<ServerCommandSource> context, int amount) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayerEntity target = EntityArgumentType.getPlayer(context, "player");
        String typeInput = StringArgumentType.getString(context, "type");

        Optional<EntityType<?>> type = typeRegistry.resolve(typeInput);
        if (type.isEmpty()) {
            context.getSource().sendError(prefixed("Unknown spawner type: " + typeInput));
            return 0;
        }

        ItemStack stack = SpawnerItemUtil.createSpawnerItem(type.get(), amount);
        boolean inserted = target.getInventory().insertStack(stack);
        if (!inserted) {
            target.dropItem(stack, false);
        }

        String resolvedType = Registries.ENTITY_TYPE.getId(type.get()).toString();
        context.getSource().sendFeedback(() -> prefixed("Gave " + amount + " spawner(s) of type " + resolvedType + " to " + target.getName().getString() + "."), true);
        target.sendMessage(prefixed("You received " + amount + " spawner(s) of type " + resolvedType + ".").formatted(Formatting.GREEN));
        return 1;
    }

    private static CompletableFuture<Suggestions> suggestTypes(CommandContext<ServerCommandSource> context, SuggestionsBuilder builder) {
        for (String key : typeRegistry.keys()) {
            builder.suggest(key);
        }

        return builder.buildFuture();
    }

    private static void registerBreakListeners() {
        PlayerBlockBreakEvents.BEFORE.register((world, player, pos, state, blockEntity) -> {
            if (!state.isOf(Blocks.SPAWNER)) {
                return true;
            }

            if (!(player instanceof ServerPlayerEntity serverPlayer)) {
                return true;
            }

            CachedSpawnerKey key = new CachedSpawnerKey(world, pos);

            if (!PermissionHelper.canMineSpawner(serverPlayer, config.useLuckPerms)) {
                BROKEN_SPAWNER_TYPES.remove(key);
                serverPlayer.sendMessageToClient(prefixed("You do not have permission to mine spawners.").formatted(Formatting.RED), true);
                return false;
            }

            boolean hasSilkTouch = hasSilkTouch(serverPlayer);
            if (!hasSilkTouch && !PermissionHelper.canBypassSilk(serverPlayer, config.useLuckPerms)) {
                // No silk and no bypass: allow normal vanilla break (destroy + XP).
                BROKEN_SPAWNER_TYPES.remove(key);
                return true;
            }

            EntityType<?> entityType = readSpawnerType(world, pos, blockEntity).orElse(EntityType.PIG);
            BROKEN_SPAWNER_TYPES.put(key, entityType);
            return true;
        });

        PlayerBlockBreakEvents.AFTER.register((world, player, pos, state, blockEntity) -> {
            if (world.isClient() || !state.isOf(Blocks.SPAWNER) || !(player instanceof ServerPlayerEntity serverPlayer)) {
                return;
            }

            CachedSpawnerKey key = new CachedSpawnerKey(world, pos);
            EntityType<?> cachedType = BROKEN_SPAWNER_TYPES.remove(key);

            if (serverPlayer.isCreative()) {
                return;
            }

            if (!PermissionHelper.canMineSpawner(serverPlayer, config.useLuckPerms)) {
                return;
            }

            boolean hasSilkTouch = hasSilkTouch(serverPlayer);
            if (!hasSilkTouch && !PermissionHelper.canBypassSilk(serverPlayer, config.useLuckPerms)) {
                return;
            }

            EntityType<?> entityType = cachedType != null ? cachedType : readSpawnerType(world, pos, blockEntity).orElse(EntityType.PIG);
            normalizeSpawnerDrops(world, pos, entityType, true);
            PENDING_SPAWNER_DROP_FIXES.add(new PendingSpawnerDropFix(world.getRegistryKey(), pos.toImmutable(), entityType, 8));

            removeNearbyExperienceOrbs(world, pos, 3.0D);
            PENDING_XP_CLEANUPS.add(new PendingXpCleanup(world.getRegistryKey(), pos.toImmutable(), 12));
        });
    }

    private static void registerPlaceCheck() {
        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            if (world.isClient()) {
                return ActionResult.PASS;
            }

            ItemStack stack = player.getStackInHand(hand);
            if (!stack.isOf(Items.SPAWNER)) {
                return ActionResult.PASS;
            }

            if (!(player instanceof ServerPlayerEntity serverPlayer)) {
                return ActionResult.PASS;
            }

            if (!PermissionHelper.canMineSpawner(serverPlayer, config.useLuckPerms)) {
                ItemStack refund = stack.copyWithCount(1);
                if (!serverPlayer.isCreative() && !stack.isEmpty()) {
                    stack.decrement(1);
                }
                serverPlayer.dropItem(refund, false);
                serverPlayer.sendMessageToClient(prefixed("You do not have permission to place spawners.").formatted(Formatting.RED), true);
                return ActionResult.FAIL;
            }

            Optional<EntityType<?>> itemType = SpawnerItemUtil.readEntityTypeFromSpawnerItem(stack);
            if (itemType.isPresent()) {
                ItemPlacementContext placeContext = new ItemPlacementContext(player, hand, stack, hitResult);
                BlockPos targetPos = placeContext.getBlockPos().toImmutable();
                PENDING_PLACEMENTS.add(new PendingPlacement(world.getRegistryKey(), targetPos, itemType.get(), 4));
            }

            return ActionResult.PASS;
        });
    }

    private static void registerTickProcessors() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            int maxPlacementProcess = Math.min(PENDING_PLACEMENTS.size(), 128);
            for (int i = 0; i < maxPlacementProcess; i++) {
                PendingPlacement pending = PENDING_PLACEMENTS.poll();
                if (pending == null) {
                    break;
                }

                if (!tryApplyPendingPlacement(server, pending) && pending.attemptsLeft() > 0) {
                    PENDING_PLACEMENTS.add(pending.nextAttempt());
                }
            }

            int maxSpawnerDropFixes = Math.min(PENDING_SPAWNER_DROP_FIXES.size(), 128);
            for (int i = 0; i < maxSpawnerDropFixes; i++) {
                PendingSpawnerDropFix fix = PENDING_SPAWNER_DROP_FIXES.poll();
                if (fix == null) {
                    break;
                }

                tryRunSpawnerDropFix(server, fix);
                if (fix.attemptsLeft() > 0) {
                    PENDING_SPAWNER_DROP_FIXES.add(fix.nextAttempt());
                }
            }

            int maxXpProcess = Math.min(PENDING_XP_CLEANUPS.size(), 128);
            for (int i = 0; i < maxXpProcess; i++) {
                PendingXpCleanup cleanup = PENDING_XP_CLEANUPS.poll();
                if (cleanup == null) {
                    break;
                }

                tryRunXpCleanup(server, cleanup);
                if (cleanup.attemptsLeft() > 0) {
                    PENDING_XP_CLEANUPS.add(cleanup.nextAttempt());
                }
            }
        });
    }

    private static boolean tryApplyPendingPlacement(MinecraftServer server, PendingPlacement pending) {
        ServerWorld world = server.getWorld(pending.worldKey());
        if (world == null) {
            return true;
        }

        if (!world.getBlockState(pending.pos()).isOf(Blocks.SPAWNER)) {
            return false;
        }

        BlockEntity blockEntity = world.getBlockEntity(pending.pos());
        if (!(blockEntity instanceof MobSpawnerBlockEntity spawner)) {
            return false;
        }

        spawner.setEntityType(pending.entityType(), world.getRandom());
        spawner.markDirty();
        world.getChunkManager().markForUpdate(pending.pos());
        return true;
    }

    private static void tryRunSpawnerDropFix(MinecraftServer server, PendingSpawnerDropFix fix) {
        ServerWorld world = server.getWorld(fix.worldKey());
        if (world == null) {
            return;
        }

        normalizeSpawnerDrops(world, fix.pos(), fix.entityType(), false);
    }

    private static void tryRunXpCleanup(MinecraftServer server, PendingXpCleanup cleanup) {
        ServerWorld world = server.getWorld(cleanup.worldKey());
        if (world == null) {
            return;
        }

        removeNearbyExperienceOrbs(world, cleanup.pos(), 6.0D);
    }

    private static boolean normalizeSpawnerDrops(World world, BlockPos pos, EntityType<?> entityType, boolean allowCreate) {
        ItemStack typedDrop = SpawnerItemUtil.createSpawnerItem(entityType, 1);
        Box area = new Box(pos).expand(1.5D);

        ItemEntity chosen = null;
        for (ItemEntity itemEntity : world.getEntitiesByClass(ItemEntity.class, area, entity -> entity.getStack().isOf(Items.SPAWNER))) {
            if (chosen == null) {
                chosen = itemEntity;
            } else {
                // Remove duplicate spawner drops at this break position.
                itemEntity.discard();
            }
        }

        if (chosen != null) {
            chosen.setStack(typedDrop);
            return true;
        }

        if (!allowCreate) {
            return false;
        }

        net.minecraft.block.Block.dropStack(world, pos, typedDrop);
        return true;
    }
    private static Optional<EntityType<?>> readSpawnerType(World world, BlockPos pos, BlockEntity blockEntity) {
        if (blockEntity == null) {
            return Optional.empty();
        }

        var nbt = blockEntity.createNbtWithIdentifyingData(world.getRegistryManager());
        return SpawnerItemUtil.readEntityTypeFromBlockEntityNbt(nbt);
    }

    private static boolean hasSilkTouch(ServerPlayerEntity player) {
        ItemStack tool = player.getMainHandStack();
        if (tool.isEmpty()) {
            return false;
        }

        var silkEntry = player.getEntityWorld().getRegistryManager().getOrThrow(RegistryKeys.ENCHANTMENT).getOrThrow(Enchantments.SILK_TOUCH);
        return EnchantmentHelper.getLevel(silkEntry, tool) > 0;
    }

    private static void removeNearbyExperienceOrbs(World world, BlockPos pos, double radius) {
        Box area = new Box(pos).expand(radius);
        for (ExperienceOrbEntity orb : world.getEntitiesByClass(ExperienceOrbEntity.class, area, entity -> true)) {
            orb.discard();
        }
    }

    private record CachedSpawnerKey(RegistryKey<World> worldKey, BlockPos pos) {
        private CachedSpawnerKey(World world, BlockPos pos) {
            this(world.getRegistryKey(), pos.toImmutable());
        }
    }

    private record PendingPlacement(RegistryKey<World> worldKey, BlockPos pos, EntityType<?> entityType, int attemptsLeft) {
        private PendingPlacement nextAttempt() {
            return new PendingPlacement(worldKey, pos, entityType, attemptsLeft - 1);
        }
    }

    private record PendingSpawnerDropFix(RegistryKey<World> worldKey, BlockPos pos, EntityType<?> entityType, int attemptsLeft) {
        private PendingSpawnerDropFix nextAttempt() {
            return new PendingSpawnerDropFix(worldKey, pos, entityType, attemptsLeft - 1);
        }
    }

    private record PendingXpCleanup(RegistryKey<World> worldKey, BlockPos pos, int attemptsLeft) {
        private PendingXpCleanup nextAttempt() {
            return new PendingXpCleanup(worldKey, pos, attemptsLeft - 1);
        }
    }
}

