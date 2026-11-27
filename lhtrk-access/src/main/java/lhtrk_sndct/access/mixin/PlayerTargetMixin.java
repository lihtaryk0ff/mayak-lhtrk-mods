package lhtrk_sndct.access.mixin;

import lhtrk_sndct.access.LhtrkAccessMod;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Player.class)
public abstract class PlayerTargetMixin {
    /**
     * Makes guests invisible as enemies to mob targeting AI (same as creative/spectator).
     */
    @Inject(method = "canBeSeenAsEnemy", at = @At("HEAD"), cancellable = true)
    private void lhtrk$hideGuestFromMobs(CallbackInfoReturnable<Boolean> cir) {
        if ((Object) this instanceof ServerPlayer serverPlayer && !LhtrkAccessMod.isFullPlayer(serverPlayer)) {
            cir.setReturnValue(false);
        }
    }
}
