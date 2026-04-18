package net.getspawners;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
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
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SpawnerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

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
    private static final double SPAWNER_DROP_MATCH_RANGE = 1.25D;
    private static final double SPAWNER_DROP_MATCH_RANGE_SQR = SPAWNER_DROP_MATCH_RANGE * SPAWNER_DROP_MATCH_RANGE;
    private static final int SPAWNER_DROP_FIX_DEBUG_THRESHOLD = 8;
    private static final long SPAWNER_DROP_FIX_DEBUG_COOLDOWN_TICKS = 40L;

    private static final ConcurrentHashMap<CachedSpawnerKey, EntityType<?>> BROKEN_SPAWNER_TYPES = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<CachedSpawnerKey, Boolean> DIRECT_TYPED_DROPS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<CachedSpawnerKey, EntityType<?>> KNOWN_SPAWNER_TYPES = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<CachedSpawnerKey, EntityType<?>> PENDING_PLACEMENT_TYPE_HINTS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<CachedSpawnerKey, Boolean> SUPPRESSED_XP_BREAKS = new ConcurrentHashMap<>();
    private static final ConcurrentLinkedQueue<PendingPlacement> PENDING_PLACEMENTS = new ConcurrentLinkedQueue<>();
    private static final ConcurrentLinkedQueue<PendingSpawnerDropFix> PENDING_SPAWNER_DROP_FIXES = new ConcurrentLinkedQueue<>();

    private static GetSpawnersConfig config;
    private static SpawnerTypeRegistry typeRegistry;
    private static long lastSpawnerDropFixDebugTick = Long.MIN_VALUE;
    private static boolean spawnerDropFixBacklogActive;

    @Override
    public void onInitialize() {
        config = GetSpawnersConfig.load();
        typeRegistry = SpawnerTypeRegistry.create();
        PermissionHelper.refreshState(config);

        registerCommands();
        registerBreakListeners();
        registerPlaceCheck();
        registerTickProcessors();
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
                                    .then(argument("type", StringArgumentType.word())
                                            .suggests(GetSpawnersMod::suggestTypes)
                                            .executes(context -> executeGive(context, 1))
                                            .then(argument("amount", IntegerArgumentType.integer(1, 64))
                                                    .executes(context -> executeGive(context, IntegerArgumentType.getInteger(context, "amount")))))))
                    .then(literal("set")
                            .requires(source -> PermissionHelper.canUseAnyCommand(
                                    source,
                                    config,
                                    config.allowEveryoneSetCommand,
                                    "getspawner.set",
                                    "getspawners.set"
                            ))
                            .then(argument("type", StringArgumentType.word())
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
        String typeInput = StringArgumentType.getString(context, "type");

        Optional<EntityType<?>> type = typeRegistry.resolve(typeInput);
        if (type.isEmpty()) {
            context.getSource().sendFailure(prefixed("Unknown spawner type: " + typeInput));
            return 0;
        }

        ItemStack stack = SpawnerItemUtil.createSpawnerItem(type.get(), amount);
        boolean inserted = target.getInventory().add(stack);
        if (!inserted && !stack.isEmpty()) {
            target.drop(stack, false, false);
        }

        String resolvedType = BuiltInRegistries.ENTITY_TYPE.getKey(type.get()).toString();
        context.getSource().sendSuccess(() -> prefixed("Gave " + amount + " spawner(s) of type " + resolvedType + " to " + target.getName().getString() + "."), true);
        target.sendSystemMessage(prefixed("You received " + amount + " spawner(s) of type " + resolvedType + ".").withStyle(ChatFormatting.GREEN));
        return 1;
    }

    private static int executeSet(CommandContext<CommandSourceStack> context) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        String typeInput = StringArgumentType.getString(context, "type");

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
        for (String key : typeRegistry.keys()) {
            builder.suggest(key);
        }

        return builder.buildFuture();
    }

    private static void registerBreakListeners() {
        PlayerBlockBreakEvents.BEFORE.register((world, player, pos, state, blockEntity) -> {
            if (state.getBlock() != Blocks.SPAWNER) {
                return true;
            }

            if (!(player instanceof ServerPlayer serverPlayer)) {
                return true;
            }

            CachedSpawnerKey key = new CachedSpawnerKey(world, pos);

            if (!PermissionHelper.canMineSpawner(serverPlayer, config.useLuckPerms)) {
                BROKEN_SPAWNER_TYPES.remove(key);
                DIRECT_TYPED_DROPS.remove(key);
                serverPlayer.sendSystemMessage(prefixed("You do not have permission to mine spawners.").withStyle(ChatFormatting.RED), true);
                return false;
            }

            boolean hasSilkTouch = hasSilkTouch(serverPlayer);
            if (!hasSilkTouch && !PermissionHelper.canBypassSilk(serverPlayer, config)) {
                BROKEN_SPAWNER_TYPES.remove(key);
                DIRECT_TYPED_DROPS.remove(key);
                SUPPRESSED_XP_BREAKS.remove(key);
                return true;
            }

            Optional<EntityType<?>> entityType = resolveSpawnerType(world, pos, blockEntity, key, "before_break");
            if (entityType.isEmpty()) {
                BROKEN_SPAWNER_TYPES.remove(key);
                DIRECT_TYPED_DROPS.remove(key);
                SUPPRESSED_XP_BREAKS.remove(key);
                return true;
            }

            BROKEN_SPAWNER_TYPES.put(key, entityType.get());
            DIRECT_TYPED_DROPS.put(key, Boolean.TRUE);
            SUPPRESSED_XP_BREAKS.put(key, Boolean.TRUE);
            return true;
        });

        PlayerBlockBreakEvents.AFTER.register((world, player, pos, state, blockEntity) -> {
            if (world.isClientSide() || state.getBlock() != Blocks.SPAWNER || !(player instanceof ServerPlayer serverPlayer)) {
                return;
            }

            CachedSpawnerKey key = new CachedSpawnerKey(world, pos);
            EntityType<?> cachedType = BROKEN_SPAWNER_TYPES.remove(key);
            EntityType<?> knownType = KNOWN_SPAWNER_TYPES.remove(key);
            if (DIRECT_TYPED_DROPS.remove(key) != null) {
                return;
            }

            if (serverPlayer.isCreative()) {
                SUPPRESSED_XP_BREAKS.remove(key);
                return;
            }

            if (!PermissionHelper.canMineSpawner(serverPlayer, config.useLuckPerms)) {
                DIRECT_TYPED_DROPS.remove(key);
                return;
            }

            boolean hasSilkTouch = hasSilkTouch(serverPlayer);
            if (!hasSilkTouch && !PermissionHelper.canBypassSilk(serverPlayer, config)) {
                DIRECT_TYPED_DROPS.remove(key);
                return;
            }

            EntityType<?> entityType = cachedType != null
                    ? cachedType
                    : resolveSpawnerType(world, pos, blockEntity, key, knownType, PENDING_PLACEMENT_TYPE_HINTS.get(key), "after_break").orElse(null);
            if (entityType == null) {
                return;
            }

            PENDING_SPAWNER_DROP_FIXES.add(new PendingSpawnerDropFix(world.dimension(), pos.immutable(), entityType, 8));
        });
    }

    private static void registerPlaceCheck() {
        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            if (world.isClientSide()) {
                return InteractionResult.PASS;
            }

            ItemStack stack = player.getItemInHand(hand);
            if (stack.getItem() != Items.SPAWNER) {
                return InteractionResult.PASS;
            }

            if (!(player instanceof ServerPlayer serverPlayer)) {
                return InteractionResult.PASS;
            }

            if (!PermissionHelper.canMineSpawner(serverPlayer, config.useLuckPerms)) {
                serverPlayer.sendSystemMessage(prefixed("You do not have permission to place spawners.").withStyle(ChatFormatting.RED), true);
                return InteractionResult.FAIL;
            }

            Optional<EntityType<?>> itemType = SpawnerItemUtil.readEntityTypeFromSpawnerItem(stack);
            if (itemType.isPresent()) {
                BlockPlaceContext placementContext = new BlockPlaceContext(player, hand, stack, hitResult);
                BlockPlaceContext resolvedContext = placementContext;
                if (stack.getItem() instanceof BlockItem blockItem) {
                    BlockPlaceContext updatedContext = blockItem.updatePlacementContext(placementContext);
                    if (updatedContext != null) {
                        resolvedContext = updatedContext;
                    }
                }

                BlockPos primaryPos = resolvedContext.getClickedPos().immutable();
                BlockPos secondaryPos = primaryPos;
                if (!resolvedContext.canPlace()) {
                    secondaryPos = hitResult.getBlockPos().relative(hitResult.getDirection()).immutable();
                }

                cachePendingPlacementHints(world, primaryPos, secondaryPos, itemType.get());
                PENDING_PLACEMENTS.add(new PendingPlacement(world.dimension(), primaryPos, secondaryPos, itemType.get(), 4));
            }

            return InteractionResult.PASS;
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

                if (!tryApplyPendingPlacement(server, pending)) {
                    if (pending.attemptsLeft() > 0) {
                        PENDING_PLACEMENTS.add(pending.nextAttempt());
                    } else {
                        clearPendingPlacementHints(pending);
                    }
                }
            }

            int maxSpawnerDropFixes = Math.min(PENDING_SPAWNER_DROP_FIXES.size(), 128);
            logSpawnerDropFixBacklog(server, maxSpawnerDropFixes);
            processSpawnerDropFixes(server, maxSpawnerDropFixes);

        });
    }

    private static void logSpawnerDropFixBacklog(MinecraftServer server, int pendingFixCount) {
        ServerLevel overworld = server.overworld();
        if (overworld == null) {
            return;
        }

        long gameTime = overworld.getGameTime();
        if (pendingFixCount >= SPAWNER_DROP_FIX_DEBUG_THRESHOLD) {
            if (!spawnerDropFixBacklogActive || gameTime - lastSpawnerDropFixDebugTick >= SPAWNER_DROP_FIX_DEBUG_COOLDOWN_TICKS) {
                LOGGER.debug("{}Pending spawner drop fixes queued: {}", LOG_PREFIX, pendingFixCount);
                lastSpawnerDropFixDebugTick = gameTime;
            }
            spawnerDropFixBacklogActive = true;
            return;
        }

        if (spawnerDropFixBacklogActive && pendingFixCount == 0) {
            LOGGER.debug("{}Pending spawner drop fix backlog cleared.", LOG_PREFIX);
            spawnerDropFixBacklogActive = false;
            lastSpawnerDropFixDebugTick = gameTime;
        }
    }

    private static boolean tryApplyPendingPlacement(MinecraftServer server, PendingPlacement pending) {
        ServerLevel world = server.getLevel(pending.worldKey());
        if (world == null) {
            clearPendingPlacementHints(pending);
            return true;
        }

        if (tryApplyPendingPlacementAt(world, pending.primaryPos(), pending.entityType())) {
            clearPendingPlacementHints(pending);
            return true;
        }

        if (pending.secondaryPos().equals(pending.primaryPos())) {
            return false;
        }

        if (tryApplyPendingPlacementAt(world, pending.secondaryPos(), pending.entityType())) {
            clearPendingPlacementHints(pending);
            return true;
        }

        return false;
    }

    private static boolean tryApplyPendingPlacementAt(ServerLevel world, BlockPos pos, EntityType<?> entityType) {
        BlockState state = world.getBlockState(pos);
        if (state.getBlock() != Blocks.SPAWNER) {
            return false;
        }

        BlockEntity blockEntity = world.getBlockEntity(pos);
        if (!(blockEntity instanceof SpawnerBlockEntity spawner)) {
            return false;
        }

        spawner.setEntityId(entityType, world.getRandom());
        spawner.setChanged();
        world.sendBlockUpdated(pos, state, state, 3);
        KNOWN_SPAWNER_TYPES.put(new CachedSpawnerKey(world, pos), entityType);
        return true;
    }

    public static boolean shouldSuppressExperience(ServerLevel world, BlockPos pos) {
        return SUPPRESSED_XP_BREAKS.remove(new CachedSpawnerKey(world, pos)) != null;
    }

    private static void processSpawnerDropFixes(MinecraftServer server, int maxSpawnerDropFixes) {
        if (maxSpawnerDropFixes <= 0) {
            return;
        }

        List<PendingSpawnerDropFix> fixes = new ArrayList<>(maxSpawnerDropFixes);
        for (int i = 0; i < maxSpawnerDropFixes; i++) {
            PendingSpawnerDropFix fix = PENDING_SPAWNER_DROP_FIXES.poll();
            if (fix == null) {
                break;
            }
            fixes.add(fix);
        }

        Map<ResourceKey<Level>, List<PendingSpawnerDropFix>> fixesByWorld = new HashMap<>();
        for (PendingSpawnerDropFix fix : fixes) {
            fixesByWorld.computeIfAbsent(fix.worldKey(), ignored -> new ArrayList<>()).add(fix);
        }

        for (Map.Entry<ResourceKey<Level>, List<PendingSpawnerDropFix>> entry : fixesByWorld.entrySet()) {
            ServerLevel world = server.getLevel(entry.getKey());
            if (world == null) {
                continue;
            }

            resolveSpawnerDropFixes(world, entry.getValue());
        }
    }

    private static void resolveSpawnerDropFixes(ServerLevel world, List<PendingSpawnerDropFix> fixes) {
        if (fixes.isEmpty()) {
            return;
        }

        AABB searchArea = createDropSearchArea(fixes);
        List<ItemEntity> plainDrops = new ArrayList<>(world.getEntitiesOfClass(
                ItemEntity.class,
                searchArea,
                entity -> SpawnerItemUtil.isPlainSpawnerDrop(entity.getItem())));
        Set<Integer> usedDropIndexes = new HashSet<>();
        int ambiguousMatches = 0;
        int fallbackCreates = 0;

        for (PendingSpawnerDropFix fix : fixes) {
            MatchResult match = findBestPlainDropMatch(plainDrops, usedDropIndexes, fix.pos());
            if (match.candidateCount() > 1) {
                ambiguousMatches++;
            }

            if (match.dropIndex() >= 0) {
                ItemEntity plainDrop = plainDrops.get(match.dropIndex());
                ItemStack existing = plainDrop.getItem();
                plainDrop.setItem(createTypedSpawnerDrop(fix.entityType(), existing.getCount()));
                usedDropIndexes.add(match.dropIndex());
                continue;
            }

            if (fix.attemptsLeft() > 0) {
                PENDING_SPAWNER_DROP_FIXES.add(fix.nextAttempt());
                continue;
            }

            Block.popResource(world, fix.pos(), createTypedSpawnerDrop(fix.entityType(), 1));
            fallbackCreates++;
        }

        if (ambiguousMatches > 0 || fallbackCreates > 0) {
            LOGGER.debug(
                    "{}Spawner drop resolver in {} processed {} fixes with {} ambiguous matches and {} fallback creates.",
                    LOG_PREFIX,
                    world.dimension(),
                    fixes.size(),
                    ambiguousMatches,
                    fallbackCreates
            );
        }
    }

    private static AABB createDropSearchArea(List<PendingSpawnerDropFix> fixes) {
        BlockPos firstPos = fixes.get(0).pos();
        AABB area = AABB.ofSize(Vec3.atCenterOf(firstPos), 1.0D, 1.0D, 1.0D);

        for (int index = 1; index < fixes.size(); index++) {
            area = area.minmax(AABB.ofSize(Vec3.atCenterOf(fixes.get(index).pos()), 1.0D, 1.0D, 1.0D));
        }

        return area.inflate(SPAWNER_DROP_MATCH_RANGE);
    }

    private static MatchResult findBestPlainDropMatch(List<ItemEntity> plainDrops, Set<Integer> usedDropIndexes, BlockPos pos) {
        Vec3 center = Vec3.atCenterOf(pos);
        int bestIndex = -1;
        int candidateCount = 0;
        double closestPlainDropDistance = Double.MAX_VALUE;

        for (int index = 0; index < plainDrops.size(); index++) {
            if (usedDropIndexes.contains(index)) {
                continue;
            }

            ItemEntity itemEntity = plainDrops.get(index);
            if (!itemEntity.isAlive()) {
                continue;
            }

            double distanceToCenter = itemEntity.position().distanceToSqr(center);
            if (distanceToCenter > SPAWNER_DROP_MATCH_RANGE_SQR) {
                continue;
            }

            candidateCount++;
            if (distanceToCenter < closestPlainDropDistance) {
                bestIndex = index;
                closestPlainDropDistance = distanceToCenter;
            }
        }

        return new MatchResult(bestIndex, candidateCount);
    }

    private static ItemStack createTypedSpawnerDrop(EntityType<?> entityType, int amount) {
        return SpawnerItemUtil.createSpawnerItem(entityType, amount);
    }

    public static Optional<ItemStack> getDirectTypedSpawnerDrop(
            ServerLevel world,
            BlockPos pos,
            BlockEntity blockEntity,
            Player player,
            ItemStack tool
    ) {
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return Optional.empty();
        }

        if (!PermissionHelper.canMineSpawner(serverPlayer, config.useLuckPerms)) {
            return Optional.empty();
        }

        boolean hasSilkTouch = hasSilkTouch(serverPlayer);
        if (!hasSilkTouch && !PermissionHelper.canBypassSilk(serverPlayer, config)) {
            return Optional.empty();
        }

        CachedSpawnerKey key = new CachedSpawnerKey(world, pos);
        EntityType<?> entityType = BROKEN_SPAWNER_TYPES.get(key);
        if (entityType == null) {
            entityType = resolveSpawnerType(world, pos, blockEntity, key, "direct_drop").orElse(null);
        }

        if (entityType == null) {
            return Optional.empty();
        }

        return Optional.of(createTypedSpawnerDrop(entityType, 1));
    }

    private static Optional<EntityType<?>> readSpawnerType(Level world, BlockPos pos, BlockEntity blockEntity) {
        if (blockEntity == null) {
            return Optional.empty();
        }

        if (blockEntity instanceof SpawnerBlockEntity spawnerBlockEntity) {
            var nextSpawnData = ((BaseSpawnerAccessor) spawnerBlockEntity.getSpawner()).getspawners$getNextSpawnData();
            if (nextSpawnData != null) {
                Optional<EntityType<?>> nextSpawnType = readEntityTypeFromSpawnData(nextSpawnData);
                if (nextSpawnType.isPresent()) {
                    return nextSpawnType;
                }
            }

            var displayEntity = spawnerBlockEntity.getSpawner().getOrCreateDisplayEntity(world, pos);
            if (displayEntity != null) {
                return Optional.of(displayEntity.getType());
            }
        }

        var nbt = blockEntity.saveWithFullMetadata(world.registryAccess());
        return SpawnerItemUtil.readEntityTypeFromBlockEntityNbt(nbt);
    }

    private static Optional<EntityType<?>> readEntityTypeFromSpawnData(net.minecraft.world.level.SpawnData spawnData) {
        if (spawnData == null) {
            return Optional.empty();
        }

        String rawId = spawnData.getEntityToSpawn().getString("id").orElse("");
        if (rawId.isBlank()) {
            return Optional.empty();
        }

        var entityId = net.minecraft.resources.Identifier.tryParse(rawId);
        if (entityId == null || !BuiltInRegistries.ENTITY_TYPE.containsKey(entityId)) {
            return Optional.empty();
        }

        return Optional.of(BuiltInRegistries.ENTITY_TYPE.getValue(entityId));
    }

    private static Optional<EntityType<?>> resolveSpawnerType(Level world, BlockPos pos, BlockEntity blockEntity, CachedSpawnerKey key, String phase) {
        EntityType<?> knownType = KNOWN_SPAWNER_TYPES.get(key);
        EntityType<?> pendingHint = PENDING_PLACEMENT_TYPE_HINTS.get(key);
        return resolveSpawnerType(world, pos, blockEntity, key, knownType, pendingHint, phase);
    }

    private static Optional<EntityType<?>> resolveSpawnerType(
            Level world,
            BlockPos pos,
            BlockEntity blockEntity,
            CachedSpawnerKey key,
            EntityType<?> knownType,
            EntityType<?> pendingHint,
            String phase
    ) {
        Optional<EntityType<?>> blockEntityType = readSpawnerType(world, pos, blockEntity);
        if (blockEntityType.isPresent()) {
            KNOWN_SPAWNER_TYPES.put(key, blockEntityType.get());
            return blockEntityType;
        }

        if (knownType != null) {
            LOGGER.debug("{}Recovered missing spawner type from cache at {} in {} during {}.", LOG_PREFIX, pos, world.dimension(), phase);
            return Optional.of(knownType);
        }

        if (pendingHint != null) {
            LOGGER.debug("{}Recovered missing spawner type from pending placement hint at {} in {} during {}.", LOG_PREFIX, pos, world.dimension(), phase);
            return Optional.of(pendingHint);
        }

        LOGGER.debug("{}Could not resolve spawner type at {} in {} during {}. Skipping typed spawner conversion for this break.", LOG_PREFIX, pos, world.dimension(), phase);
        return Optional.empty();
    }

    private static void cachePendingPlacementHints(Level world, BlockPos primaryPos, BlockPos secondaryPos, EntityType<?> entityType) {
        PENDING_PLACEMENT_TYPE_HINTS.put(new CachedSpawnerKey(world, primaryPos), entityType);
        PENDING_PLACEMENT_TYPE_HINTS.put(new CachedSpawnerKey(world, secondaryPos), entityType);
    }

    private static void clearPendingPlacementHints(PendingPlacement pending) {
        PENDING_PLACEMENT_TYPE_HINTS.remove(new CachedSpawnerKey(pending.worldKey(), pending.primaryPos()));
        PENDING_PLACEMENT_TYPE_HINTS.remove(new CachedSpawnerKey(pending.worldKey(), pending.secondaryPos()));
    }

    private static boolean hasSilkTouch(ServerPlayer player) {
        ItemStack tool = player.getMainHandItem();
        if (tool.isEmpty()) {
            return false;
        }

        var enchantRegistry = player.level().registryAccess().lookupOrThrow(Registries.ENCHANTMENT);
        var silkHolder = enchantRegistry.getOrThrow(Enchantments.SILK_TOUCH);
        return EnchantmentHelper.getItemEnchantmentLevel(silkHolder, tool) > 0;
    }

    private record CachedSpawnerKey(ResourceKey<Level> worldKey, BlockPos pos) {
        private CachedSpawnerKey(Level world, BlockPos pos) {
            this(world.dimension(), pos.immutable());
        }
    }

    private record PendingPlacement(ResourceKey<Level> worldKey, BlockPos primaryPos, BlockPos secondaryPos, EntityType<?> entityType, int attemptsLeft) {
        private PendingPlacement nextAttempt() {
            return new PendingPlacement(worldKey, primaryPos, secondaryPos, entityType, attemptsLeft - 1);
        }
    }

    private record PendingSpawnerDropFix(ResourceKey<Level> worldKey, BlockPos pos, EntityType<?> entityType, int attemptsLeft) {
        private PendingSpawnerDropFix nextAttempt() {
            return new PendingSpawnerDropFix(worldKey, pos, entityType, attemptsLeft - 1);
        }
    }

    private record MatchResult(int dropIndex, int candidateCount) {
    }

}
