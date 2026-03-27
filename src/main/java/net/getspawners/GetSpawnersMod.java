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
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.item.ItemEntity;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

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

    private static MutableComponent prefixed(String message) {
        return Component.literal(PREFIX + message);
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
                            .then(argument("player", EntityArgument.player())
                                    .then(argument("type", StringArgumentType.word())
                                            .suggests(GetSpawnersMod::suggestTypes)
                                            .executes(context -> executeGive(context, 1))
                                            .then(argument("amount", IntegerArgumentType.integer(1, 64))
                                                    .executes(context -> executeGive(context, IntegerArgumentType.getInteger(context, "amount")))))));

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
        context.getSource().sendSuccess(() -> prefixed("Config reloaded."), false);
        LOGGER.info("{}Config reloaded by {}", PREFIX, context.getSource().getTextName());
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
                serverPlayer.sendSystemMessage(prefixed("You do not have permission to mine spawners.").withStyle(ChatFormatting.RED), true);
                return false;
            }

            boolean hasSilkTouch = hasSilkTouch(serverPlayer);
            if (!hasSilkTouch && !PermissionHelper.canBypassSilk(serverPlayer, config.useLuckPerms)) {
                BROKEN_SPAWNER_TYPES.remove(key);
                return true;
            }

            EntityType<?> entityType = readSpawnerType(world, pos, blockEntity).orElse(EntityType.PIG);
            BROKEN_SPAWNER_TYPES.put(key, entityType);
            return true;
        });

        PlayerBlockBreakEvents.AFTER.register((world, player, pos, state, blockEntity) -> {
            if (world.isClientSide() || state.getBlock() != Blocks.SPAWNER || !(player instanceof ServerPlayer serverPlayer)) {
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
            PENDING_SPAWNER_DROP_FIXES.add(new PendingSpawnerDropFix(world.dimension(), pos.immutable(), entityType, 8));

            removeNearbyExperienceOrbs(world, pos, 3.0D);
            PENDING_XP_CLEANUPS.add(new PendingXpCleanup(world.dimension(), pos.immutable(), 12));
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
                ItemStack refund = stack.copyWithCount(1);
                if (!serverPlayer.isCreative() && !stack.isEmpty()) {
                    stack.shrink(1);
                }
                serverPlayer.drop(refund, false, false);
                serverPlayer.sendSystemMessage(prefixed("You do not have permission to place spawners.").withStyle(ChatFormatting.RED), true);
                return InteractionResult.FAIL;
            }

            Optional<EntityType<?>> itemType = SpawnerItemUtil.readEntityTypeFromSpawnerItem(stack);
            if (itemType.isPresent()) {
                BlockPlaceContext placeContext = new BlockPlaceContext(player, hand, stack, hitResult);
                BlockPos targetPos = placeContext.getClickedPos().immutable();
                PENDING_PLACEMENTS.add(new PendingPlacement(world.dimension(), targetPos, itemType.get(), 4));
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
        ServerLevel world = server.getLevel(pending.worldKey());
        if (world == null) {
            return true;
        }

        BlockState state = world.getBlockState(pending.pos());
        if (state.getBlock() != Blocks.SPAWNER) {
            return false;
        }

        BlockEntity blockEntity = world.getBlockEntity(pending.pos());
        if (!(blockEntity instanceof SpawnerBlockEntity spawner)) {
            return false;
        }

        spawner.setEntityId(pending.entityType(), world.getRandom());
        spawner.setChanged();
        world.sendBlockUpdated(pending.pos(), state, state, 3);
        return true;
    }

    private static void tryRunSpawnerDropFix(MinecraftServer server, PendingSpawnerDropFix fix) {
        ServerLevel world = server.getLevel(fix.worldKey());
        if (world == null) {
            return;
        }

        normalizeSpawnerDrops(world, fix.pos(), fix.entityType(), false);
    }

    private static void tryRunXpCleanup(MinecraftServer server, PendingXpCleanup cleanup) {
        ServerLevel world = server.getLevel(cleanup.worldKey());
        if (world == null) {
            return;
        }

        removeNearbyExperienceOrbs(world, cleanup.pos(), 6.0D);
    }

    private static boolean normalizeSpawnerDrops(Level world, BlockPos pos, EntityType<?> entityType, boolean allowCreate) {
        ItemStack typedDrop = SpawnerItemUtil.createSpawnerItem(entityType, 1);
        AABB area = new AABB(pos).inflate(1.5D);

        ItemEntity chosen = null;
        for (ItemEntity itemEntity : world.getEntitiesOfClass(ItemEntity.class, area, entity -> entity.getItem().getItem() == Items.SPAWNER)) {
            if (chosen == null) {
                chosen = itemEntity;
            } else {
                itemEntity.discard();
            }
        }

        if (chosen != null) {
            chosen.setItem(typedDrop);
            return true;
        }

        if (!allowCreate) {
            return false;
        }

        Block.popResource(world, pos, typedDrop);
        return true;
    }

    private static Optional<EntityType<?>> readSpawnerType(Level world, BlockPos pos, BlockEntity blockEntity) {
        if (blockEntity == null) {
            return Optional.empty();
        }

        var nbt = blockEntity.saveWithFullMetadata(world.registryAccess());
        return SpawnerItemUtil.readEntityTypeFromBlockEntityNbt(nbt);
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

    private static void removeNearbyExperienceOrbs(Level world, BlockPos pos, double radius) {
        AABB area = new AABB(pos).inflate(radius);
        for (ExperienceOrb orb : world.getEntitiesOfClass(ExperienceOrb.class, area, entity -> true)) {
            orb.discard();
        }
    }

    private record CachedSpawnerKey(ResourceKey<Level> worldKey, BlockPos pos) {
        private CachedSpawnerKey(Level world, BlockPos pos) {
            this(world.dimension(), pos.immutable());
        }
    }

    private record PendingPlacement(ResourceKey<Level> worldKey, BlockPos pos, EntityType<?> entityType, int attemptsLeft) {
        private PendingPlacement nextAttempt() {
            return new PendingPlacement(worldKey, pos, entityType, attemptsLeft - 1);
        }
    }

    private record PendingSpawnerDropFix(ResourceKey<Level> worldKey, BlockPos pos, EntityType<?> entityType, int attemptsLeft) {
        private PendingSpawnerDropFix nextAttempt() {
            return new PendingSpawnerDropFix(worldKey, pos, entityType, attemptsLeft - 1);
        }
    }

    private record PendingXpCleanup(ResourceKey<Level> worldKey, BlockPos pos, int attemptsLeft) {
        private PendingXpCleanup nextAttempt() {
            return new PendingXpCleanup(worldKey, pos, attemptsLeft - 1);
        }
    }
}
