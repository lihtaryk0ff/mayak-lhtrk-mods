package lhtrk_sndct.admin;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * Handles both the daily 04:00 scheduled restart and /techrestart countdowns.
 */
public final class RestartScheduler {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private static Path configPath;
    private static RestartConfig config = new RestartConfig();

    private static long nextScheduledEpochSec = -1L;
    private static boolean warned15m = false;
    private static boolean warned1m = false;
    private static boolean warned15s = false;

    private static boolean techRestartActive = false;
    private static long techRestartTargetEpochSec = -1L;
    private static long lastCountdownShownSec = -1L;
    private static boolean restartTriggered = false;

    public static class RestartConfig {
        public boolean enabled = true;
        public int scheduledHour = 4;
        public int scheduledMinute = 0;
        public String timezone = "Europe/Kyiv";
        public String pelicanApiUrl = "";
        public String pelicanServerId = "";
        public String pelicanApiToken = "";
    }

    private RestartScheduler() {}

    public static void init(MinecraftServer server, Logger logger) {
        configPath = server.getServerDirectory().resolve("config").resolve("lhtrk_restart.json");
        loadConfig(logger);
        scheduleNextDailyRestart();
        techRestartActive = false;
        restartTriggered = false;
        lastCountdownShownSec = -1L;
    }

    public static void startTechRestart(MinecraftServer server, int seconds) {
        int clampedSeconds = Math.max(1, seconds);
        long now = System.currentTimeMillis() / 1000L;
        techRestartActive = true;
        techRestartTargetEpochSec = now + clampedSeconds;
        lastCountdownShownSec = -1L;
        restartTriggered = false;

        // Показуємо великий Title перед очима лише один раз на старті тех-рестарту
        Component title = Component.literal("⚙ ТЕХНІЧНИЙ РЕСТАРТ")
                .withStyle(ChatFormatting.BOLD)
                .withColor(0xFF2A85);
        Component subtitle = Component.literal("Перезавантаження через " + clampedSeconds + " сек...")
                .withColor(0xFEE440);
        sendTitleToAll(server, title, subtitle, 5, 50, 15);
        playBellToAll(server);

        Component chat = Component.literal("⚙ ")
                .withColor(0xFF2A85)
                .append(Component.literal("УВАГА! Технічний рестарт сервера через " + clampedSeconds + " сек!").withColor(0xFEE440));
        broadcastChat(server, chat);
    }

