package lhtrk_sndct.afk;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class LhtrkAfkMod implements ModInitializer {
    public static final String MOD_ID = "lhtrk_afk";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static final long AFK_TIMEOUT_MS = 5 * 60 * 1000L; // 5 хвилин
    private static final double MOVE_THRESHOLD_SQ = 0.0025; // > 0.05 блока по горизонталі
    private static final DustParticleOptions AFK_ORBIT_DUST = new DustParticleOptions(0x9EA7B3, 0.8F);

    private static final Map<UUID, Long> LAST_ACTIVITY_MS = new HashMap<>();
    private static final Map<UUID, PlayerPose> LAST_POSES = new HashMap<>();
    private static final Set<UUID> AFK_PLAYERS = new HashSet<>();

    private record PlayerPose(double x, double z, float yRot, float xRot) {}

    @Override
    public void onInitialize() {
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayer player = handler.getPlayer();
            UUID uuid = player.getUUID();
            LAST_ACTIVITY_MS.put(uuid, System.currentTimeMillis());
            LAST_POSES.put(uuid, capturePose(player));
            AFK_PLAYERS.remove(uuid);
        });

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            UUID uuid = handler.getPlayer().getUUID();
            LAST_ACTIVITY_MS.remove(uuid);
            LAST_POSES.remove(uuid);
            AFK_PLAYERS.remove(uuid);
        });

        ServerMessageEvents.CHAT_MESSAGE.register((message, sender, params) -> markActive(sender));

        AttackBlockCallback.EVENT.register((player, world, hand, pos, direction) -> {
            if (!world.isClientSide() && player instanceof ServerPlayer sp) markActive(sp);
            return InteractionResult.PASS;
        });

        PlayerBlockBreakEvents.BEFORE.register((world, player, pos, state, blockEntity) -> {
            if (!world.isClientSide() && player instanceof ServerPlayer sp) markActive(sp);
            return true;
        });

        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            if (!world.isClientSide() && player instanceof ServerPlayer sp) markActive(sp);
            return InteractionResult.PASS;
        });

        UseItemCallback.EVENT.register((player, world, hand) -> {
            if (!world.isClientSide() && player instanceof ServerPlayer sp) markActive(sp);
            return InteractionResult.PASS;
        });

        AttackEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
            if (!world.isClientSide() && player instanceof ServerPlayer sp) markActive(sp);
            return InteractionResult.PASS;
        });

        UseEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
            if (!world.isClientSide() && player instanceof ServerPlayer sp) markActive(sp);
            return InteractionResult.PASS;
        });

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                dispatcher.register(Commands.literal("afk")
                        .executes(ctx -> {
                            ServerPlayer player = ctx.getSource().getPlayerOrException();
                            if (isAfk(player)) {
                                markActive(player);
                            } else {
                                LAST_POSES.put(player.getUUID(), capturePose(player));
                                setAfkState(player, true);
                            }
                            return 1;
                        })
                        .then(Commands.argument("target", EntityArgument.player())
                                .requires(source -> source.getEntity() == null || serverIsOp(source.getServer(), source.getTextName()))
                                .executes(ctx -> {
                                    ServerPlayer target = EntityArgument.getPlayer(ctx, "target");
                                    if (isAfk(target)) {
                                        markActive(target);
                                        ctx.getSource().sendSuccess(() -> Component.literal("✦ Гравця ")
                                                .append(Component.literal(target.getName().getString()).withColor(0x00F5D4))
                                                .append(" виведено з режиму AFK."), true);
                                    } else {
                                        LAST_POSES.put(target.getUUID(), capturePose(target));
                                        setAfkState(target, true);
                                        ctx.getSource().sendSuccess(() -> Component.literal("◇ Гравця ")
                                                .append(Component.literal(target.getName().getString()).withColor(0xAAAAAA))
                                                .append(" примусово переведено в режим AFK."), true);
                                    }
                                    return 1;
                                }))));

        ServerTickEvents.END_SERVER_TICK.register(this::onServerTick);
        LOGGER.info("Модуль LHTRK sndct AFK завантажено!");
    }

    public static boolean isAfk(ServerPlayer player) {
        return AFK_PLAYERS.contains(player.getUUID());
    }

    private void onServerTick(MinecraftServer server) {
        int tickCount = server.getTickCount();
        long now = System.currentTimeMillis();

        boolean checkActivity = (tickCount % 5 == 0);
        boolean spawnParticles = (tickCount % 4 == 0);
        if (!checkActivity && !spawnParticles) {
            return;
        }

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            UUID uuid = player.getUUID();

            if (checkActivity) {
                PlayerPose currentPose = capturePose(player);
                PlayerPose prevPose = LAST_POSES.put(uuid, currentPose);

                if (prevPose != null && hasMovedOrLooked(prevPose, currentPose)) {
                    markActive(player, now);
                } else if (!AFK_PLAYERS.contains(uuid)) {
                    long lastActive = LAST_ACTIVITY_MS.getOrDefault(uuid, now);
                    if (now - lastActive >= AFK_TIMEOUT_MS) {
                        setAfkState(player, true);
                    }
                }
            }

            if (spawnParticles && AFK_PLAYERS.contains(uuid)) {
                spawnAfkParticles(player, tickCount);
            }
        }
    }

    private static void markActive(ServerPlayer player) {
        markActive(player, System.currentTimeMillis());
    }

    private static void markActive(ServerPlayer player, long now) {
        LAST_ACTIVITY_MS.put(player.getUUID(), now);
        if (AFK_PLAYERS.contains(player.getUUID())) {
            setAfkState(player, false);
        }
    }

    private static void setAfkState(ServerPlayer player, boolean afk) {
        UUID uuid = player.getUUID();
        boolean changed = afk ? AFK_PLAYERS.add(uuid) : AFK_PLAYERS.remove(uuid);
        if (!changed) return;

        if (!afk) {
            LAST_ACTIVITY_MS.put(uuid, System.currentTimeMillis());
            player.sendSystemMessage(Component.literal("✦ Ти повернувся з режиму AFK.").withColor(0x00F5D4));
        } else {
            player.sendSystemMessage(Component.literal("◇ Ти перейшов у режим AFK (час гри не рахується).").withColor(0xAAAAAA));
        }

        MinecraftServer server = player.level().getServer();
        if (server != null) {
            server.getPlayerList().broadcastAll(
                    new ClientboundPlayerInfoUpdatePacket(ClientboundPlayerInfoUpdatePacket.Action.UPDATE_DISPLAY_NAME, player)
            );
        }
    }

    private static void spawnAfkParticles(ServerPlayer player, int tickCount) {
        ServerLevel level = player.level();
        double baseX = player.getX();
        double baseY = player.getY() + 2.15;
        double baseZ = player.getZ();

        double angle = (tickCount * 0.14) % (Math.PI * 2.0);
        double radius = 0.42;

        for (int i = 0; i < 2; i++) {
            double a = angle + i * Math.PI;
            double px = baseX + Math.cos(a) * radius;
            double pz = baseZ + Math.sin(a) * radius;
            double py = baseY + Math.sin(angle * 2.0 + i) * 0.06;
            level.sendParticles(AFK_ORBIT_DUST, px, py, pz, 1, 0.0, 0.0, 0.0, 0.0);
        }

        if (tickCount % 20 == 0) {
            level.sendParticles(ParticleTypes.END_ROD, baseX, baseY + 0.1, baseZ, 1, 0.18, 0.08, 0.18, 0.012);
        }
    }

    private static PlayerPose capturePose(ServerPlayer player) {
        return new PlayerPose(player.getX(), player.getZ(), player.getYRot(), player.getXRot());
    }

    private static boolean hasMovedOrLooked(PlayerPose prev, PlayerPose curr) {
        if (Math.abs(prev.yRot() - curr.yRot()) > 0.1F || Math.abs(prev.xRot() - curr.xRot()) > 0.1F) {
            return true;
        }
        double dx = prev.x() - curr.x();
        double dz = prev.z() - curr.z();
        return (dx * dx + dz * dz) > MOVE_THRESHOLD_SQ;
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
