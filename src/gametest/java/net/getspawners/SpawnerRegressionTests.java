package net.getspawners;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.TypedEntityData;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.SpawnerBlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

public class SpawnerRegressionTests {
    private static final BlockPos POS = new BlockPos(1, 1, 1);

    @GameTest
    public void placementIsImmediate(GameTestHelper helper) {
        ServerPlayer player = player(helper);
        ItemStack stack = SpawnerItemUtil.createSpawnerItem(EntityTypes.ZOMBIE, 2);
        place(helper, player, stack);
        assertType(helper, EntityTypes.ZOMBIE);
        helper.assertTrue(stack.getCount() == 1, "Successful placement consumes exactly one spawner");
        helper.succeed();
    }

    @GameTest
    public void blockedPlacementDoesNotRetypeExistingSpawner(GameTestHelper helper) {
        helper.setBlock(POS, Blocks.SPAWNER);
        helper.getBlockEntity(POS, SpawnerBlockEntity.class).setEntityId(EntityTypes.SKELETON, helper.getLevel().getRandom());
        ItemStack stack = SpawnerItemUtil.createSpawnerItem(EntityTypes.ZOMBIE, 2);
        place(helper, player(helper), stack);
        helper.assertTrue(stack.getCount() == 2, "Failed placement must not consume the stack");
        helper.runAfterDelay(6, () -> {
            assertType(helper, EntityTypes.SKELETON);
            helper.succeed();
        });
    }

    @GameTest
    public void lastDurabilityHitKeepsTypeAndSuppressesXp(GameTestHelper helper) {
        helper.setBlock(POS, Blocks.SPAWNER);
        var world = helper.getLevel();
        var pos = helper.absolutePos(POS);
        var state = world.getBlockState(pos);
        var spawner = helper.getBlockEntity(POS, SpawnerBlockEntity.class);
        spawner.setEntityId(EntityTypes.SKELETON, world.getRandom());
        ServerPlayer player = player(helper);
        ItemStack tool = silkTool(helper);
        tool.setDamageValue(tool.getMaxDamage() - 1);
        player.setItemInHand(InteractionHand.MAIN_HAND, tool);
        PlayerBlockBreakEvents.BEFORE.invoker().beforeBlockBreak(world, player, pos, state, spawner);
        ItemStack toolBeforeBreak = tool.copy();
        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        var drops = Block.getDrops(state, world, pos, spawner, player, toolBeforeBreak);
        helper.assertTrue(drops.size() == 1 && SpawnerItemUtil.isSpawnerItemOfType(drops.getFirst(), EntityTypes.SKELETON),
                "Last tool hit must produce exactly one correctly typed spawner");
        state.spawnAfterBreak(world, pos, toolBeforeBreak, true);
        helper.assertEntityNotPresent(EntityTypes.EXPERIENCE_ORB);
        PlayerBlockBreakEvents.AFTER.invoker().afterBlockBreak(world, player, pos, state, spawner);
        helper.assertFalse(GetSpawnersMod.shouldSuppressExperience(world, pos), "XP state must be consumed");
        helper.succeed();
    }

    @GameTest
    public void repeatedPlacementAndBreakNeverMixTypes(GameTestHelper helper) {
        ServerPlayer player = player(helper);
        var world = helper.getLevel();
        var pos = helper.absolutePos(POS);
        for (int i = 0; i < 32; i++) {
            EntityType<?> type = i % 2 == 0 ? EntityTypes.ZOMBIE : EntityTypes.SKELETON;
            place(helper, player, SpawnerItemUtil.createSpawnerItem(type, 1));
            assertType(helper, type);
            ItemStack tool = silkTool(helper);
            player.setItemInHand(InteractionHand.MAIN_HAND, tool);
            var state = world.getBlockState(pos);
            var spawner = world.getBlockEntity(pos);
            PlayerBlockBreakEvents.BEFORE.invoker().beforeBlockBreak(world, player, pos, state, spawner);
            var drops = Block.getDrops(state, world, pos, spawner, player, tool);
            helper.assertTrue(drops.size() == 1 && SpawnerItemUtil.isSpawnerItemOfType(drops.getFirst(), type), "Rapid break must keep its own type");
            world.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
            PlayerBlockBreakEvents.AFTER.invoker().afterBlockBreak(world, player, pos, state, spawner);
        }
        helper.runAfterDelay(10, () -> {
            helper.assertItemEntityNotPresent(Items.SPAWNER);
            helper.succeed();
        });
    }

