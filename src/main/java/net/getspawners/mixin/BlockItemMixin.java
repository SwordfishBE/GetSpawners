package net.getspawners.mixin;

import net.getspawners.SpawnerItemUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.SpawnerBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(BlockItem.class)
public class BlockItemMixin {
    @Inject(method = "updateBlockEntityComponents", at = @At("TAIL"))
    private static void getspawners$applyPlacedType(Level level, BlockPos pos, ItemStack stack, CallbackInfo ci) {
        // This hook runs only after a successful placement, before the held stack is consumed.
        if (!(level instanceof ServerLevel world) || !stack.is(Items.SPAWNER)
                || !(world.getBlockEntity(pos) instanceof SpawnerBlockEntity spawner)) {
            return;
        }
        SpawnerItemUtil.readEntityTypeFromSpawnerItem(stack).ifPresent(type -> {
            spawner.setEntityId(type, world.getRandom());
            spawner.setChanged();
            var state = world.getBlockState(pos);
            world.sendBlockUpdated(pos, state, state, 3);
        });
    }
}
