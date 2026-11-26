package lhtrk_sndct.placeholders;

import eu.pb4.placeholders.api.PlaceholderResult;
import eu.pb4.placeholders.api.Placeholders;
import lhtrk_sndct.access.LhtrkAccessMod;
import lhtrk_sndct.afk.LhtrkAfkMod;
import net.fabricmc.api.ModInitializer;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LhtrkPlaceholdersMod implements ModInitializer {
    public static final String MOD_ID = "lhtrk_placeholders";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        Placeholders.registerServer(Identifier.fromNamespaceAndPath("lhtrk", "playtime_ua"), (ctx, arg) -> {
            if (!(ctx.player() instanceof ServerPlayer player)) {
                return PlaceholderResult.invalid("No player");
            }
            int ticks = player.getStats().getValue(Stats.CUSTOM.get(Stats.PLAY_TIME));
            long totalMinutes = ticks / 1200L;
            long hours = totalMinutes / 60L;
            long minutes = totalMinutes % 60L;
            String formatted = hours > 0 ? (hours + " год " + minutes + " хв") : (minutes + " хв");
            return PlaceholderResult.value(formatted);
        });

        Placeholders.registerServer(Identifier.fromNamespaceAndPath("lhtrk", "sub_badge"), (ctx, arg) -> {
            if (!(ctx.player() instanceof ServerPlayer player)) {
                return PlaceholderResult.invalid("No player");
            }
            return LhtrkAccessMod.isFullPlayer(player)
                    ? PlaceholderResult.value(Component.literal("✦").withColor(0x00F5D4))
                    : PlaceholderResult.value(Component.literal("◇").withColor(0x888888));
        });

        Placeholders.registerServer(Identifier.fromNamespaceAndPath("lhtrk", "role_hover"), (ctx, arg) -> {
            if (!(ctx.player() instanceof ServerPlayer player)) {
                return PlaceholderResult.invalid("No player");
            }
            return LhtrkAccessMod.isFullPlayer(player)
                    ? PlaceholderResult.value(Component.literal("✦ Гравець").withColor(0x00F5D4))
                    : PlaceholderResult.value(Component.literal("◇ Гість").withColor(0xAAAAAA));
        });

        Placeholders.registerServer(Identifier.fromNamespaceAndPath("lhtrk", "world_ua"), (ctx, arg) -> {
            if (!(ctx.player() instanceof ServerPlayer player)) {
                return PlaceholderResult.invalid("No player");
            }
            String dim = player.level().dimension().identifier().toString();
            return switch (dim) {
                case "mayak:mayak" -> PlaceholderResult.value(Component.literal("Маяк").withColor(0x00F5D4));
                case "minecraft:overworld" -> PlaceholderResult.value(Component.literal("Великі Землі").withColor(0x55FF55));
                case "minecraft:the_nether" -> PlaceholderResult.value(Component.literal("Незер").withColor(0xFF5555));
                case "minecraft:the_end" -> PlaceholderResult.value(Component.literal("Енд").withColor(0xAA00AA));
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
                    ? PlaceholderResult.value(Component.literal(" [AFK]").withColor(0x888888))
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
}