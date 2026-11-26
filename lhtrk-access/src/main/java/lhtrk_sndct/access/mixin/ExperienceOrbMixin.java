package lhtrk_sndct.access.mixin;

import lhtrk_sndct.access.LhtrkAccessMod;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ExperienceOrb.class)
public abstract class ExperienceOrbMixin {
    /**
     * Prevents guests from picking up experience orbs meant for full players nearby.
     */
    @Inject(method = "playerTouch", at = @At("HEAD"), cancellable = true)
    private void lhtrk$preventGuestXpPickup(Player player, CallbackInfo ci) {
        if (player instanceof ServerPlayer serverPlayer && !LhtrkAccessMod.isFullPlayer(serverPlayer)) {
            ci.cancel();
        }
    }
}
