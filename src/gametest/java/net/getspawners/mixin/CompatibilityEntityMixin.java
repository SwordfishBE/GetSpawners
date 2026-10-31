package net.getspawners.mixin;

import net.getspawners.CompatibilityTickProbe;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Entity.class)
public class CompatibilityEntityMixin {
    @Inject(method = "baseTick", at = @At("HEAD"))
    private void getspawnersTest$recordThread(CallbackInfo ci) {
        Entity entity = (Entity) (Object) this;
        if (entity.entityTags().contains(CompatibilityTickProbe.TAG)) {
            CompatibilityTickProbe.record();
        }
    }
}
