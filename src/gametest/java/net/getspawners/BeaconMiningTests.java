package net.getspawners;

import com.mojang.authlib.GameProfile;
import io.netty.channel.embedded.EmbeddedChannel;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket.Action;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.TypedEntityData;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BeaconBlockEntity;
import net.minecraft.world.level.block.entity.SpawnerBlockEntity;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class BeaconMiningTests {
    @GameTest(structure = "getspawners-test:beacon_mining", skyAccess = true, maxTicks = 1200)
    public void diamondHasteTwoEfficiencyFive(GameTestHelper helper) {
        new MiningRun(helper, Items.DIAMOND_PICKAXE).start();
    }

    @GameTest(structure = "getspawners-test:beacon_mining", skyAccess = true, maxTicks = 1200)
    public void netheriteHasteTwoEfficiencyFive(GameTestHelper helper) {
        new MiningRun(helper, Items.NETHERITE_PICKAXE).start();
    }

    private static final class MiningRun {
        private static final int BLOCK_COUNT = 128;
        private static final BlockPos BEACON = new BlockPos(5, 4, 5);
        private final GameTestHelper helper;
        private final Item toolItem;
        private final Map<EntityType<?>, Integer> expected = new HashMap<>();
        private ServerPlayer player;
        private EmbeddedChannel channel;
        private BeaconBlockEntity beacon;
        private ItemStack tool;
        private BlockPos target;
        private long startedAt;
        private long finishedAt = -1;
        private int ticksToBreak;
        private int mined;
        private int sequence;
        private boolean finished;

        private MiningRun(GameTestHelper helper, Item toolItem) {
            this.helper = helper;
            this.toolItem = toolItem;
        }

        private void start() {
            for (int x = 0; x < 18; x++) {
                for (int z = 0; z < 12; z++) {
                    helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
                }
            }
            for (int depth = 1; depth <= 4; depth++) {
                for (int x = -depth; x <= depth; x++) {
                    for (int z = -depth; z <= depth; z++) {
                        helper.setBlock(BEACON.offset(x, -depth, z), Blocks.IRON_BLOCK);
                    }
                }
            }
            helper.setBlock(BEACON, Blocks.BEACON);
            beacon = helper.getBlockEntity(BEACON, BeaconBlockEntity.class);
            CompoundTag effects = new CompoundTag();
            effects.putString("primary_effect", "minecraft:haste");
            effects.putString("secondary_effect", "minecraft:haste");
            TypedEntityData.of(beacon.getType(), effects).loadInto(beacon, helper.getLevel().registryAccess());

            // Use a real survival ServerPlayer; Minecraft's in-level test helper overrides gameMode to creative.
            var world = helper.getLevel();
            var profile = new GameProfile(UUID.randomUUID(), toolItem == Items.DIAMOND_PICKAXE ? "haste_diamond" : "haste_netherite");
            var cookie = CommonListenerCookie.createInitial(profile, false);
            player = new ServerPlayer(world.getServer(), world, profile, cookie.clientInformation());
            var connection = new Connection(PacketFlow.SERVERBOUND);
            channel = new EmbeddedChannel(connection);
            world.getServer().getPlayerList().placeNewPlayer(connection, player, cookie);
            player.setGameMode(GameType.SURVIVAL);
            var stand = helper.absolutePos(new BlockPos(13, 1, 1));
            player.setPos(stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5);
            player.setOnGround(true);
            tool = new ItemStack(toolItem);
            var enchantments = world.registryAccess().lookupOrThrow(Registries.ENCHANTMENT);
            tool.enchant(enchantments.getOrThrow(Enchantments.EFFICIENCY), 5);
            tool.enchant(enchantments.getOrThrow(Enchantments.SILK_TOUCH), 1);
            player.setItemInHand(InteractionHand.MAIN_HAND, tool);
            helper.onEachTick(() -> {
                try {
                    tick();
                } catch (RuntimeException | AssertionError failure) {
                    finished = true;
                    throw failure;
                }
            });
        }

        private void tick() {
            if (finished) {
                return;
            }
            // Embedded connections are not in the network listener's tick list. Tick the player
            // packet listener so living/equipment updates apply the Efficiency attribute normally.
            player.connection.tick();
            var haste = player.getEffect(MobEffects.HASTE);
            if (haste == null || haste.getAmplifier() != 1) {
                helper.assertTrue(mined == 0 && helper.getTick() < 200, "Actual beacon must grant and maintain Haste II");
                return;
            }
            helper.assertFalse(player.isCreative(), "Mining must run in survival");
            helper.assertFalse(beacon.getBeamSections().isEmpty(), "Beacon beam must be active");
            helper.assertTrue(beacon.saveWithFullMetadata(helper.getLevel().registryAccess()).getInt("Levels").orElse(0) == 4,
                    "Beacon must have four valid pyramid layers");
            player.setOnGround(true);

            if (mined == BLOCK_COUNT) {
                if (finishedAt < 0) {
                    finishedAt = helper.getTick();
                    assertDrops();
                } else if (helper.getTick() - finishedAt >= 30) {
                    assertDrops();
                    helper.assertTrue(tool.getDamageValue() == BLOCK_COUNT, "Each survival break must consume one durability");
                    GetSpawnersMod.LOGGER.info("Beacon mining regression passed: {}, Haste II, Efficiency V, {} spawners, {} per mob type, {} ticks per block, no missing/extra drops or XP",
                            toolItem, BLOCK_COUNT, BLOCK_COUNT / 4, ticksToBreak);
                    finished = true;
                    player.connection.disconnect(Component.literal("Beacon regression complete"));
                    helper.getLevel().getServer().getPlayerList().remove(player);
                    channel.finishAndReleaseAll();
                    helper.succeed();
                }
                return;
            }

            if (target == null) {
                var relative = new BlockPos(12 + mined % 4, 1, 3 + (mined / 4) % 2);
                helper.setBlock(relative, Blocks.SPAWNER);
                EntityType<?> type = switch (mined % 4) {
                    case 0 -> EntityTypes.ZOMBIE;
                    case 1 -> EntityTypes.SKELETON;
                    case 2 -> EntityTypes.SPIDER;
                    default -> BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.withDefaultNamespace("sulfur_cube"));
                };
                helper.assertTrue(type != null, "All test mob types must be registered");
                helper.getBlockEntity(relative, SpawnerBlockEntity.class).setEntityId(type, helper.getLevel().getRandom());
                var nextTarget = helper.absolutePos(relative);
                var efficiency = helper.getLevel().registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.EFFICIENCY);
                helper.assertTrue(EnchantmentHelper.getItemEnchantmentLevel(efficiency, tool) == 5, "Pickaxe must have Efficiency V");
                float progress = helper.getLevel().getBlockState(nextTarget).getDestroyProgress(player, helper.getLevel(), nextTarget);
                helper.assertTrue(progress > 0.30F && progress < 1.0F, "Haste II and Efficiency V must affect real survival mining speed: " + progress);
                ticksToBreak = (int) Math.ceil(1.0 / progress);
                expected.merge(type, 1, Integer::sum);
                target = nextTarget;
                startedAt = helper.getTick();
                action(Action.START_DESTROY_BLOCK);
            } else if (helper.getTick() - startedAt >= ticksToBreak - 1) {
                action(Action.STOP_DESTROY_BLOCK);
                helper.assertTrue(helper.getLevel().getBlockState(target).isAir(), "Server mining action must remove the spawner; mined=" + mined);
                mined++;
                target = null;
                assertDrops();
            }
        }

        private void action(Action action) {
            player.gameMode.handleBlockBreakAction(target, action, Direction.NORTH, helper.getLevel().getMaxY(), ++sequence);
        }

        private void assertDrops() {
            Map<EntityType<?>, Integer> actual = new HashMap<>();
            for (ItemEntity entity : helper.getLevel().getEntitiesOfClass(ItemEntity.class, helper.getBounds().inflate(1))) {
                count(entity.getItem(), actual);
            }
            for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
                count(player.getInventory().getItem(slot), actual);
            }
            helper.assertTrue(actual.equals(expected), "Every mined spawner must survive with its exact type and count: expected " + expected + ", actual " + actual);
            helper.assertEntityNotPresent(EntityTypes.EXPERIENCE_ORB);
        }

        private void count(ItemStack stack, Map<EntityType<?>, Integer> counts) {
            if (stack.is(Items.SPAWNER)) {
                var type = SpawnerItemUtil.readEntityTypeFromSpawnerItem(stack);
                helper.assertTrue(type.isPresent(), "Every dropped spawner must retain its mob type");
                counts.merge(type.orElseThrow(), stack.getCount(), Integer::sum);
            }
        }
    }
}
