package lhtrk_sndct.admin;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import lhtrk_sndct.access.LhtrkAccessMod;
import lhtrk_sndct.afk.LhtrkAfkMod;
import lhtrk_sndct.placeholders.LhtrkPlaceholdersMod;
import lhtrk_sndct.sleep.LhtrkNightSkipperMod;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
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

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;

public class LhtrkAdminMod implements ModInitializer {
    public static final String MOD_ID = "lhtrk_admin";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static Path adminConfigPath;
    private static AdminConfig adminConfig = new AdminConfig();

    public static class AdminConfig {
        public String mayakWorldName = "Маяк";
        public String overworldName = "Великі Землі";
        public String teleportPrefix = "✦ Телепортовано у світ: ";
        public String reloadSuccess = "✦ Конфіги всіх модів LHTRK перезавантажено!";
    }

    @Override
    public void onInitialize() {
        ServerLifecycleEvents.SERVER_STARTING.register(server -> {
            adminConfigPath = server.getServerDirectory().resolve("config").resolve("lhtrk_admin.json");
            reloadAdminConfig();
            RestartScheduler.init(server, LOGGER);
        });
        ServerTickEvents.END_SERVER_TICK.register(RestartScheduler::onServerTick);

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(Commands.literal("mayak")
                    .requires(source -> serverIsOp(source.getServer(), source.getTextName()))
                    .executes(ctx -> {
                        ServerPlayer self = ctx.getSource().getPlayerOrException();
                        teleportToDimensionSpawn(self, LhtrkAccessMod.MAYAK_DIM, adminConfig.mayakWorldName, 0x00F5D4);
                        return 1;
                    })
                    .then(Commands.argument("target", EntityArgument.player())
                            .executes(ctx -> {
                                ServerPlayer target = EntityArgument.getPlayer(ctx, "target");
                                teleportToDimensionSpawn(target, LhtrkAccessMod.MAYAK_DIM, adminConfig.mayakWorldName, 0x00F5D4);
                                ctx.getSource().sendSuccess(() -> Component.literal("✦ ")
                                        .append(Component.literal(target.getName().getString()).withColor(0x00F5D4))
                                        .append(" телепортовано у світ Маяк."), true);
                                return 1;
                            })));

            dispatcher.register(Commands.literal("overworld")
                    .requires(source -> serverIsOp(source.getServer(), source.getTextName()))
                    .executes(ctx -> {
                        ServerPlayer self = ctx.getSource().getPlayerOrException();
                        teleportToDimensionSpawn(self, Level.OVERWORLD, adminConfig.overworldName, 0x55FF55);
                        return 1;
                    })
                    .then(Commands.argument("target", EntityArgument.player())
                            .executes(ctx -> {
                                ServerPlayer target = EntityArgument.getPlayer(ctx, "target");
                                teleportToDimensionSpawn(target, Level.OVERWORLD, adminConfig.overworldName, 0x55FF55);
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

            for (String cmdName : new String[]{"techrestart", "trestart"}) {
                dispatcher.register(Commands.literal(cmdName)
                        .requires(source -> source.getEntity() == null || serverIsOp(source.getServer(), source.getTextName()))
                        .executes(ctx -> {
                            RestartScheduler.startTechRestart(ctx.getSource().getServer(), RestartScheduler.getDefaultTechSeconds());
                            return 1;
                        })
                        .then(Commands.argument("seconds", IntegerArgumentType.integer(1, 3600))
                                .executes(ctx -> {
                                    int seconds = IntegerArgumentType.getInteger(ctx, "seconds");
                                    RestartScheduler.startTechRestart(ctx.getSource().getServer(), seconds);
                                    return 1;
                                }))
                        .then(Commands.literal("cancel")
                                .executes(ctx -> {
                                    if (!RestartScheduler.cancelRestart(ctx.getSource().getServer())) {
                                        ctx.getSource().sendFailure(Component.literal("◇ Немає активного перезавантаження для скасування."));
                                        return 0;
                                    }
                                    return 1;
                                })));
            }

            dispatcher.register(Commands.literal("lhtrkreload")
                    .requires(source -> source.getEntity() == null || serverIsOp(source.getServer(), source.getTextName()))
                    .executes(ctx -> {
                        LhtrkAccessMod.reloadConfig();
                        LhtrkAfkMod.reloadConfig();
                        LhtrkNightSkipperMod.reloadConfig();
                        LhtrkPlaceholdersMod.reloadConfig();
                        reloadAdminConfig();
                        RestartScheduler.reloadConfig(LOGGER);
                        ctx.getSource().sendSuccess(() -> Component.literal(adminConfig.reloadSuccess).withColor(0x00F5D4), true);
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
            player.sendSystemMessage(Component.literal(adminConfig.teleportPrefix)
                    .append(Component.literal(worldNameUa).withColor(color)));
        }
    }

    public static synchronized void reloadAdminConfig() {
        if (adminConfigPath == null) return;
        if (!Files.exists(adminConfigPath)) {
            saveAdminConfig();
            return;
        }
        try (Reader reader = Files.newBufferedReader(adminConfigPath, StandardCharsets.UTF_8)) {
            AdminConfig loaded = GSON.fromJson(reader, AdminConfig.class);
            if (loaded != null) {
                adminConfig = loaded;
            }
        } catch (IOException e) {
            LOGGER.error("Помилка читання config/lhtrk_admin.json", e);
        }
    }

    private static void saveAdminConfig() {
        if (adminConfigPath == null) return;
        try {
            Files.createDirectories(adminConfigPath.getParent());
            try (Writer writer = Files.newBufferedWriter(adminConfigPath, StandardCharsets.UTF_8)) {
                GSON.toJson(adminConfig, writer);
            }
        } catch (IOException e) {
            LOGGER.error("Помилка запису config/lhtrk_admin.json", e);
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