package net.getspawners.mixin;

import net.getspawners.GetSpawnersMod;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.SpawnerBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(SpawnerBlock.class)
public class SpawnerBlockMixin {
    @Redirect(
            method = "spawnAfterBreak(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/item/ItemStack;Z)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/block/SpawnerBlock;popExperience(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/core/BlockPos;I)V"
            )
    )
    private void getspawners$suppressSpawnerExperience(SpawnerBlock instance, ServerLevel level, BlockPos pos, int amount) {
        if (!GetSpawnersMod.shouldSuppressExperience(level, pos)) {
            ((BlockInvoker) instance).getspawners$callPopExperience(level, pos, amount);
        }
    }
}
