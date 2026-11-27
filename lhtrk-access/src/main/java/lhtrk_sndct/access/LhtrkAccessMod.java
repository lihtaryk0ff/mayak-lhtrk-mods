package lhtrk_sndct.access;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.food.FoodData;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class LhtrkAccessMod implements ModInitializer {
    public static final String MOD_ID = "lhtrk_access";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public static final ResourceKey<Level> MAYAK_DIM = ResourceKey.create(
            Registries.DIMENSION, Identifier.fromNamespaceAndPath("mayak", "mayak")
    );
    private static final double DEFAULT_MAYAK_SPAWN_X = -37.33;
    private static final double DEFAULT_MAYAK_SPAWN_Y = 71.0;
    private static final double DEFAULT_MAYAK_SPAWN_Z = 34.94;

    private static final String DISCORD_URL = "https://discord.gg/gxEgpubfqa";
    private static final Map<UUID, Long> MESSAGE_COOLDOWNS = new HashMap<>();
    private static final Map<UUID, Long> EFFECT_COOLDOWNS = new HashMap<>();
    private static final Map<UUID, Long> STUCK_COOLDOWNS = new HashMap<>();
    private static final Map<UUID, Integer> OUTSIDE_MAYAK_TICKS = new HashMap<>();
    private static final Set<UUID> FORCE_KILL_BYPASS = new HashSet<>();

    private static final DustParticleOptions DENY_DUST = new DustParticleOptions(0xFF2A85, 1.3F);

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss Z");
    private static final List<SubscriberEntry> PLAYERS_WHITELIST = new ArrayList<>();
    private static Path subscribersFilePath;
    private static Path mayakSpawnConfigPath;
    private static Path accessConfigPath;
    private static AccessConfig accessConfig = new AccessConfig();
    private static MayakSpawnConfig mayakSpawnConfig = new MayakSpawnConfig(
            DEFAULT_MAYAK_SPAWN_X, DEFAULT_MAYAK_SPAWN_Y, DEFAULT_MAYAK_SPAWN_Z
    );

    public static class AccessConfig {
        public String discordUrl = "https://discord.gg/gxEgpubfqa";
        public int outsideMayakSeconds = 5;
        public int stuckCooldownSeconds = 600;
        public double mobPacifyRadius = 24.0;
        public double creeperPacifyRadius = 7.0;
        public String msgGuestWarningPrefix = "◇ У тебе доступ Гостя! Отримати доступ Гравця: ";
        public String msgDiscordLabel = "[Discord]";
        public String msgDiscordHover = "Натисни, щоб перейти в Discord та отримати доступ Гравця";
        public String msgOutsideMayak = "◇ Цей світ доступний лише з доступом Гравця! Повернення на МАЯК через 5 сек...";
        public String msgStuckSuccess = "✦ Тебе повернуто на точку спавну МАЯКА!";
        public String msgSubGranted = "✦ Тобі відкрито доступ Гравця! Дозволено будівництво та всі світи.";
        public String msgUnsubRevoked = "◇ Твій доступ змінено на Гостя. Відкритий лише МАЯК.";
    }

    public static class MayakSpawnConfig {
        public double x;
        public double y;
        public double z;

        public MayakSpawnConfig(double x, double y, double z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }

    public static class SubscriberEntry {
        public String uuid;
        public String name;
        public String created;
        public String source;

        public SubscriberEntry(String uuid, String name, String created, String source) {
            this.uuid = uuid;
            this.name = name;
            this.created = created;
            this.source = source;
        }
    }

    @Override
    public void onInitialize() {
        ServerLifecycleEvents.SERVER_STARTING.register(server -> {
            subscribersFilePath = server.getServerDirectory().resolve("subscribers.json");
            mayakSpawnConfigPath = server.getServerDirectory().resolve("config").resolve("lhtrk_mayak.json");
            accessConfigPath = server.getServerDirectory().resolve("config").resolve("lhtrk_access.json");
            reloadConfig();
        });

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayer player = handler.getPlayer();
            if (player.entityTags().contains("subscriber") && !isFullPlayer(player)) {
                addPlayerEntry(player.getUUID().toString(), player.getName().getString(), "LegacyTagMigration");
            } else {
                updateUuidAndNameOnJoin(player);
            }

            if (isFullPlayer(player)) {
                player.removeEffect(MobEffects.MINING_FATIGUE);
            } else {
                applyGuestFatigue(player);
            }
        });

        AttackBlockCallback.EVENT.register((player, world, hand, pos, direction) -> {
            if (!world.isClientSide() && player instanceof ServerPlayer serverPlayer && !isFullPlayer(serverPlayer)) {
                spawnBlockDenyEffect(serverPlayer, pos);
                sendGuestWarning(serverPlayer);
                return InteractionResult.PASS;
            }
            return InteractionResult.PASS;
        });

        PlayerBlockBreakEvents.BEFORE.register((world, player, pos, state, blockEntity) -> {
            if (!world.isClientSide() && player instanceof ServerPlayer serverPlayer && !isFullPlayer(serverPlayer)) {
                spawnBlockDenyEffect(serverPlayer, pos);
                sendGuestWarning(serverPlayer);
                serverPlayer.connection.send(new ClientboundBlockUpdatePacket(pos, state));
                return false;
            }
            return true;
        });

        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            if (!world.isClientSide() && player instanceof ServerPlayer serverPlayer && !isFullPlayer(serverPlayer)) {
                BlockState state = world.getBlockState(hitResult.getBlockPos());
                if (state.is(BlockTags.DOORS)) {
                    return InteractionResult.PASS;
                }
                spawnBlockDenyEffect(serverPlayer, hitResult.getBlockPos());
                sendGuestWarning(serverPlayer);
                return InteractionResult.FAIL;
            }
            return InteractionResult.PASS;
        });

        UseItemCallback.EVENT.register((player, world, hand) -> {
            if (!world.isClientSide() && player instanceof ServerPlayer serverPlayer && !isFullPlayer(serverPlayer)) {
                sendGuestWarning(serverPlayer);
                return InteractionResult.FAIL;
            }
            return InteractionResult.PASS;
        });

        AttackEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
            if (!world.isClientSide() && player instanceof ServerPlayer serverPlayer && !isFullPlayer(serverPlayer)) {
                sendGuestWarning(serverPlayer);
                return InteractionResult.FAIL;
            }
            return InteractionResult.PASS;
        });

        UseEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
            if (!world.isClientSide() && player instanceof ServerPlayer serverPlayer && !isFullPlayer(serverPlayer)) {
                sendGuestWarning(serverPlayer);
                return InteractionResult.FAIL;
            }
            return InteractionResult.PASS;
        });

        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
            if (entity instanceof ServerPlayer serverPlayer && !isFullPlayer(serverPlayer)) {
                return FORCE_KILL_BYPASS.contains(serverPlayer.getUUID()) || source.is(DamageTypes.GENERIC_KILL);
            }
            return true;
        });

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            int tickCount = server.getTickCount();

            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                if (isFullPlayer(player)) {
                    OUTSIDE_MAYAK_TICKS.remove(player.getUUID());
                    continue;
                }

                FoodData foodData = player.getFoodData();
                if (foodData.getFoodLevel() < 20) {
                    foodData.setFoodLevel(20);
                }
                if (foodData.getSaturationLevel() < 5.0F) {
                    foodData.setSaturation(5.0F);
                }
                if (player.getAirSupply() < player.getMaxAirSupply()) {
                    player.setAirSupply(player.getMaxAirSupply());
                }

                // Підтримуємо прихований ефект Втоми від копання (без іконки та частинок зілля)
                if (tickCount % 40 == 0) {
                    applyGuestFatigue(player);
                }

                if (tickCount % 5 == 0) {
                    pacifyMobsAroundGuest(player);
                }

                if (player.level().dimension().equals(MAYAK_DIM)) {
                    OUTSIDE_MAYAK_TICKS.remove(player.getUUID());
                } else {
                    int ticksOutside = OUTSIDE_MAYAK_TICKS.getOrDefault(player.getUUID(), 0) + 1;
                    if (ticksOutside == 1) {
                        player.sendSystemMessage(Component.literal(accessConfig.msgOutsideMayak)
                                .withColor(0xFF2A85));
                    }
                    if (ticksOutside >= Math.max(1, accessConfig.outsideMayakSeconds) * 20) {
                        OUTSIDE_MAYAK_TICKS.remove(player.getUUID());
                        teleportBackToMayak(player, server);
                    } else {
                        OUTSIDE_MAYAK_TICKS.put(player.getUUID(), ticksOutside);
                    }
                }
            }
        });

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(Commands.literal("sub")
                    .requires(source -> source.getEntity() == null || serverIsOp(source.getServer(), source.getTextName()))
                    .then(Commands.argument("player", StringArgumentType.word())
                            .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(
                                    ctx.getSource().getServer().getPlayerNames(), builder))
                            .executes(ctx -> {
                                String targetName = StringArgumentType.getString(ctx, "player");
                                MinecraftServer server = ctx.getSource().getServer();
                                ServerPlayer onlinePlayer = server.getPlayerList().getPlayerByName(targetName);

                                String uuidStr = onlinePlayer != null ? onlinePlayer.getUUID().toString() : "";
                                String exactName = onlinePlayer != null ? onlinePlayer.getName().getString() : targetName;
                                String adminName = ctx.getSource().getTextName();

                                if (addPlayerEntry(uuidStr, exactName, adminName)) {
                                    if (onlinePlayer != null) {
                                        onlinePlayer.addTag("subscriber");
                                        onlinePlayer.removeEffect(MobEffects.MINING_FATIGUE);
                                        onlinePlayer.sendSystemMessage(Component.literal(accessConfig.msgSubGranted)
                                                .withColor(0x00F5D4));
                                    }
                                    ctx.getSource().sendSuccess(() -> Component.literal("✦ ")
                                            .append(Component.literal(exactName).withColor(0x00F5D4))
                                            .append(" отримав доступ Гравця (додано у subscribers.json)."), true);
                                } else {
                                    ctx.getSource().sendFailure(Component.literal(exactName + " вже має доступ Гравця."));
                                }
                                return 1;
                            })));

            dispatcher.register(Commands.literal("unsub")
                    .requires(source -> source.getEntity() == null || serverIsOp(source.getServer(), source.getTextName()))
                    .then(Commands.argument("player", StringArgumentType.word())
                            .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(
                                    PLAYERS_WHITELIST.stream().map(s -> s.name).toList(), builder))
                            .executes(ctx -> {
                                String targetName = StringArgumentType.getString(ctx, "player");
                                MinecraftServer server = ctx.getSource().getServer();
                                ServerPlayer onlinePlayer = server.getPlayerList().getPlayerByName(targetName);

                                if (removePlayerEntry(targetName, onlinePlayer != null ? onlinePlayer.getUUID().toString() : "")) {
                                    if (onlinePlayer != null) {
                                        onlinePlayer.removeTag("subscriber");
                                        applyGuestFatigue(onlinePlayer);
                                        pacifyMobsAroundGuest(onlinePlayer);
                                        onlinePlayer.sendSystemMessage(Component.literal(accessConfig.msgUnsubRevoked)
                                                .withColor(0xFF2A85));
                                    }
                                    ctx.getSource().sendSuccess(() -> Component.literal("◇ ")
                                            .append(Component.literal(targetName).withColor(0xFF2A85))
                                            .append(" переведено на доступ Гостя (видалено зі subscribers.json)."), true);
                                } else {
                                    ctx.getSource().sendFailure(Component.literal(targetName + " не знайдено у списку Гравців."));
                                }
                                return 1;
                            })));

            dispatcher.register(Commands.literal("sublist")
                    .requires(source -> source.getEntity() == null || serverIsOp(source.getServer(), source.getTextName()))
                    .executes(ctx -> {
                        if (PLAYERS_WHITELIST.isEmpty()) {
                            ctx.getSource().sendSuccess(() -> Component.literal("◇ Список Гравців (subscribers.json) порожній."), false);
                        } else {
                            String names = String.join(", ", PLAYERS_WHITELIST.stream().map(s -> s.name).toList());
                            ctx.getSource().sendSuccess(() -> Component.literal("✦ Гравці (" + PLAYERS_WHITELIST.size() + "): ")
                                    .append(Component.literal(names).withColor(0x00F5D4)), false);
                        }
                        return 1;
                    }));

            dispatcher.register(Commands.literal("subreload")
                    .requires(source -> source.getEntity() == null || serverIsOp(source.getServer(), source.getTextName()))
                    .executes(ctx -> {
                        loadSubscribers();
                        for (ServerPlayer online : ctx.getSource().getServer().getPlayerList().getPlayers()) {
                            if (isFullPlayer(online)) {
                                online.removeEffect(MobEffects.MINING_FATIGUE);
                            } else {
                                applyGuestFatigue(online);
                            }
                        }
                        ctx.getSource().sendSuccess(() -> Component.literal("✦ Файл subscribers.json перезавантажено (" + PLAYERS_WHITELIST.size() + " записів)."), true);
                        return 1;
                    }));

            dispatcher.register(Commands.literal("stuck")
                    .executes(ctx -> {
                        ServerPlayer player = ctx.getSource().getPlayerOrException();
                        if (isFullPlayer(player)) {
                            ctx.getSource().sendFailure(Component.literal("◇ Команда /stuck доступна лише Гостям."));
                            return 0;
                        }

                        long now = System.currentTimeMillis();
                        long lastUsed = STUCK_COOLDOWNS.getOrDefault(player.getUUID(), 0L);
                        long cooldownMs = Math.max(0, accessConfig.stuckCooldownSeconds) * 1000L;
                        if (now - lastUsed < cooldownMs) {
                            long totalSecLeft = (cooldownMs - (now - lastUsed)) / 1000L + 1;
                            long minLeft = totalSecLeft / 60;
                            long secLeft = totalSecLeft % 60;
                            ctx.getSource().sendFailure(Component.literal("◇ Зачекай ще " + minLeft + " хв " + secLeft + " сек перед повторним використанням /stuck."));
                            return 0;
                        }
                        STUCK_COOLDOWNS.put(player.getUUID(), now);

                        MinecraftServer server = player.level().getServer();
                        if (server != null) {
                            ServerLevel mayakLevel = server.getLevel(MAYAK_DIM);
                            if (mayakLevel != null) {
                                player.teleportTo(
                                        mayakLevel,
                                        getMayakSpawnX(),
                                        getMayakSpawnY(),
                                        getMayakSpawnZ(),
                                        Set.of(),
                                        player.getYRot(),
                                        player.getXRot(),
                                        true
                                );
                                player.sendSystemMessage(Component.literal(accessConfig.msgStuckSuccess)
                                        .withColor(0x00F5D4));
                            }
                        }
                        return 1;
                    }));

            dispatcher.register(Commands.literal("k")
                    .requires(source -> source.getEntity() == null || serverIsOp(source.getServer(), source.getTextName()))
                    .then(Commands.argument("target", EntityArgument.player())
                            .executes(ctx -> {
                                ServerPlayer target = EntityArgument.getPlayer(ctx, "target");
                                FORCE_KILL_BYPASS.add(target.getUUID());
                                target.getAbilities().invulnerable = false;
                                target.onUpdateAbilities();
                                target.setHealth(0.0F);
                                target.kill(target.level());
                                FORCE_KILL_BYPASS.remove(target.getUUID());
                                ctx.getSource().sendSuccess(() -> Component.literal("☠ ")
                                        .append(Component.literal(target.getName().getString()).withColor(0xFF2A85))
                                        .append(" було вбито."), true);
                                return 1;
                            })));
        });

        LOGGER.info("Модуль LHTRK sndct Access завантажено!");
    }

    private static void applyGuestFatigue(ServerPlayer player) {
        player.addEffect(new MobEffectInstance(
                MobEffects.MINING_FATIGUE,
                100, // 5 секунд (оновлюється кожні 2 сек, тому ніколи не блимає)
                4,   // Рівень V — повністю зупиняє анімацію ламання блоків
                true,  // ambient
                false, // visible (без бульбашок зілля навколо гравця)
                false  // showIcon (без іконки в HUD та інвентарі)
        ));
    }

    private static void spawnBlockDenyEffect(ServerPlayer player, BlockPos pos) {
        long now = System.currentTimeMillis();
        long lastEffect = EFFECT_COOLDOWNS.getOrDefault(player.getUUID(), 0L);
        if (now - lastEffect < 350L) {
            return;
        }
        EFFECT_COOLDOWNS.put(player.getUUID(), now);

        ServerLevel level = player.level();
        double cx = pos.getX() + 0.5;
        double cy = pos.getY() + 0.5;
        double cz = pos.getZ() + 0.5;

        level.sendParticles(player, DENY_DUST, true, false, cx, cy, cz, 14, 0.38, 0.38, 0.38, 0.02);
        level.sendParticles(player, ParticleTypes.SMOKE, true, false, cx, cy, cz, 5, 0.3, 0.3, 0.3, 0.01);

        player.connection.send(new ClientboundSoundPacket(
                SoundEvents.NOTE_BLOCK_BASS,
                SoundSource.BLOCKS,
                cx, cy, cz,
                0.7F, 0.55F,
                level.getRandom().nextLong()
        ));
    }

    public static synchronized boolean isFullPlayer(ServerPlayer player) {
        String uuid = player.getUUID().toString();
        String name = player.getName().getString();
        for (SubscriberEntry entry : PLAYERS_WHITELIST) {
            if ((!entry.uuid.isEmpty() && uuid.equalsIgnoreCase(entry.uuid)) || name.equalsIgnoreCase(entry.name)) {
                return true;
            }
        }
        return false;
    }

    private static void pacifyMobsAroundGuest(ServerPlayer guest) {
        double mobRadius = accessConfig.mobPacifyRadius;
        double creeperDistSq = accessConfig.creeperPacifyRadius * accessConfig.creeperPacifyRadius;
        for (Mob mob : guest.level().getEntitiesOfClass(Mob.class, guest.getBoundingBox().inflate(mobRadius))) {
            if (mob.getTarget() == guest || mob.getLastHurtByMob() == guest) {
                mob.setTarget(null);
                mob.setLastHurtByMob(null);
                mob.setLastHurtMob(null);
                mob.setAggressive(false);
                mob.getNavigation().stop();
            }

            var brain = mob.getBrain();
            if (brain.hasMemoryValue(MemoryModuleType.ATTACK_TARGET)) {
                var memTarget = brain.getMemory(MemoryModuleType.ATTACK_TARGET);
                if (memTarget.isPresent() && memTarget.get() == guest) {
                    brain.eraseMemory(MemoryModuleType.ATTACK_TARGET);
                    mob.setAggressive(false);
                    mob.getNavigation().stop();
                }
            }

            if (mob instanceof Creeper creeper && creeper.getSwellDir() > 0 && creeper.distanceToSqr(guest) <= creeperDistSq) {
                if (creeper.getTarget() == null || creeper.getTarget() == guest) {
                    creeper.setTarget(null);
                    creeper.setSwellDir(-1);
                }
            }
        }
    }

    private static synchronized void loadSubscribers() {
        if (subscribersFilePath == null) return;
        PLAYERS_WHITELIST.clear();
        if (!Files.exists(subscribersFilePath)) {
            saveSubscribers();
            return;
        }
        try (Reader reader = Files.newBufferedReader(subscribersFilePath, StandardCharsets.UTF_8)) {
            Type listType = new TypeToken<List<SubscriberEntry>>() {}.getType();
            List<SubscriberEntry> loaded = GSON.fromJson(reader, listType);
            if (loaded != null) {
                PLAYERS_WHITELIST.addAll(loaded);
            }
        } catch (IOException e) {
            LOGGER.error("Помилка читання subscribers.json", e);
        }
    }

    private static synchronized void saveSubscribers() {
        if (subscribersFilePath == null) return;
        try (Writer writer = Files.newBufferedWriter(subscribersFilePath, StandardCharsets.UTF_8)) {
            GSON.toJson(PLAYERS_WHITELIST, writer);
        } catch (IOException e) {
            LOGGER.error("Помилка запису subscribers.json", e);
        }
    }

    private static synchronized boolean addPlayerEntry(String uuid, String name, String source) {
        for (SubscriberEntry entry : PLAYERS_WHITELIST) {
            if ((!uuid.isEmpty() && uuid.equalsIgnoreCase(entry.uuid)) || name.equalsIgnoreCase(entry.name)) {
                return false;
            }
        }
        String created = ZonedDateTime.now().format(DATE_FORMAT);
        PLAYERS_WHITELIST.add(new SubscriberEntry(uuid, name, created, source));
        saveSubscribers();
        return true;
    }

    private static synchronized boolean removePlayerEntry(String name, String uuid) {
        boolean removed = PLAYERS_WHITELIST.removeIf(entry ->
                entry.name.equalsIgnoreCase(name) || (!uuid.isEmpty() && uuid.equalsIgnoreCase(entry.uuid))
        );
        if (removed) {
            saveSubscribers();
        }
        return removed;
    }

    private static synchronized void updateUuidAndNameOnJoin(ServerPlayer player) {
        String uuid = player.getUUID().toString();
        String name = player.getName().getString();
        boolean changed = false;
        for (SubscriberEntry entry : PLAYERS_WHITELIST) {
            if (uuid.equalsIgnoreCase(entry.uuid) || name.equalsIgnoreCase(entry.name)) {
                if (!uuid.equals(entry.uuid) || !name.equals(entry.name)) {
                    entry.uuid = uuid;
                    entry.name = name;
                    changed = true;
                }
            }
        }
        if (changed) {
            saveSubscribers();
        }
    }

    private static void teleportBackToMayak(ServerPlayer player, MinecraftServer server) {
        ServerLevel mayakLevel = server.getLevel(MAYAK_DIM);
        if (mayakLevel != null) {
            player.teleportTo(mayakLevel, getMayakSpawnX(), getMayakSpawnY(), getMayakSpawnZ(), Set.of(), player.getYRot(), player.getXRot(), true);
            sendGuestWarning(player);
        }
    }

    public static synchronized double getMayakSpawnX() {
        return mayakSpawnConfig.x;
    }

    public static synchronized double getMayakSpawnY() {
        return mayakSpawnConfig.y;
    }

    public static synchronized double getMayakSpawnZ() {
        return mayakSpawnConfig.z;
    }

    public static synchronized void setMayakSpawn(double x, double y, double z) {
        mayakSpawnConfig = new MayakSpawnConfig(x, y, z);
        saveMayakSpawn();
    }

    private static synchronized void loadMayakSpawn() {
        if (mayakSpawnConfigPath == null) return;
        if (!Files.exists(mayakSpawnConfigPath)) {
            saveMayakSpawn();
            return;
        }
        try (Reader reader = Files.newBufferedReader(mayakSpawnConfigPath, StandardCharsets.UTF_8)) {
            MayakSpawnConfig loaded = GSON.fromJson(reader, MayakSpawnConfig.class);
            if (loaded != null) {
                mayakSpawnConfig = loaded;
            }
        } catch (IOException e) {
            LOGGER.error("Помилка читання config/lhtrk_mayak.json", e);
        }
    }

    private static synchronized void saveMayakSpawn() {
        if (mayakSpawnConfigPath == null) return;
        try {
            Files.createDirectories(mayakSpawnConfigPath.getParent());
            try (Writer writer = Files.newBufferedWriter(mayakSpawnConfigPath, StandardCharsets.UTF_8)) {
                GSON.toJson(mayakSpawnConfig, writer);
            }
        } catch (IOException e) {
            LOGGER.error("Помилка запису config/lhtrk_mayak.json", e);
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

    private static void sendGuestWarning(ServerPlayer player) {
        long now = System.currentTimeMillis();
        long lastSent = MESSAGE_COOLDOWNS.getOrDefault(player.getUUID(), 0L);
        if (now - lastSent < 2000L) {
            return;
        }
        MESSAGE_COOLDOWNS.put(player.getUUID(), now);

        Component discordLink = Component.literal(accessConfig.msgDiscordLabel)
                .setStyle(Style.EMPTY
                        .withColor(0x00F5D4)
                        .withUnderlined(true)
                        .withClickEvent(new ClickEvent.OpenUrl(URI.create(accessConfig.discordUrl)))
                        .withHoverEvent(new HoverEvent.ShowText(Component.literal(accessConfig.msgDiscordHover)))
                );

        Component fullMessage = Component.literal(accessConfig.msgGuestWarningPrefix)
                .withColor(0xFF2A85)
                .append(discordLink);

        player.sendSystemMessage(fullMessage);
    }

    public static synchronized void reloadConfig() {
        loadSubscribers();
        loadMayakSpawn();
        loadAccessConfig();
    }

    private static synchronized void loadAccessConfig() {
        if (accessConfigPath == null) return;
        if (!Files.exists(accessConfigPath)) {
            saveAccessConfig();
            return;
        }
        try (Reader reader = Files.newBufferedReader(accessConfigPath, StandardCharsets.UTF_8)) {
            AccessConfig loaded = GSON.fromJson(reader, AccessConfig.class);
            if (loaded != null) {
                accessConfig = loaded;
            }
        } catch (IOException e) {
            LOGGER.error("Помилка читання config/lhtrk_access.json", e);
        }
    }

    private static synchronized void saveAccessConfig() {
        if (accessConfigPath == null) return;
        try {
            Files.createDirectories(accessConfigPath.getParent());
            try (Writer writer = Files.newBufferedWriter(accessConfigPath, StandardCharsets.UTF_8)) {
                GSON.toJson(accessConfig, writer);
            }
        } catch (IOException e) {
            LOGGER.error("Помилка запису config/lhtrk_access.json", e);
        }
    }
}