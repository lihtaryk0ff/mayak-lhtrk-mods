package lhtrk_sndct.afk.mixin;

import lhtrk_sndct.afk.LhtrkAfkMod;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stat;
import net.minecraft.stats.Stats;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerPlayer.class)
public abstract class ServerPlayerMixin {
    /**
     * Makes the player's name gray in the TAB list while AFK.
     */
    @Inject(method = "getTabListDisplayName", at = @At("HEAD"), cancellable = true)
    private void lhtrk$grayTabNameWhenAfk(CallbackInfoReturnable<Component> cir) {
        ServerPlayer self = (ServerPlayer) (Object) this;
        if (LhtrkAfkMod.isAfk(self)) {
            cir.setReturnValue(Component.literal(self.getName().getString()).withStyle(ChatFormatting.GRAY));
        }
    }

    /**
     * Prevents Stats.PLAY_TIME from incrementing while the player is AFK.
     */
    @Inject(method = "awardStat(Lnet/minecraft/stats/Stat;I)V", at = @At("HEAD"), cancellable = true)
    private void lhtrk$pausePlayTimeWhenAfk(Stat<?> stat, int increment, CallbackInfo ci) {
        ServerPlayer self = (ServerPlayer) (Object) this;
        if (LhtrkAfkMod.isAfk(self) && Stats.PLAY_TIME.equals(stat.getValue())) {
            ci.cancel();
        }
    }
}