    public static boolean cancelRestart(MinecraftServer server) {
        if (!techRestartActive && !warned15s && !warned1m && !warned15m) {
            return false;
        }
        if (techRestartActive) {
            techRestartActive = false;
            techRestartTargetEpochSec = -1L;
        } else {
            // Skip today's scheduled restart by advancing to tomorrow
            scheduleNextDailyRestart(true);
        }
        lastCountdownShownSec = -1L;
        restartTriggered = false;

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            player.connection.send(new ClientboundSetTitleTextPacket(Component.empty()));
            player.connection.send(new ClientboundSetSubtitleTextPacket(Component.empty()));
            player.sendSystemMessage(Component.empty(), true);
        }
        broadcastChat(server, Component.literal("✦ Перезавантаження сервера скасовано.").withColor(0x00F5D4));
        return true;
    }

    public static void onServerTick(MinecraftServer server) {
        if (restartTriggered) return;
        if (server.getTickCount() % 10 != 0) return; // Перевіряємо двічі на секунду

        long nowSec = System.currentTimeMillis() / 1000L;

        if (techRestartActive) {
            long remaining = techRestartTargetEpochSec - nowSec;
            handleTechCountdown(server, remaining);
            return;
        }

        if (!config.enabled || nextScheduledEpochSec <= 0) return;

        long remaining = nextScheduledEpochSec - nowSec;
        handleScheduledCountdown(server, remaining);
    }

    private static void handleTechCountdown(MinecraftServer server, long remaining) {
        if (remaining <= 0) {
            executeRestart(server, true);
            return;
        }
        if (remaining == lastCountdownShownSec) return;
        lastCountdownShownSec = remaining;

        Component actionBar = Component.literal("⚙ Технічний рестарт через ")
                .withColor(0xFF2A85)
                .append(Component.literal(remaining + " сек").withColor(0xFEE440));
        sendActionBarToAll(server, actionBar);
    }

    private static void handleScheduledCountdown(MinecraftServer server, long remaining) {
        if (remaining <= 0) {
            executeRestart(server, false);
            return;
        }

        if (remaining <= 900 && !warned15m) {
            warned15m = true;
            String timeStr = String.format("%02d:%02d", config.scheduledHour, config.scheduledMinute);
            broadcastChat(server, Component.literal("✦ Планове перезавантаження сервера через 15 хвилин (о " + timeStr + ").")
                    .withColor(0x00F5D4));
            playBellToAll(server);
        }

        if (remaining <= 60 && !warned1m) {
            warned1m = true;
            broadcastChat(server, Component.literal("✦ Планове перезавантаження сервера через 1 хвилину!")
                    .withColor(0xFEE440));
            playBellToAll(server);
        }

        if (remaining <= 15 && !warned15s) {
            warned15s = true;
            broadcastChat(server, Component.literal("◇ Перезавантаження сервера через 15 секунд! Зберігаємо світ...")
                    .withColor(0xFF2A85));
            playBellToAll(server);
        }

        if (remaining <= 5 && remaining != lastCountdownShownSec) {
            lastCountdownShownSec = remaining;
            sendActionBarToAll(server, Component.literal("◇ Перезавантаження через ")
                    .withColor(0xFF2A85)
                    .append(Component.literal(remaining + " сек...").withColor(0xFEE440)));
        }
    }

    private static void executeRestart(MinecraftServer server, boolean isTech) {
        restartTriggered = true;
        LhtrkAdminMod.LOGGER.info("Запуск безшовного перезавантаження (tech={})...", isTech);

        server.saveEverything(false, true, true);

        Component disconnectMsg = isTech
                ? Component.literal("⚙ Технічний рестарт сервера!\nПовертайтесь за мить.").withColor(0xFF2A85)
                : Component.literal("✦ Планове перезавантаження сервера!\nПовертайтесь за 15–30 секунд.").withColor(0x00F5D4);

        for (ServerPlayer player : server.getPlayerList().getPlayers().stream().toList()) {
            player.connection.disconnect(disconnectMsg);
        }

        if (config.pelicanApiUrl != null && !config.pelicanApiUrl.isBlank()
                && config.pelicanServerId != null && !config.pelicanServerId.isBlank()
                && config.pelicanApiToken != null && !config.pelicanApiToken.isBlank()) {
            triggerPelicanRestartAsync(server);
        } else {
            server.halt(false);
        }
    }

    private static void triggerPelicanRestartAsync(MinecraftServer server) {
        String url = config.pelicanApiUrl.replaceAll("/+$", "")
                + "/api/client/servers/" + config.pelicanServerId + "/power";
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(8))
                .header("Authorization", "Bearer " + config.pelicanApiToken.trim())
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"signal\":\"restart\"}"))
                .build();

        client.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .whenComplete((resp, err) -> {
                    if (err != null || resp.statusCode() >= 400) {
                        LhtrkAdminMod.LOGGER.warn("Pelican restart API не відповів ({}), виконуємо server.halt(false)",
                                err != null ? err.getMessage() : resp.statusCode());
                        server.execute(() -> server.halt(false));
                    }
                });
    }

    private static void scheduleNextDailyRestart() {
        scheduleNextDailyRestart(false);
    }

    private static void scheduleNextDailyRestart(boolean forceTomorrow) {
        ZoneId zone;
        try {
            zone = ZoneId.of(config.timezone);
        } catch (Exception e) {
            zone = ZoneId.systemDefault();
        }
        ZonedDateTime now = ZonedDateTime.now(zone);
        ZonedDateTime target = now.withHour(config.scheduledHour).withMinute(config.scheduledMinute).withSecond(0).withNano(0);
        if (forceTomorrow || !target.isAfter(now.plusSeconds(30))) {
            target = target.plusDays(1);
        }
        nextScheduledEpochSec = target.toEpochSecond();
        warned15m = false;
        warned1m = false;
        warned15s = false;
    }

    private static void broadcastChat(MinecraftServer server, Component msg) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            player.sendSystemMessage(msg);
        }
        LhtrkAdminMod.LOGGER.info(msg.getString());
    }

    private static void sendActionBarToAll(MinecraftServer server, Component msg) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            player.sendSystemMessage(msg, true);
        }
    }

    private static void sendTitleToAll(MinecraftServer server, Component title, Component subtitle, int fadeIn, int stay, int fadeOut) {
        var anim = new ClientboundSetTitlesAnimationPacket(fadeIn, stay, fadeOut);
        var titlePkt = new ClientboundSetTitleTextPacket(title);
        var subPkt = new ClientboundSetSubtitleTextPacket(subtitle);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            player.connection.send(anim);
            player.connection.send(titlePkt);
            player.connection.send(subPkt);
        }
    }

    private static void playBellToAll(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            player.connection.send(new ClientboundSoundPacket(
                    SoundEvents.NOTE_BLOCK_BELL,
                    SoundSource.MASTER,
                    player.getX(), player.getY(), player.getZ(),
                    0.8F, 1.0F,
                    player.level().getRandom().nextLong()
            ));
        }
    }

    private static void loadConfig(Logger logger) {
        if (configPath == null) return;
        if (!Files.exists(configPath)) {
            saveConfig(logger);
            return;
        }
        try (Reader reader = Files.newBufferedReader(configPath, StandardCharsets.UTF_8)) {
            RestartConfig loaded = GSON.fromJson(reader, RestartConfig.class);
            if (loaded != null) {
                config = loaded;
            }
        } catch (IOException e) {
            logger.error("Помилка читання config/lhtrk_restart.json", e);
        }
    }

    private static void saveConfig(Logger logger) {
        if (configPath == null) return;
        try {
            Files.createDirectories(configPath.getParent());
            try (Writer writer = Files.newBufferedWriter(configPath, StandardCharsets.UTF_8)) {
                GSON.toJson(config, writer);
            }
        } catch (IOException e) {
            logger.error("Помилка запису config/lhtrk_restart.json", e);
        }
    }
}
