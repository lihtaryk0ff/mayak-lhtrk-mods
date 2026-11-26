package lhtrk_sndct.access.mixin;

import lhtrk_sndct.access.LhtrkAccessMod;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ItemEntity.class)
public abstract class ItemEntityMixin {
    /**
     * Prevents guests from picking up items without modifying the item entity's global pickup delay.
     */
    @Inject(method = "playerTouch", at = @At("HEAD"), cancellable = true)
    private void lhtrk$preventGuestPickup(Player player, CallbackInfo ci) {
        if (player instanceof ServerPlayer serverPlayer && !LhtrkAccessMod.isFullPlayer(serverPlayer)) {
            ci.cancel();
        }
    }
}
