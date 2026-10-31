package net.getspawners;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.List;

public class OptimizationCompatibilityTests {
    @GameTest(structure = "getspawners-test:beacon_mining", skyAccess = true, maxTicks = 300)
    public void mobsTickWhileSpawnerRegressionsRun(GameTestHelper helper) {
        CompatibilityTickProbe.serverThread = Thread.currentThread();
        CompatibilityTickProbe.WORKER_TICKS.set(0);
        CompatibilityTickProbe.THREADS.clear();
        for (int x = 0; x < 18; x++) {
            for (int z = 0; z < 12; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
            }
        }
        List<Mob> mobs = new ArrayList<>();
        for (int i = 0; i < 64; i++) {
            BlockPos pos = new BlockPos(1 + i % 16, 1, 1 + i / 16 * 2);
            Mob mob = i % 2 == 0 ? helper.spawn(EntityTypes.COW, pos) : helper.spawn(EntityTypes.PIG, pos);
            mob.addTag(CompatibilityTickProbe.TAG);
            mob.setPersistenceRequired();
            mob.setPermanentlyInvulnerable(true);
            mobs.add(mob);
        }
        helper.runAfterDelay(120, () -> {
            helper.assertTrue(mobs.stream().allMatch(mob -> mob.isAlive() && mob.tickCount >= 50),
                    "All background mobs must actually tick while spawner tests run");
            if (FabricLoader.getInstance().isModLoaded("async")) {
                helper.assertTrue(CompatibilityTickProbe.WORKER_TICKS.get() > 0,
                        "Async must execute real mob ticks on worker threads, not merely be loaded");
            }
            GetSpawnersMod.LOGGER.info("Compatibility mob probe: 64 live mobs, worker ticks={}, threads={}",
                    CompatibilityTickProbe.WORKER_TICKS.get(), CompatibilityTickProbe.THREADS);
            mobs.forEach(Mob::discard);
            helper.succeed();
        });
    }
}
