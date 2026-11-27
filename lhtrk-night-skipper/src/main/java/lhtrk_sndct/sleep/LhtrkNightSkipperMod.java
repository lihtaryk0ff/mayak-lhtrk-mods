package lhtrk_sndct.sleep;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import lhtrk_sndct.access.LhtrkAccessMod;
import lhtrk_sndct.afk.LhtrkAfkMod;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.clock.ServerClockManager;
import net.minecraft.world.clock.WorldClock;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class LhtrkNightSkipperMod implements ModInitializer {
    public static final String MOD_ID = "lhtrk_night_skipper";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final int DAY_LENGTH = 24000;

    private static Path configPath;
    private static NightSkipperConfig config = new NightSkipperConfig();

    public static class NightSkipperConfig {
        public double minSleepPercentage = 0.20;
        public double defaultSleepPercentage = 0.50;
        public double maxSleepPercentage = 0.90;
        public double percentageStep = 0.10;
        public int targetMorningTime = 0;
        public int maxWarpDurationTicks = 1200;
        public boolean ignoreAfkPlayers = true;
        public boolean clearWeatherOnDawn = true;
        public String msgSleepingPrefix = "🛏 Сплять: ";
        public String msgSleepingNeeded = " • Ще потрібно: ";
        public String msgDawnChat = "☀ Настає світанок... Ніч пропускається!";
        public String msgWarpingActionBar = "☀ Пропуск ночі...";
    }

    private static double currentSleepPercentage = 0.50;
    private static boolean failedAttemptPending = false;

    private static boolean isWarping = false;
    private static long targetOverworldTotalTicks = 0L;
    private static long targetMayakTotalTicks = 0L;
    private static double fractionalAccumulator = 0.0;
    private static boolean wasSleepOverlayActive = false;

    @Override
    public void onInitialize() {
        ServerLifecycleEvents.SERVER_STARTING.register(server -> {
            configPath = server.getServerDirectory().resolve("config").resolve("lhtrk_night_skipper.json");
            reloadConfig();
            currentSleepPercentage = config.defaultSleepPercentage;
        });

        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            var silentSource = server.createCommandSourceStack().withSuppressedOutput();
            server.getCommands().performPrefixedCommand(silentSource, "gamerule players_sleeping_percentage 100");
            server.getCommands().performPrefixedCommand(silentSource, "gamerule minecraft:players_sleeping_percentage 100");
        });

        ServerTickEvents.END_SERVER_TICK.register(this::handleSmoothSleep);
        LOGGER.info("Модуль LHTRK sndct Night Skipper завантажено!");
    }

    private void handleSmoothSleep(MinecraftServer server) {
        ServerLevel overworld = server.getLevel(Level.OVERWORLD);
        if (overworld == null) return;
        ServerLevel mayakLevel = server.getLevel(LhtrkAccessMod.MAYAK_DIM);

        List<ServerPlayer> eligiblePlayers = new ArrayList<>();
        int sleepingCount = 0;

        for (ServerPlayer p : overworld.players()) {
            if (!p.isSpectator() && LhtrkAccessMod.isFullPlayer(p) && (!config.ignoreAfkPlayers || !LhtrkAfkMod.isAfk(p))) {
                eligiblePlayers.add(p);
                if (p.isSleeping()) {
                    sleepingCount++;
                }
            }
        }

        if (mayakLevel != null) {
            for (ServerPlayer p : mayakLevel.players()) {
                if (!p.isSpectator() && LhtrkAccessMod.isFullPlayer(p) && (!config.ignoreAfkPlayers || !LhtrkAfkMod.isAfk(p))) {
                    eligiblePlayers.add(p);
                    if (p.isSleeping()) {
                        sleepingCount++;
                    }
                }
            }
        }

        if (eligiblePlayers.isEmpty()) {
            resetWarpState();
            return;
        }

        Holder<WorldClock> overworldClock = overworld.dimensionType().defaultClock().orElse(null);
        if (overworldClock == null) return;

        ServerClockManager clockManager = overworld.clockManager();
        Holder<WorldClock> mayakClock = (mayakLevel != null)
                ? mayakLevel.dimensionType().defaultClock().orElse(null)
                : null;
        boolean hasSeparateMayakClock = mayakClock != null && !mayakClock.equals(overworldClock);

        if (!isWarping) {
            if (sleepingCount == 0) {
                if (failedAttemptPending) {
                    failedAttemptPending = false;
                    currentSleepPercentage = Math.max(config.minSleepPercentage,
                            Math.round((currentSleepPercentage - config.percentageStep) * 100.0) / 100.0);
                }

                if (wasSleepOverlayActive) {
                    for (ServerPlayer p : eligiblePlayers) {
                        p.sendSystemMessage(Component.empty(), true);
                    }
                    wasSleepOverlayActive = false;
                }
                return;
            }

            int requiredCount = Math.max(1, (int) Math.ceil(eligiblePlayers.size() * currentSleepPercentage));
            int neededMore = Math.max(0, requiredCount - sleepingCount);

            if (neededMore > 0) {
                failedAttemptPending = true;

                String peopleWord = switch (neededMore) {
                    case 1 -> "1 гравець";
                    case 2, 3, 4 -> neededMore + " гравці";
                    default -> neededMore + " гравців";
                };

                Component actionBar = Component.literal(config.msgSleepingPrefix)
                        .withColor(0x00F5D4)
                        .append(Component.literal(sleepingCount + "/" + requiredCount).withColor(0xFEE440))
                        .append(Component.literal(config.msgSleepingNeeded).withColor(0xAAAAAA))
                        .append(Component.literal(peopleWord).withColor(0xFF2A85));

                for (ServerPlayer p : eligiblePlayers) {
                    p.sendSystemMessage(actionBar, true);
                }
                wasSleepOverlayActive = true;
                return;
            }

            long currentOverworldTotal = clockManager.getInstance(overworldClock).totalTicks();
            long ticksToTarget = computeTicksToDawn(currentOverworldTotal);
            targetOverworldTotalTicks = currentOverworldTotal + ticksToTarget;

            if (hasSeparateMayakClock) {
                long currentMayakTotal = mayakLevel.clockManager().getInstance(mayakClock).totalTicks();
                targetMayakTotalTicks = currentMayakTotal + computeTicksToDawn(currentMayakTotal);
            } else {
                targetMayakTotalTicks = 0L;
            }

            failedAttemptPending = false;
            currentSleepPercentage = Math.min(config.maxSleepPercentage,
                    Math.round((currentSleepPercentage + config.percentageStep) * 100.0) / 100.0);

            isWarping = true;
            fractionalAccumulator = 0.0;

            for (ServerPlayer p : eligiblePlayers) {
                if (p.isSleeping()) {
                    p.stopSleepInBed(false, true);
                }
            }

            Component chatMsg = Component.literal(config.msgDawnChat).withColor(0x00F5D4);
            for (ServerPlayer p : eligiblePlayers) {
                p.sendSystemMessage(chatMsg);
            }
        }

        // Враховуємо природний тік (+1), який сервер уже зробив у цьому тіку, щоб не перелітати світанок
        int maxNightTicks = DAY_LENGTH - 12541 + config.targetMorningTime;
        double ticksPerServerTick = (double) maxNightTicks / Math.max(1, config.maxWarpDurationTicks);
        fractionalAccumulator += ticksPerServerTick;
        int desiredTotalStep = (int) Math.floor(fractionalAccumulator);
        fractionalAccumulator -= desiredTotalStep;
        int extraTicksThisTick = Math.max(0, desiredTotalStep - 1);

        long currentOverworldTotal = clockManager.getInstance(overworldClock).totalTicks();
        long remainingOverworld = targetOverworldTotalTicks - currentOverworldTotal;

        if (remainingOverworld > 0 && extraTicksThisTick > 0) {
            int stepNow = (int) Math.min(extraTicksThisTick, remainingOverworld);
            clockManager.addTicks(overworldClock, stepNow);
            remainingOverworld -= stepNow;
        }

        if (hasSeparateMayakClock) {
            ServerClockManager mayakClockManager = mayakLevel.clockManager();
            long currentMayakTotal = mayakClockManager.getInstance(mayakClock).totalTicks();
            long remainingMayak = targetMayakTotalTicks - currentMayakTotal;
            if (remainingMayak > 0 && extraTicksThisTick > 0) {
                int stepMayak = (int) Math.min(extraTicksThisTick, remainingMayak);
                mayakClockManager.addTicks(mayakClock, stepMayak);
            }
        }

        if (remainingOverworld <= 0) {
            finishNightSkip(overworld, mayakLevel, eligiblePlayers);
            return;
        }

        Component actionBar = Component.literal(config.msgWarpingActionBar).withColor(0x00F5D4);
        for (ServerPlayer p : eligiblePlayers) {
            p.sendSystemMessage(actionBar, true);
        }
        wasSleepOverlayActive = true;
    }

    private static long computeTicksToDawn(long totalTicks) {
        long dayTime = Math.floorMod(totalTicks, DAY_LENGTH);
        long ticksToTarget = Math.floorMod(config.targetMorningTime - dayTime, DAY_LENGTH);
        return ticksToTarget == 0 ? DAY_LENGTH : ticksToTarget;
    }

    private static void finishNightSkip(ServerLevel overworld, ServerLevel mayakLevel, List<ServerPlayer> eligiblePlayers) {
        resetWarpState();

        if (config.clearWeatherOnDawn) {
            if (overworld.isRaining() || overworld.isThundering()) {
                overworld.resetWeatherCycle();
            }
            if (mayakLevel != null && (mayakLevel.isRaining() || mayakLevel.isThundering())) {
                mayakLevel.resetWeatherCycle();
            }
        }

        for (ServerPlayer p : eligiblePlayers) {
            p.sendSystemMessage(Component.empty(), true);
        }
    }

    public static synchronized void reloadConfig() {
        if (configPath == null) return;
        if (!Files.exists(configPath)) {
            saveConfig();
            return;
        }
        try (Reader reader = Files.newBufferedReader(configPath, StandardCharsets.UTF_8)) {
            NightSkipperConfig loaded = GSON.fromJson(reader, NightSkipperConfig.class);
            if (loaded != null) {
                config = loaded;
            }
        } catch (IOException e) {
            LOGGER.error("Помилка читання config/lhtrk_night_skipper.json", e);
        }
    }

    private static void saveConfig() {
        if (configPath == null) return;
        try {
            Files.createDirectories(configPath.getParent());
            try (Writer writer = Files.newBufferedWriter(configPath, StandardCharsets.UTF_8)) {
                GSON.toJson(config, writer);
            }
        } catch (IOException e) {
            LOGGER.error("Помилка запису config/lhtrk_night_skipper.json", e);
        }
    }

    private static void resetWarpState() {
        isWarping = false;
        targetOverworldTotalTicks = 0L;
        targetMayakTotalTicks = 0L;
        fractionalAccumulator = 0.0;
        wasSleepOverlayActive = false;
    }
}