    @GameTest
    public void ordinaryMiningStillDropsXp(GameTestHelper helper) {
        helper.setBlock(POS, Blocks.SPAWNER);
        var world = helper.getLevel();
        var pos = helper.absolutePos(POS);
        var state = world.getBlockState(pos);
        var player = player(helper);
        var tool = new ItemStack(Items.DIAMOND_PICKAXE);
        player.setItemInHand(InteractionHand.MAIN_HAND, tool);
        var drops = Block.getDrops(state, world, pos, world.getBlockEntity(pos), player, tool);
        helper.assertTrue(drops.stream().noneMatch(stack -> stack.is(Items.SPAWNER)), "Ordinary mining must not drop a spawner");
        state.spawnAfterBreak(world, pos, tool, true);
        helper.assertEntityPresent(EntityTypes.EXPERIENCE_ORB);
        helper.succeed();
    }

    @GameTest
    public void transientStateExpires(GameTestHelper helper) {
        helper.setBlock(POS, Blocks.SPAWNER);
        var world = helper.getLevel();
        var pos = helper.absolutePos(POS);
        helper.getBlockEntity(POS, SpawnerBlockEntity.class).setEntityId(EntityTypes.ZOMBIE, world.getRandom());
        var player = player(helper);
        var tool = silkTool(helper);
        player.setItemInHand(InteractionHand.MAIN_HAND, tool);
        PlayerBlockBreakEvents.BEFORE.invoker().beforeBlockBreak(world, player, pos, world.getBlockState(pos), world.getBlockEntity(pos));
        Block.getDrops(world.getBlockState(pos), world, pos, world.getBlockEntity(pos), player, tool);
        helper.runAfterDelay(2, () -> {
            helper.assertFalse(GetSpawnersMod.shouldSuppressExperience(world, pos), "Unconsumed XP state must expire after the tick");
            helper.assertTrue(GetSpawnersMod.getDirectTypedSpawnerDrop(world, pos, null, player, tool).isEmpty(), "Unconsumed type cache must expire");
            helper.succeed();
        });
    }

    @GameTest
    public void retypingPreservesSettingsAndStack(GameTestHelper helper) {
        ItemStack original = SpawnerItemUtil.createSpawnerItem(EntityTypes.ZOMBIE, 32);
        original.set(DataComponents.CUSTOM_NAME, Component.literal("Custom spawner"));
        var data = original.get(DataComponents.BLOCK_ENTITY_DATA);
        var tag = data.copyTagWithoutId();
        tag.putShort("MinSpawnDelay", (short) 77);
        original.set(DataComponents.BLOCK_ENTITY_DATA, TypedEntityData.of(data.type(), tag));
        ItemStack changed = SpawnerItemUtil.withSpawnerItemType(original, EntityTypes.SKELETON);
        helper.assertTrue(changed.getCount() == 32, "Retyping keeps stack size");
        helper.assertTrue(changed.get(DataComponents.CUSTOM_NAME).equals(original.get(DataComponents.CUSTOM_NAME)), "Retyping keeps name");
        helper.assertTrue(changed.get(DataComponents.BLOCK_ENTITY_DATA).copyTagWithoutId().getShort("MinSpawnDelay").orElse((short) 0) == 77,
                "Retyping keeps custom spawner settings");
        helper.assertTrue(SpawnerItemUtil.isSpawnerItemOfType(original, EntityTypes.ZOMBIE), "Original stack must not be mutated");
        helper.assertTrue(SpawnerItemUtil.isSpawnerItemOfType(changed, EntityTypes.SKELETON), "Retyped stack must contain requested mob");
        helper.succeed();
    }

    private static ServerPlayer player(GameTestHelper helper) {
        return (ServerPlayer) helper.makeMockServerPlayer(GameType.SURVIVAL);
    }

    @GameTest
    public void namespacedSetCommandAndConfigPermissions(GameTestHelper helper) throws Exception {
        var original = GetSpawnersMod.loadConfigForEditing();
        var edited = original.copy();
        var player = connectedPlayer(helper);
        var dispatcher = helper.getLevel().getServer().getCommands().getDispatcher();
        player.setItemInHand(InteractionHand.MAIN_HAND, SpawnerItemUtil.createSpawnerItem(EntityTypes.ZOMBIE, 16));
        try {
            edited.useLuckPerms = false;
            edited.allowEveryoneSetCommand = false;
            GetSpawnersMod.applyEditedConfig(edited);
            helper.assertFalse(PermissionHelper.canUseCommand(player.createCommandSourceStack(), "getspawners.set", edited, false),
                    "Non-OP players must not have access when the config toggle is disabled");
            edited.allowEveryoneSetCommand = true;
            GetSpawnersMod.applyEditedConfig(edited);
            int result = dispatcher.execute("gs set minecraft:skeleton", player.createCommandSourceStack());
            helper.assertTrue(result == 1, "Namespaced type must parse and execute");
            helper.assertTrue(SpawnerItemUtil.isSpawnerItemOfType(player.getMainHandItem(), EntityTypes.SKELETON)
                    && player.getMainHandItem().getCount() == 16, "Command must change the full held stack");
            edited.useLuckPerms = true;
            PermissionHelper.refreshState(edited);
            helper.assertTrue(PermissionHelper.canUseCommand(player.createCommandSourceStack(), "getspawners.set", edited, true),
                    "Missing LuckPerms must fall back to the config toggle");
        } finally {
            GetSpawnersMod.applyEditedConfig(original);
        }
        helper.succeed();
    }

