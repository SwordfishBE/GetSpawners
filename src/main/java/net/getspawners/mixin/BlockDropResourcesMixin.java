package net.getspawners.mixin;

import net.getspawners.GetSpawnersMod;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemInstance;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;

@Mixin(Block.class)
public class BlockDropResourcesMixin {
    @Inject(
            method = "getDrops(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/entity/BlockEntity;Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/item/ItemInstance;)Ljava/util/List;",
            at = @At("RETURN"),
            cancellable = true
    )
    private static void getspawners$replaceSpawnerDrops(
            BlockState state,
            ServerLevel world,
            BlockPos pos,
            BlockEntity blockEntity,
            Entity entity,
            ItemInstance tool,
            CallbackInfoReturnable<List<ItemStack>> cir
    ) {
        if (state.getBlock() != Blocks.SPAWNER || !(entity instanceof Player player) || !(tool instanceof ItemStack itemStack)) {
            return;
        }

        var typedDrop = GetSpawnersMod.getDirectTypedSpawnerDrop(world, pos, blockEntity, player, itemStack).orElse(null);
        if (typedDrop == null) {
            return;
        }

        List<ItemStack> originalDrops = cir.getReturnValue();
        List<ItemStack> updatedDrops = new ArrayList<>(originalDrops);
        boolean replacedSpawner = false;
        for (int index = 0; index < updatedDrops.size(); index++) {
            ItemStack drop = updatedDrops.get(index);
            if (drop.getItem() == net.minecraft.world.item.Items.SPAWNER) {
                updatedDrops.set(index, typedDrop.copyWithCount(drop.getCount()));
                replacedSpawner = true;
            }
        }

        if (!replacedSpawner) {
            updatedDrops.add(typedDrop);
        }

        cir.setReturnValue(updatedDrops);
    }
}
