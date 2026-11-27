package lhtrk_sndct.placeholders;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import eu.pb4.placeholders.api.PlaceholderResult;
import eu.pb4.placeholders.api.Placeholders;
import lhtrk_sndct.access.LhtrkAccessMod;
import lhtrk_sndct.afk.LhtrkAfkMod;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public class LhtrkPlaceholdersMod implements ModInitializer {
    public static final String MOD_ID = "lhtrk_placeholders";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static Path configPath;
    private static PlaceholdersConfig config = new PlaceholdersConfig();

    public static class PlaceholdersConfig {
        public String playerBadge = "✦";
        public String guestBadge = "◇";
        public String playerRoleText = "✦ Гравець";
        public String guestRoleText = "◇ Гість";
        public String afkBadgeText = " [AFK]";
        public String worldMayak = "Маяк";
        public String worldOverworld = "Великі Землі";
        public String worldNether = "Незер";
        public String worldEnd = "Енд";
        public String hourLabel = "год";
        public String minuteLabel = "хв";
    }

    @Override
    public void onInitialize() {
        ServerLifecycleEvents.SERVER_STARTING.register(server -> {
            configPath = server.getServerDirectory().resolve("config").resolve("lhtrk_placeholders.json");
            reloadConfig();
        });

        Placeholders.registerServer(Identifier.fromNamespaceAndPath("lhtrk", "playtime_ua"), (ctx, arg) -> {
            if (!(ctx.player() instanceof ServerPlayer player)) {
                return PlaceholderResult.invalid("No player");
            }
            int ticks = player.getStats().getValue(Stats.CUSTOM.get(Stats.PLAY_TIME));
            long totalMinutes = ticks / 1200L;
            long hours = totalMinutes / 60L;
            long minutes = totalMinutes % 60L;
            String formatted = hours > 0
                    ? (hours + " " + config.hourLabel + " " + minutes + " " + config.minuteLabel)
                    : (minutes + " " + config.minuteLabel);
            return PlaceholderResult.value(formatted);
        });

        Placeholders.registerServer(Identifier.fromNamespaceAndPath("lhtrk", "sub_badge"), (ctx, arg) -> {
            if (!(ctx.player() instanceof ServerPlayer player)) {
                return PlaceholderResult.invalid("No player");
            }
            return LhtrkAccessMod.isFullPlayer(player)
                    ? PlaceholderResult.value(Component.literal(config.playerBadge).withColor(0x00F5D4))
                    : PlaceholderResult.value(Component.literal(config.guestBadge).withColor(0x888888));
        });

        Placeholders.registerServer(Identifier.fromNamespaceAndPath("lhtrk", "role_hover"), (ctx, arg) -> {
            if (!(ctx.player() instanceof ServerPlayer player)) {
                return PlaceholderResult.invalid("No player");
            }
            return LhtrkAccessMod.isFullPlayer(player)
                    ? PlaceholderResult.value(Component.literal(config.playerRoleText).withColor(0x00F5D4))
                    : PlaceholderResult.value(Component.literal(config.guestRoleText).withColor(0xAAAAAA));
        });

        Placeholders.registerServer(Identifier.fromNamespaceAndPath("lhtrk", "world_ua"), (ctx, arg) -> {
            if (!(ctx.player() instanceof ServerPlayer player)) {
                return PlaceholderResult.invalid("No player");
            }
            String dim = player.level().dimension().identifier().toString();
            return switch (dim) {
                case "mayak:mayak" -> PlaceholderResult.value(Component.literal(config.worldMayak).withColor(0x00F5D4));
                case "minecraft:overworld" -> PlaceholderResult.value(Component.literal(config.worldOverworld).withColor(0x55FF55));
                case "minecraft:the_nether" -> PlaceholderResult.value(Component.literal(config.worldNether).withColor(0xFF5555));
                case "minecraft:the_end" -> PlaceholderResult.value(Component.literal(config.worldEnd).withColor(0xAA00AA));
                default -> PlaceholderResult.value(dim);
            };
        });

        Placeholders.registerServer(Identifier.fromNamespaceAndPath("lhtrk", "advancements"), (ctx, arg) -> {
            if (!(ctx.player() instanceof ServerPlayer player)) {
                return PlaceholderResult.invalid("No player");
            }
            long done = 0;
            MinecraftServer server = player.level().getServer();
            if (server != null) {
                for (AdvancementHolder holder : server.getAdvancements().getAllAdvancements()) {
                    if (holder.value().display().isPresent() && player.getAdvancements().getOrStartProgress(holder).isDone()) {
                        done++;
                    }
                }
            }
            return PlaceholderResult.value(String.valueOf(done));
        });

        Placeholders.registerServer(Identifier.fromNamespaceAndPath("lhtrk", "afk_badge"), (ctx, arg) -> {
            if (!(ctx.player() instanceof ServerPlayer player)) {
                return PlaceholderResult.invalid("No player");
            }
            return LhtrkAfkMod.isAfk(player)
                    ? PlaceholderResult.value(Component.literal(config.afkBadgeText).withColor(0x888888))
                    : PlaceholderResult.value(Component.empty());
        });

        Placeholders.registerServer(Identifier.fromNamespaceAndPath("lhtrk", "online_players"), (ctx, arg) -> {
            MinecraftServer server = ctx.server();
            if (server == null) {
                return PlaceholderResult.value("0");
            }
            long count = server.getPlayerList().getPlayers().stream()
                    .filter(LhtrkAccessMod::isFullPlayer)
                    .count();
            return PlaceholderResult.value(String.valueOf(count));
        });

        LOGGER.info("Модуль LHTRK sndct Placeholders завантажено!");
    }

    public static synchronized void reloadConfig() {
        if (configPath == null) return;
        if (!Files.exists(configPath)) {
            saveConfig();
            return;
        }
        try (Reader reader = Files.newBufferedReader(configPath, StandardCharsets.UTF_8)) {
            PlaceholdersConfig loaded = GSON.fromJson(reader, PlaceholdersConfig.class);
            if (loaded != null) {
                config = loaded;
            }
        } catch (IOException e) {
            LOGGER.error("Помилка читання config/lhtrk_placeholders.json", e);
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
            LOGGER.error("Помилка запису config/lhtrk_placeholders.json", e);
        }
    }
}