    @GameTest
    public void canceledBreakClearsTransientState(GameTestHelper helper) {
        helper.setBlock(POS, Blocks.SPAWNER);
        var world = helper.getLevel();
        var pos = helper.absolutePos(POS);
        var spawner = helper.getBlockEntity(POS, SpawnerBlockEntity.class);
        spawner.setEntityId(EntityTypes.ZOMBIE, world.getRandom());
        var player = connectedPlayer(helper);
        var tool = silkTool(helper);
        player.setItemInHand(InteractionHand.MAIN_HAND, tool);
        PlayerBlockBreakEvents.BEFORE.invoker().beforeBlockBreak(world, player, pos, world.getBlockState(pos), spawner);
        PlayerBlockBreakEvents.CANCELED.invoker().onBlockBreakCanceled(world, player, pos, world.getBlockState(pos), spawner);
        helper.assertTrue(GetSpawnersMod.getDirectTypedSpawnerDrop(world, pos, null, player, tool).isEmpty(),
                "Canceled break must not leave cached type data behind");
        helper.assertFalse(GetSpawnersMod.shouldSuppressExperience(world, pos), "Canceled break must not leave XP suppression behind");
        helper.succeed();
    }

    @GameTest
    public void updateCheckerRejectsIncompatibleRelease(GameTestHelper helper) throws Exception {
        var method = ModrinthUpdateChecker.class.getDeclaredMethod("extractLatestVersion", String.class, String.class);
        method.setAccessible(true);
        String versions = """
                [{"version_number":"99.0.0","date_published":"2026-10-19T00:00:00Z",
                  "version_type":"release","loaders":["neoforge"],"game_versions":["26.3"]},
                 {"version_number":"98.0.0","date_published":"2026-10-18T00:00:00Z",
                  "version_type":"release","loaders":["fabric"],"game_versions":["26.2"]}]
                """;
        var result = (java.util.Optional<?>) method.invoke(null, versions, "26.3");
        helper.assertTrue(result.isEmpty(), "An incompatible loader or game version must never be recommended");
        helper.succeed();
    }

    private static ItemStack silkTool(GameTestHelper helper) {
        ItemStack tool = new ItemStack(Items.DIAMOND_PICKAXE);
        tool.enchant(helper.getLevel().registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.SILK_TOUCH), 1);
        return tool;
    }

    private static ServerPlayer connectedPlayer(GameTestHelper helper) {
        var player = helper.makeMockServerPlayerInLevel();
        player.setGameMode(GameType.SURVIVAL);
        helper.runBeforeTestEnd(() -> helper.getLevel().getServer().getPlayerList().remove(player));
        return player;
    }

    private static void place(GameTestHelper helper, ServerPlayer player, ItemStack stack) {
        helper.setBlock(POS.below(), Blocks.STONE);
        var world = helper.getLevel();
        var support = helper.absolutePos(POS.below());
        var hit = new BlockHitResult(Vec3.atCenterOf(support).add(0, 0.5, 0), Direction.UP, support, false);
        player.setItemInHand(InteractionHand.MAIN_HAND, stack);
        UseBlockCallback.EVENT.invoker().interact(player, world, InteractionHand.MAIN_HAND, hit);
        ((BlockItem) Items.SPAWNER).place(new BlockPlaceContext(player, InteractionHand.MAIN_HAND, stack, hit));
    }

    private static void assertType(GameTestHelper helper, EntityType<?> expected) {
        var spawner = helper.getBlockEntity(POS, SpawnerBlockEntity.class);
        helper.assertTrue(SpawnerItemUtil.readEntityTypeFromBlockEntityNbt(spawner.saveWithFullMetadata(helper.getLevel().registryAccess()))
                .filter(type -> type == expected).isPresent(), "Placed spawner must have the requested type immediately");
    }
}
