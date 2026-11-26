package lhtrk_sndct.access.mixin;

import lhtrk_sndct.access.LhtrkAccessMod;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.FarmlandBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(FarmlandBlock.class)
public abstract class FarmlandBlockMixin {
    /**
     * Prevents guests from trampling farmland when jumping or falling on crops.
     */
    @Inject(method = "fallOn", at = @At("HEAD"), cancellable = true)
    private void lhtrk$preventGuestTrample(Level level, BlockState state, BlockPos pos, Entity entity, double fallDistance, CallbackInfo ci) {
        if (!level.isClientSide() && entity instanceof ServerPlayer serverPlayer && !LhtrkAccessMod.isFullPlayer(serverPlayer)) {
            ci.cancel();
        }
    }
}
