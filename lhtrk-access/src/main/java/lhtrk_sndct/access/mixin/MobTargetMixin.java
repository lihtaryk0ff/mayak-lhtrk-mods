package lhtrk_sndct.access.mixin;

import lhtrk_sndct.access.LhtrkAccessMod;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Mob.class)
public abstract class MobTargetMixin {
    /**
     * Prevents any mob from setting a guest as its target.
     */
    @Inject(method = "setTarget", at = @At("HEAD"), cancellable = true)
    private void lhtrk$preventGuestTarget(LivingEntity target, CallbackInfo ci) {
        if (target instanceof ServerPlayer serverPlayer && !LhtrkAccessMod.isFullPlayer(serverPlayer)) {
            ci.cancel();
        }
    }
}
