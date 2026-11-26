package lhtrk_sndct.admin;

import lhtrk_sndct.access.LhtrkAccessMod;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;
import java.util.Set;

public class LhtrkAdminMod implements ModInitializer {
    public static final String MOD_ID = "lhtrk_admin";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(Commands.literal("mayak")
                    .requires(source -> serverIsOp(source.getServer(), source.getTextName()))
                    .executes(ctx -> {
                        ServerPlayer self = ctx.getSource().getPlayerOrException();
                        teleportToDimensionSpawn(self, LhtrkAccessMod.MAYAK_DIM, "Маяк", 0x00F5D4);
                        return 1;
                    })
                    .then(Commands.argument("target", EntityArgument.player())
                            .executes(ctx -> {
                                ServerPlayer target = EntityArgument.getPlayer(ctx, "target");
                                teleportToDimensionSpawn(target, LhtrkAccessMod.MAYAK_DIM, "Маяк", 0x00F5D4);
                                ctx.getSource().sendSuccess(() -> Component.literal("✦ ")
                                        .append(Component.literal(target.getName().getString()).withColor(0x00F5D4))
                                        .append(" телепортовано у світ Маяк."), true);
                                return 1;
                            })));

            dispatcher.register(Commands.literal("overworld")
                    .requires(source -> serverIsOp(source.getServer(), source.getTextName()))
                    .executes(ctx -> {
                        ServerPlayer self = ctx.getSource().getPlayerOrException();
                        teleportToDimensionSpawn(self, Level.OVERWORLD, "Великі Землі", 0x55FF55);
                        return 1;
                    })
                    .then(Commands.argument("target", EntityArgument.player())
                            .executes(ctx -> {
                                ServerPlayer target = EntityArgument.getPlayer(ctx, "target");
                                teleportToDimensionSpawn(target, Level.OVERWORLD, "Великі Землі", 0x55FF55);
                                ctx.getSource().sendSuccess(() -> Component.literal("✦ ")
                                        .append(Component.literal(target.getName().getString()).withColor(0x55FF55))
                                        .append(" телепортовано у Великі Землі."), true);
                                return 1;
                            })));

            dispatcher.register(Commands.literal("setmayakspawn")
                    .requires(source -> serverIsOp(source.getServer(), source.getTextName()))
                    .executes(ctx -> {
                        ServerPlayer self = ctx.getSource().getPlayerOrException();
                        LhtrkAccessMod.setMayakSpawn(self.getX(), self.getY(), self.getZ());
                        String coords = String.format(Locale.ROOT, "%.2f, %.2f, %.2f", self.getX(), self.getY(), self.getZ());
                        ctx.getSource().sendSuccess(() -> Component.literal("✦ Спавн Маяка оновлено: ")
                                .append(Component.literal(coords).withColor(0x00F5D4)), true);
                        return 1;
                    }));
        });

        LOGGER.info("Модуль LHTRK sndct Admin завантажено!");
    }

    private static void teleportToDimensionSpawn(ServerPlayer player, ResourceKey<Level> targetDim, String worldNameUa, int color) {
        MinecraftServer server = player.level().getServer();
        if (server == null) return;
        ServerLevel destination = server.getLevel(targetDim);
        if (destination != null) {
            double x, y, z;
            if (targetDim.equals(LhtrkAccessMod.MAYAK_DIM)) {
                x = LhtrkAccessMod.getMayakSpawnX();
                y = LhtrkAccessMod.getMayakSpawnY();
                z = LhtrkAccessMod.getMayakSpawnZ();
            } else {
                var spawnPos = destination.getLevelData().getRespawnData().pos();
                x = spawnPos.getX() + 0.5;
                y = spawnPos.getY();
                z = spawnPos.getZ() + 0.5;
            }
            player.teleportTo(destination, x, y, z, Set.of(), player.getYRot(), player.getXRot(), true);
            player.sendSystemMessage(Component.literal("✦ Телепортовано у світ: ")
                    .append(Component.literal(worldNameUa).withColor(color)));
        }
    }

    private static boolean serverIsOp(MinecraftServer server, String name) {
        if (server == null) return false;
        for (String opName : server.getPlayerList().getOps().getUserList()) {
            if (opName.equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }
}