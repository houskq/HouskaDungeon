package cz.houska.dungeon;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;

import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.Block;
import org.bukkit.command.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;


import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

public final class HouskaDungeon extends JavaPlugin
        implements Listener, CommandExecutor, TabCompleter {

    private final Map<String, Dungeon> dungeons = new HashMap<>();

    /** Logické dungeon groupy (např. easy), které sdílí vstup, exit a cooldown. */
    private final Map<String, DungeonGroup> groups = new HashMap<>();

    private final Map<UUID, String> activeByPlayer = new HashMap<>();

    private final Map<UUID, UUID> dungeonOwner = new HashMap<>();

    private final Map<UUID, String> entityDungeon = new HashMap<>();

    private final Map<UUID, Integer> entityWave = new HashMap<>();

    private final Map<UUID, BossBar> bossBars = new HashMap<>();

    private final Map<String, Long> cooldowns = new HashMap<>();

    private final Map<UUID, Location> lastSafeLocation = new HashMap<>();

    private final Map<UUID, Selection> selections = new HashMap<>();

    /**
     * Dungeon, ve kterém hráč byl při předchozí kontrole.
     *
     * Používá se proto, aby hráč stojící uvnitř arény
     * nespouštěl dungeon znovu každých 10 ticků.
     */
    private final Map<UUID, String> playersInside = new HashMap<>();

    private ItemStack wand;

    private NamespacedKey dungeonMobKey;

    private NamespacedKey bossKey;

    private NamespacedKey infoKey;

    private File dataFile;

    private YamlConfiguration dataConfig;

    private File rewardsFile;
    private YamlConfiguration rewardsConfig;
    private File appearanceFile;
    private YamlConfiguration appearanceConfig;
    private File cooldownFile;
    private YamlConfiguration cooldownConfig;

    private BukkitTask particleTask;

    private BukkitTask dungeonEffectsTask;

    /** Teleporty provedené pluginem, které nesmí blokovat PlayerTeleportEvent. */
    private final Set<UUID> allowedTeleports = new HashSet<>();


    // =========================================================
    // ENABLE / DISABLE
    // =========================================================

    @Override
    public void onEnable() {

        saveDefaultConfig();

        dungeonMobKey =
                new NamespacedKey(
                        this,
                        "dungeon_mob"
                );

        bossKey =
                new NamespacedKey(
                        this,
                        "dungeon_boss"
                );

        infoKey =
                new NamespacedKey(
                        this,
                        "info"
                );

        dataFile =
                new File(
                        getDataFolder(),
                        "data.yml"
                );

        if (!dataFile.exists()) {

            try {

                dataFile.getParentFile().mkdirs();

                dataFile.createNewFile();

            } catch (IOException e) {

                getLogger().warning(
                        "Nelze vytvořit data.yml: "
                                + e.getMessage()
                );
            }
        }

        dataConfig =
                YamlConfiguration.loadConfiguration(
                        dataFile
                );

        loadExtraConfigs();

        loadGroups();
        loadDungeons();
        ensureArenaCooldownEntries();

        loadCooldowns();

        createWand();

        PluginCommand dungeonCommand =
                getCommand("dungeon");

        if (dungeonCommand != null) {

            dungeonCommand.setExecutor(this);

            dungeonCommand.setTabCompleter(this);
        }

        PluginCommand infoCommand =
                getCommand("spawndinfo");

        if (infoCommand != null) {

            infoCommand.setExecutor(this);

            infoCommand.setTabCompleter(this);
        }

        getServer()
                .getPluginManager()
                .registerEvents(
                        this,
                        this
                );

        /*
         * Kontrola vstupu hráčů.
         */
        Bukkit.getScheduler().runTaskTimer(
                this,
                this::tickPlayers,
                10L,
                10L
        );

        /*
         * Permanentní obrys všech dungeonů.
         *
         * Nezávisí na tom, jestli je dungeon ON nebo OFF.
         */
        particleTask =
                Bukkit.getScheduler().runTaskTimer(
                        this,
                        this::drawDungeonBorders,
                        10L,
                        20L
                );

        /*
         * Lehké průběžné efekty aktivního dungeonu.
         * Drží atmosféru živou, ale částice posíláme jen kolem
         * hráče a aktivních mobů, takže to zbytečně nezatěžuje celý server.
         */
        dungeonEffectsTask =
                Bukkit.getScheduler().runTaskTimer(
                        this,
                        this::tickDungeonEffects,
                        5L,
                        5L
                );

        /*
         * Po restartu znovu vytvoříme info hologramy.
         */
        for (DungeonGroup g : groups.values()) {
            Dungeon rep = representativeArena(g);
            if (g.info != null && rep != null) {
                rep.info = g.info.clone();
                spawnInfoDisplay(rep);
            }
        }

        getLogger().info(
                "HouskaDungeon enabled."
        );

        getLogger().info(
                "Načteno dungeonů: "
                        + dungeons.size()
        );
    }


    @Override
    public void onDisable() {

        if (particleTask != null) {

            particleTask.cancel();

            particleTask = null;
        }

        if (dungeonEffectsTask != null) {

            dungeonEffectsTask.cancel();

            dungeonEffectsTask = null;
        }

        for (Dungeon d :
                dungeons.values()) {

            if (d.activePlayer != null) {

                Player p =
                        Bukkit.getPlayer(
                                d.activePlayer
                        );

                if (p != null) {

                    safeTeleport(
                            p,
                            d.exit != null
                                    ? d.exit
                                    : d.spawn
                    );
                }
            }

            cleanupDungeonEntities(d);

            removeBossBar(d);

            d.activePlayer = null;
        }

        saveCooldowns();

        saveGroups();
        saveDungeons();

        removeAllInfoDisplays();
    }


    // =========================================================
    // UTIL
    // =========================================================

    private Component c(
            String text
    ) {

        return LegacyComponentSerializer
                .legacySection()
                .deserialize(text);
    }


    private String visual(String path, String fallback) {
        if (appearanceConfig == null) return fallback;
        return appearanceConfig.getString(path, fallback).replace('&', '§');
    }

    private boolean visualEnabled(String path, boolean fallback) {
        return appearanceConfig == null ? fallback : appearanceConfig.getBoolean(path, fallback);
    }

    private int visualInt(String path, int fallback) {
        return appearanceConfig == null ? fallback : appearanceConfig.getInt(path, fallback);
    }

    private double visualDouble(String path, double fallback) {
        return appearanceConfig == null ? fallback : appearanceConfig.getDouble(path, fallback);
    }

    private void playConfiguredSound(Player p, String path, Sound fallback, float volume, float pitch) {
        if (!visualEnabled(path + ".enabled", true)) return;
        Sound sound = fallback;
        String configured = appearanceConfig == null ? "" : appearanceConfig.getString(path + ".sound", "");
        if (configured != null && !configured.isBlank()) {
            try { sound = Sound.valueOf(configured.toUpperCase(Locale.ROOT)); } catch (IllegalArgumentException ignored) {}
        }
        float v = (float) visualDouble(path + ".volume", volume);
        float pi = (float) visualDouble(path + ".pitch", pitch);
        p.playSound(p.getLocation(), sound, v, pi);
    }

    private String visualMessage(String path, String fallback, Player p, Dungeon d) {
        return visual(path, fallback)
                .replace("%player%", p == null ? "" : p.getName())
                .replace("%arena%", d == null ? "" : d.id)
                .replace("%wave%", d == null ? "0" : String.valueOf(d.wave))
                .replace("%remaining%", d == null ? "0" : String.valueOf(d.remaining));
    }



    // =========================================================
    // WAND
    // =========================================================

    private void createWand() {

        wand =
                new ItemStack(
                        Material.BLAZE_ROD
                );

        ItemMeta meta =
                wand.getItemMeta();

        if (meta == null) {
            return;
        }

        meta.displayName(
                c("§5§lDungeon Wand")
        );

        meta.lore(
                List.of(
                        c("§7Levý klik §8→ §fPozice 1"),
                        c("§7Pravý klik §8→ §fPozice 2"),
                        c("§8Použij pouze s §5/dungeon create")
                )
        );

        wand.setItemMeta(meta);
    }


    private boolean isWand(
            ItemStack item
    ) {

        if (item == null ||
                !item.hasItemMeta() ||
                item.getType() != Material.BLAZE_ROD) {

            return false;
        }

        ItemMeta meta =
                item.getItemMeta();

        if (meta == null) {
            return false;
        }

        Component name =
                meta.displayName();

        return name != null &&
                name.toString().contains(
                        "Dungeon Wand"
                );
    }


    @EventHandler
    public void onWand(
            PlayerInteractEvent e
    ) {

        if (e.getAction() !=
                Action.LEFT_CLICK_BLOCK &&
                e.getAction() !=
                        Action.RIGHT_CLICK_BLOCK) {

            return;
        }

        if (!isWand(e.getItem())) {
            return;
        }

        if (!e.getPlayer().hasPermission(
                "houskadungeon.admin"
        )) {

            return;
        }

        e.setCancelled(true);

        Block b =
                e.getClickedBlock();

        if (b == null) {
            return;
        }

        Selection s =
                selections.computeIfAbsent(
                        e.getPlayer().getUniqueId(),
                        x -> new Selection()
                );

        if (e.getAction() ==
                Action.LEFT_CLICK_BLOCK) {

            s.one =
                    b.getLocation();

            e.getPlayer().sendMessage(
                    "§5§lDungeon §8» §fPozice §d1 §fnastavena: §7"
                            + formatLoc(
                            b.getLocation()
                    )
            );

        } else {

            s.two =
                    b.getLocation();

            e.getPlayer().sendMessage(
                    "§5§lDungeon §8» §fPozice §d2 §fnastavena: §7"
                            + formatLoc(
                            b.getLocation()
                    )
            );
        }
    }


    // =========================================================
    // PLAYER / DUNGEON DETECTION
    // =========================================================

    private void tickPlayers() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            UUID uuid = p.getUniqueId();
            if (activeByPlayer.containsKey(uuid)) continue;

            DungeonGroup group = findWall(p.getLocation());
            String previous = playersInside.get(uuid);

            if (group == null) {
                playersInside.remove(uuid);
                lastSafeLocation.put(uuid, p.getLocation().clone());
                continue;
            }

            if (group.id.equals(previous)) continue;
            playersInside.put(uuid, group.id);

            if (!group.enabled) {
                p.sendActionBar(c("§7Dungeon §d" + group.id + " §7je momentálně §cVYPNUTÝ"));
                continue;
            }

            long remaining = cooldownRemaining(uuid, group.id);
            if (remaining > 0) {
                playersInside.remove(uuid);
                p.sendMessage("§5§lDungeon §8» §cNa dungeon §d" + group.id + " §cmáš cooldown §d" + formatDuration(remaining));
                continue;
            }

            Dungeon arena = findFreeArena(group);
            if (arena == null) {
                playersInside.remove(uuid);
                p.sendMessage("§5§lDungeon §8» §cVšechny arény pro §d" + group.id + " §cjsou právě obsazené.");
                continue;
            }

            if (arena.spawn == null) {
                playersInside.remove(uuid);
                p.sendMessage("§cVybraná aréna není kompletně nastavená.");
                continue;
            }

            startDungeon(arena, p);
        }
    }

    /** Zachovává původní funkční SETWALL hitbox detekci, ale hledá GROUP wall. */
    private DungeonGroup findWall(Location l) {
        if (l == null || l.getWorld() == null) return null;
        final double horizontalReach = 0.36;
        final double minY = l.getY();
        final double maxY = l.getY() + 1.80;

        for (DungeonGroup g : groups.values()) {
            if (!g.hasWall() || g.wallWorld == null || !g.wallWorld.equals(l.getWorld().getName())) continue;
            double playerMinX = l.getX() - horizontalReach;
            double playerMaxX = l.getX() + horizontalReach;
            double playerMinZ = l.getZ() - horizontalReach;
            double playerMaxZ = l.getZ() + horizontalReach;
            double wallMinX = g.wallMinX;
            double wallMaxX = g.wallMaxX + 1.0;
            double wallMinY = g.wallMinY;
            double wallMaxY = g.wallMaxY + 1.0;
            double wallMinZ = g.wallMinZ;
            double wallMaxZ = g.wallMaxZ + 1.0;
            boolean overlapsX = playerMaxX >= wallMinX && playerMinX <= wallMaxX;
            boolean overlapsY = maxY >= wallMinY && minY <= wallMaxY;
            boolean overlapsZ = playerMaxZ >= wallMinZ && playerMinZ <= wallMaxZ;
            if (overlapsX && overlapsY && overlapsZ) return g;
        }
        return null;
    }

    private Dungeon findFreeArena(DungeonGroup group) {
        if (group == null) return null;
        return group.arenas.stream()
                .map(dungeons::get)
                .filter(Objects::nonNull)
                .filter(d -> d.enabled && d.spawn != null && d.activePlayer == null)
                .min(Comparator.comparing(d -> d.id))
                .orElse(null);
    }

    private DungeonGroup groupOf(Dungeon d) {
        return d == null || d.groupId == null ? null : groups.get(d.groupId);
    }

    private String cooldownKey(Dungeon d) {
        DungeonGroup g = groupOf(d);
        return g == null ? d.id : g.id;
    }

    private Dungeon representativeArena(DungeonGroup g) {
        if (g == null) return null;
        for (String id : g.arenas) {
            Dungeon d = dungeons.get(id);
            if (d != null) return d;
        }
        return null;
    }

    private Dungeon findContainingDungeon(
            Location l
    ) {

        Dungeon result = null;

        for (Dungeon d :
                dungeons.values()) {

            if (d.contains(l)) {

                if (result == null ||
                        d.volume() <
                                result.volume()) {

                    result = d;
                }
            }
        }

        return result;
    }


    // =========================================================
    // START DUNGEON
    // =========================================================

    private void startDungeon(
            Dungeon d,
            Player p
    ) {

        if (!d.enabled) {
            return;
        }

        if (d.spawn == null) {

            p.sendMessage(
                    "§cDungeon nemá nastavený spawn."
            );

            return;
        }

        if (d.activePlayer != null) {

            return;
        }

        d.activePlayer =
                p.getUniqueId();

        d.wave = 0;

        d.remaining = 0;
        d.waveTotal = 0;
        d.waveSpawned = 0;
        d.waveKilled = 0;
        d.transitioning = false;

        d.spawned =
                new HashSet<>();

        d.completed = false;

        d.boss = null;

        activeByPlayer.put(
                p.getUniqueId(),
                d.id
        );

        dungeonOwner.put(
                p.getUniqueId(),
                p.getUniqueId()
        );

        Location start =
                d.spawn.clone();

        p.teleport(start);

        p.sendMessage("§5§lDUNGEON §8» §fVstoupil jsi do §d" + cooldownKey(d) + " §8(§7arena " + d.id + "§8)§f.");

        p.sendMessage(
                "§8Dungeon je nyní §cOBSAZENÝ§8."
        );

        countdown(
                p,
                d
        );
    }


    private void countdown(
            Player p,
            Dungeon d
    ) {

        for (int i = 5; i >= 1; i--) {

            final int n = i;

            Bukkit.getScheduler().runTaskLater(
                    this,
                    () -> {

                        if (!isActive(d, p)) {
                            return;
                        }

                        p.showTitle(
                                Title.title(
                                        c("§5" + n),
                                        c("§dDungeon se připravuje...")
                                )
                        );

                        p.playSound(
                                p.getLocation(),
                                Sound.BLOCK_NOTE_BLOCK_PLING,
                                1f,
                                0.8f +
                                        (5 - n) * 0.12f
                        );

                        p.spawnParticle(
                                Particle.PORTAL,
                                p.getLocation()
                                        .add(0, 1, 0),
                                80,
                                0.8,
                                1,
                                0.8,
                                0.1
                        );

                    },
                    (5 - i) * 20L
            );
        }

        Bukkit.getScheduler().runTaskLater(
                this,
                () -> {

                    if (!isActive(d, p)) {
                        return;
                    }

                    p.showTitle(
                            Title.title(
                                    c("§5§lDUNGEON"),
                                    c("§fZačíná první vlna!")
                            )
                    );

                    p.playSound(
                            p.getLocation(),
                            Sound.ENTITY_ENDER_DRAGON_GROWL,
                            0.5f,
                            1.6f
                    );

                    startWave(
                            d,
                            1
                    );

                },
                100L
        );
    }


    private boolean isActive(
            Dungeon d,
            Player p
    ) {

        return d.activePlayer != null &&
                d.activePlayer.equals(
                        p.getUniqueId()
                );
    }


    // =========================================================
    // WAVES
    // =========================================================

    private void startWave(
            Dungeon d,
            int wave
    ) {

        Player p =
                d.activePlayer == null
                        ? null
                        : Bukkit.getPlayer(
                        d.activePlayer
                );

        if (p == null) {

            endDungeon(
                    d,
                    false
            );

            return;
        }

        d.wave = wave;

        d.waveTotal = getWaveCount(wave);
        d.waveSpawned = 0;
        d.waveKilled = 0;
        d.remaining = d.waveTotal;
        d.transitioning = false;

        d.spawned.clear();

        showWaveAnimatedTitle(p, wave);

        playConfiguredSound(p, "sounds.wave-start", Sound.ENTITY_WITHER_SPAWN, 0.55f, 1.8f);

        // Velký nástup vlny.
        p.spawnParticle(
                Particle.REVERSE_PORTAL,
                p.getLocation().add(0, 1, 0),
                180,
                1.4,
                1.2,
                1.4,
                0.12
        );

        p.spawnParticle(
                Particle.END_ROD,
                p.getLocation().add(0, 1, 0),
                55,
                0.8,
                1.0,
                0.8,
                0.08
        );

        // Nevyvoláváme všech 15/20 mobů najednou.
        // Na aréně jsou současně max 4 a po zabití se okamžitě doplní.
        int initial = Math.min(4, d.remaining);

        for (int i = 0; i < initial; i++) {

            final int index = i;

            Bukkit.getScheduler().runTaskLater(
                    this,
                    () -> {

                        if (d.activePlayer == null ||
                                d.wave != wave ||
                                d.remaining <= 0) {
                            return;
                        }

                        Player player =
                                Bukkit.getPlayer(
                                        d.activePlayer
                                );

                        if (player == null) {
                            return;
                        }

                        spawnWaveMobWithRetry(
                                d,
                                wave,
                                player,
                                index,
                                0
                        );

                    },
                    2L + index * 4L
            );
        }

        updateBossBar(d);
    }


    /**
     * Spawn jednoho mobu. Pokud se zrovna nenajde vhodné místo,
     * spawn se několikrát zopakuje přímo v aktivní aréně.
     */
    private void spawnWaveMobWithRetry(
            Dungeon d,
            int wave,
            Player player,
            int index,
            int attempt
    ) {

        if (d.activePlayer == null ||
                d.wave != wave ||
                !player.isOnline() ||
                d.remaining <= 0 ||
                d.waveSpawned >= d.waveTotal) {
            return;
        }

        // Nikdy nedržíme víc než 4 živé wave moby současně.
        if (d.spawned.size() >= 4) {
            Bukkit.getScheduler().runTaskLater(this,
                    () -> spawnWaveMobWithRetry(d, wave, player, index, attempt), 3L);
            return;
        }

        Location spawn =
                findSpawnLocation(
                        d,
                        null
                );

        if (spawn == null) {

            if (attempt < 20) {

                Bukkit.getScheduler().runTaskLater(
                        this,
                        () -> spawnWaveMobWithRetry(
                                d,
                                wave,
                                player,
                                index,
                                attempt + 1
                        ),
                        3L
                );

            } else {

                // Poslední pojistka: zkusíme znovu za chvíli.
                Bukkit.getScheduler().runTaskLater(
                        this,
                        () -> spawnWaveMobWithRetry(
                                d,
                                wave,
                                player,
                                index,
                                0
                        ),
                        10L
                );
            }

            return;
        }

        if (spawnDungeonMob(
                d,
                wave,
                spawn
        )) {
            d.waveSpawned++;
        }
    }


    private void showWaveAnimatedTitle(Player p, int wave) {
        String title = visual("wave.title", "⚔ WAVE %wave% ⚔").replace("%wave%", String.valueOf(wave));
        String subtitle = visual("wave.subtitle-" + wave, getWaveName(wave));
        List<String> colors = getColorway("wave.colorways." + wave, defaultWaveColors(wave));
        long step = Math.max(1L, appearanceConfig == null ? 2L : appearanceConfig.getLong("wave.rgb.speed-ticks", 2L));
        int frames = Math.max(8, appearanceConfig == null ? 20 : appearanceConfig.getInt("wave.rgb.frames", 20));

        for (int i = 0; i < frames; i++) {
            final int phase = i;
            Bukkit.getScheduler().runTaskLater(this, () -> {
                if (!p.isOnline()) return;
                Component rgbTitle = gradientText(stripLegacy(title), colors, phase, true);
                p.showTitle(Title.title(rgbTitle, c(subtitle)));
            }, i * step);
        }
    }

    private List<String> defaultWaveColors(int wave) {
        return switch (wave) {
            case 1 -> List.of("#063B20", "#0B7A3E", "#22C965", "#8CFFB1", "#22C965");
            case 2 -> List.of("#003B66", "#007ACC", "#00C8FF", "#B5F2FF", "#00A3FF");
            case 3 -> List.of("#8A2E00", "#FF6A00", "#FFB800", "#FFF09A", "#FF9500");
            case 4 -> List.of("#61001D", "#C4003D", "#FF174F", "#FF9BC3", "#FF3975");
            default -> List.of("#7A00FF", "#C15CFF", "#F0B0FF");
        };
    }

    private List<String> getColorway(String path, List<String> fallback) {
        if (appearanceConfig == null) return fallback;
        List<String> list = appearanceConfig.getStringList(path);
        return list == null || list.isEmpty() ? fallback : list;
    }

    private Component gradientText(String text, List<String> colors, int phase, boolean bold) {
        if (text == null) text = "";
        if (colors == null || colors.isEmpty()) return Component.text(text);
        Component out = Component.empty();
        int visible = 0;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '§' && i + 1 < text.length()) { i++; continue; }
            String hex = colors.get(Math.floorMod(visible + phase, colors.size()));
            TextColor color = TextColor.fromHexString(hex);
            Component part = Component.text(String.valueOf(ch), color == null ? TextColor.color(255,255,255) : color);
            if (bold) part = part.decorate(TextDecoration.BOLD);
            out = out.append(part);
            if (!Character.isWhitespace(ch)) visible++;
        }
        return out;
    }

    private String stripLegacy(String text) {
        if (text == null) return "";
        return text.replaceAll("(?i)[&§][0-9A-FK-OR]", "");
    }

    private int getWaveCount(
            int wave
    ) {

        int base = getConfig().getInt("waves.base-mobs", 10);
        int add = getConfig().getInt("waves.add-per-wave", 10);
        if (wave < 1 || wave > 4) return 0;
        return base + ((wave - 1) * add);
    }


    private String getWaveName(
            int wave
    ) {

        return switch (wave) {

            case 1 ->
                    "§aLehká vlna";

            case 2 ->
                    "§eStřední vlna";

            case 3 ->
                    "§6Těžká vlna";

            case 4 ->
                    "§cFINÁLNÍ VLNA";

            default ->
                    "";
        };
    }


    // =========================================================
    // RANDOM SPAWN
    // =========================================================

    private Location findSpawnLocation(
            Dungeon d,
            Location player
    ) {

        ThreadLocalRandom r =
                ThreadLocalRandom.current();

        World w =
                Bukkit.getWorld(
                        d.world
                );

        if (w == null) {
            return null;
        }

        // Chunky arény mohou mít část okraje mimo právě načtený chunk.
        // Chunk si před kontrolou místa načteme.
        for (int attempt = 0; attempt < 160; attempt++) {

            int x =
                    r.nextInt(
                            d.minX,
                            d.maxX + 1
                    );

            int z =
                    r.nextInt(
                            d.minZ,
                            d.maxZ + 1
                    );

            int y =
                    r.nextInt(
                            Math.max(d.minY + 1, w.getMinHeight() + 1),
                            Math.min(d.maxY + 1, w.getMaxHeight() - 1)
                    );

            Chunk chunk = w.getChunkAt(x >> 4, z >> 4);
            if (!chunk.isLoaded()) {
                chunk.load();
            }

            for (int yy = y;
                 yy <= Math.min(d.maxY, y + 12);
                 yy++) {

                if (yy <= d.minY) {
                    continue;
                }

                Block feet =
                        w.getBlockAt(
                                x,
                                yy,
                                z
                        );

                Block head =
                        w.getBlockAt(
                                x,
                                yy + 1,
                                z
                        );

                Block below =
                        w.getBlockAt(
                                x,
                                yy - 1,
                                z
                        );

                if (!below.getType().isSolid()) {
                    continue;
                }

                if (!feet.isPassable() ||
                        !head.isPassable()) {
                    continue;
                }

                if (feet.isLiquid() ||
                        head.isLiquid()) {
                    continue;
                }

                Location candidate =
                        new Location(
                                w,
                                x + 0.5,
                                yy,
                                z + 0.5
                        );

                // 16 bloků bylo na menších arénách moc.
                // Držíme moby mimo hráče, ale dovolíme spawn i v menší aréně.
                double minDistance =
                        Math.min(10.0, Math.max(4.0, Math.min(
                                Math.max(4.0, (d.maxX - d.minX) / 2.0),
                                Math.max(4.0, (d.maxZ - d.minZ) / 2.0)
                        )));

                if (player != null && player.getWorld() == candidate.getWorld()
                        && candidate.distanceSquared(player) < minDistance * minDistance) {
                    continue;
                }

                return candidate;
            }
        }

        // Pokud je aréna opravdu malá, dovolíme bezpečný spawn i blíž.
        // Jinak by se dungeon mohl zaseknout na hledání místa.
        for (int attempt = 0; attempt < 100; attempt++) {

            int x = r.nextInt(d.minX, d.maxX + 1);
            int z = r.nextInt(d.minZ, d.maxZ + 1);

            for (int yy = d.minY + 1; yy <= d.maxY; yy++) {

                Block feet = w.getBlockAt(x, yy, z);
                Block head = w.getBlockAt(x, yy + 1, z);
                Block below = w.getBlockAt(x, yy - 1, z);

                if (below.getType().isSolid() &&
                        feet.isPassable() &&
                        head.isPassable() &&
                        !feet.isLiquid() &&
                        !head.isLiquid()) {

                    return new Location(w, x + 0.5, yy, z + 0.5);
                }
            }
        }

        return null;
    }


    // =========================================================
    // MOB SPAWN
    // =========================================================

    private boolean spawnDungeonMob(
            Dungeon d,
            int wave,
            Location loc
    ) {

        World w =
                loc.getWorld();

        if (w == null) {
            return false;
        }

        EntityType type =
                chooseMob(wave);

        Entity entity =
                w.spawnEntity(
                        loc,
                        type
                );

        if (!(entity instanceof LivingEntity mob)) {
            return false;
        }

        mob.getPersistentDataContainer()
                .set(
                        dungeonMobKey,
                        PersistentDataType.STRING,
                        d.id
                );

        entityDungeon.put(
                mob.getUniqueId(),
                d.id
        );

        entityWave.put(
                mob.getUniqueId(),
                wave
        );

        d.spawned.add(
                mob.getUniqueId()
        );

        configureMob(
                mob,
                wave
        );

        // Mob po spawnu nedostává loot a hned je vidět, kde se objevil.
        if (mob.getEquipment() != null) {
            mob.getEquipment().setHelmetDropChance(0f);
            mob.getEquipment().setChestplateDropChance(0f);
            mob.getEquipment().setLeggingsDropChance(0f);
            mob.getEquipment().setBootsDropChance(0f);
            mob.getEquipment().setItemInMainHandDropChance(0f);
            mob.getEquipment().setItemInOffHandDropChance(0f);
        }

        loc.getWorld().spawnParticle(
                Particle.REVERSE_PORTAL,
                loc.clone().add(0, 0.8, 0),
                45,
                0.35,
                0.6,
                0.35,
                0.08
        );

        loc.getWorld().spawnParticle(
                Particle.SOUL_FIRE_FLAME,
                loc.clone().add(0, 0.9, 0),
                18,
                0.25,
                0.55,
                0.25,
                0.02
        );

        loc.getWorld().playSound(
                loc,
                Sound.ENTITY_ENDERMAN_TELEPORT,
                0.45f,
                1.55f
        );

        mob.setRemoveWhenFarAway(false);

        mob.setCanPickupItems(false);

        if (d.remaining <= 5) mob.setGlowing(true);
        return true;
    }


    private EntityType chooseMob(int wave) {
        // Stejný pool mobů pro všechny wave. Další wave mají hlavně VÍCE mobů.
        int n = ThreadLocalRandom.current().nextInt(100);
        if (n < 30) return EntityType.ZOMBIE;
        if (n < 50) return EntityType.SPIDER;
        if (n < 70) return EntityType.SKELETON;
        if (n < 85) return EntityType.HUSK;
        return EntityType.WITHER_SKELETON;
    }

    // =========================================================
    // MOB CONFIG
    // =========================================================

    private void configureMob(
            LivingEntity mob,
            int wave
    ) {

        double hp =
                switch (wave) {

                    case 1 -> 20.0;

                    case 2 -> 26.0;

                    case 3 -> 34.0;

                    case 4 -> 42.0;

                    default -> 20.0;
                };

        Attribute max =
                Attribute.MAX_HEALTH;

        if (mob.getAttribute(max) != null) {

            mob.getAttribute(max)
                    .setBaseValue(hp);

            mob.setHealth(hp);
        }

        double speed =
                switch (wave) {

                    case 1 -> 0.22;

                    case 2 -> 0.24;

                    case 3 -> 0.26;

                    case 4 -> 0.28;

                    default -> 0.22;
                };

        if (mob.getAttribute(
                Attribute.MOVEMENT_SPEED
        ) != null) {

            mob.getAttribute(
                    Attribute.MOVEMENT_SPEED
            ).setBaseValue(speed);
        }

        if (wave >= 2 &&
                ThreadLocalRandom.current()
                        .nextInt(100) < 35) {

            giveArmor(
                    mob,
                    wave
            );
        }

        if (wave >= 2 &&
                ThreadLocalRandom.current()
                        .nextInt(100) < 22) {

            mob.addPotionEffect(
                    new PotionEffect(
                            PotionEffectType.SPEED,
                            Integer.MAX_VALUE,
                            wave >= 3
                                    ? 1
                                    : 0,
                            false,
                            false,
                            true
                    )
            );
        }

        if (wave >= 3 &&
                ThreadLocalRandom.current()
                        .nextInt(100) < 18) {

            mob.addPotionEffect(
                    new PotionEffect(
                            PotionEffectType.STRENGTH,
                            Integer.MAX_VALUE,
                            0,
                            false,
                            false,
                            true
                    )
            );
        }

        if (wave >= 4 &&
                ThreadLocalRandom.current()
                        .nextInt(100) < 20) {

            mob.addPotionEffect(
                    new PotionEffect(
                            PotionEffectType.RESISTANCE,
                            Integer.MAX_VALUE,
                            0,
                            false,
                            false,
                            true
                    )
            );
        }
    }


    private void giveArmor(
            LivingEntity mob,
            int wave
    ) {

        ItemStack helmet =
                new ItemStack(
                        wave >= 4
                                ? Material.IRON_HELMET
                                : Material.CHAINMAIL_HELMET
                );

        ItemStack chest =
                new ItemStack(
                        wave >= 4
                                ? Material.IRON_CHESTPLATE
                                : Material.CHAINMAIL_CHESTPLATE
                );

        ItemStack legs =
                new ItemStack(
                        wave >= 4
                                ? Material.IRON_LEGGINGS
                                : Material.CHAINMAIL_LEGGINGS
                );

        ItemStack boots =
                new ItemStack(
                        wave >= 4
                                ? Material.IRON_BOOTS
                                : Material.CHAINMAIL_BOOTS
                );

        if (wave >= 3) {

            helmet.addUnsafeEnchantment(
                    Enchantment.PROTECTION,
                    1
            );

            chest.addUnsafeEnchantment(
                    Enchantment.PROTECTION,
                    1
            );

            legs.addUnsafeEnchantment(
                    Enchantment.PROTECTION,
                    1
            );

            boots.addUnsafeEnchantment(
                    Enchantment.PROTECTION,
                    1
            );
        }

        if (mob.getEquipment() == null) {
            return;
        }

        mob.getEquipment()
                .setHelmet(helmet);

        mob.getEquipment()
                .setChestplate(chest);

        mob.getEquipment()
                .setLeggings(legs);

        mob.getEquipment()
                .setBoots(boots);

        mob.getEquipment()
                .setHelmetDropChance(0);

        mob.getEquipment()
                .setChestplateDropChance(0);

        mob.getEquipment()
                .setLeggingsDropChance(0);

        mob.getEquipment()
                .setBootsDropChance(0);

        if (mob instanceof Zombie ||
                mob instanceof Husk ||
                mob instanceof WitherSkeleton) {

            ItemStack weapon =
                    new ItemStack(
                            wave >= 4
                                    ? Material.IRON_SWORD
                                    : Material.STONE_SWORD
                    );

            if (wave >= 3) {

                weapon.addUnsafeEnchantment(
                        Enchantment.SHARPNESS,
                        1
                );
            }

            mob.getEquipment()
                    .setItemInMainHand(
                            weapon
                    );

            mob.getEquipment()
                    .setItemInMainHandDropChance(
                            0
                    );
        }
    }


    // =========================================================
    // MOB DEATH
    // =========================================================

    @EventHandler(
            priority = EventPriority.HIGH
    )
    public void onDeath(
            EntityDeathEvent e
    ) {

        LivingEntity entity =
                e.getEntity();

        UUID id =
                entity.getUniqueId();

        /*
         * Boss řeší vlastní handler níže.
         */
        if (entity.getPersistentDataContainer()
                .has(
                        bossKey,
                        PersistentDataType.STRING
                )) {

            return;
        }

        String dungeonId =
                entityDungeon.remove(id);

        Integer wave =
                entityWave.remove(id);

        if (dungeonId == null ||
                wave == null) {

            return;
        }

        Dungeon d =
                dungeons.get(
                        dungeonId
                );

        if (d == null ||
                d.activePlayer == null) {

            return;
        }

        // Dungeon mobové nikdy nepouští vanilla loot ani XP dropy z tohoto handleru.
        e.getDrops().clear();
        e.setDroppedExp(0);

        d.spawned.remove(id);

        Location deathLoc = entity.getLocation();
        World deathWorld = deathLoc.getWorld();
        if (deathWorld != null) {
            deathWorld.spawnParticle(
                    Particle.POOF,
                    deathLoc.clone().add(0, 0.8, 0),
                    35,
                    0.35,
                    0.55,
                    0.35,
                    0.05
            );
            deathWorld.spawnParticle(
                    Particle.SOUL,
                    deathLoc.clone().add(0, 0.9, 0),
                    12,
                    0.2,
                    0.45,
                    0.2,
                    0.03
            );
            deathWorld.spawnParticle(
                    Particle.CRIT,
                    deathLoc.clone().add(0, 1.0, 0),
                    18,
                    0.45,
                    0.45,
                    0.45,
                    0.15
            );
            deathWorld.playSound(
                    deathLoc,
                    Sound.ENTITY_PLAYER_ATTACK_CRIT,
                    0.75f,
                    1.25f
            );
        }

        Player killer =
                entity.getKiller();

        if (killer != null &&
                killer.getUniqueId().equals(
                        d.activePlayer
                )) {

            double reward = rewardsConfig == null
                    ? getConfig().getDouble("rewards.wave-" + wave, 0)
                    : rewardsConfig.getDouble("waves." + wave + ".money-per-kill", 0);

            if (reward > 0) {
                giveMoney(killer, reward);
                String rewardBar = visual("reward.money-actionbar", "§a+$%money% §8• §fWave %wave%")
                        .replace("%money%", formatMoney(reward))
                        .replace("%wave%", String.valueOf(wave));
                killer.sendActionBar(c(rewardBar));
            }
        }

        d.waveKilled = Math.min(d.waveTotal, d.waveKilled + 1);
        d.remaining = Math.max(0, d.waveTotal - d.waveKilled);

        updateLastFiveGlow(d);

        // Doplňujeme jen moby, kteří ještě opravdu nebyli spawnuti.
        if (d.remaining > 0 && d.waveSpawned < d.waveTotal && d.activePlayer != null) {
            Player active = Bukkit.getPlayer(d.activePlayer);
            if (active != null) {
                Bukkit.getScheduler().runTaskLater(
                        this,
                        () -> spawnWaveMobWithRetry(d, wave, active, d.waveSpawned, 0),
                        2L
                );
            }
        }

        updateBossBar(d);

        if (d.waveKilled >= d.waveTotal && d.spawned.isEmpty() && !d.transitioning) {
            d.transitioning = true;
            Player rewardPlayer = Bukkit.getPlayer(d.activePlayer);
            if (rewardPlayer != null) {
                executeRewardCommands("waves." + wave + ".commands", rewardPlayer, d, wave);
                sendConfiguredMessage(rewardPlayer, "waves." + wave + ".message", "§aWave " + wave + " dokončena!", d, wave);
            }

            Bukkit.getScheduler().runTask(
                    this,
                    () -> {

                        if (d.activePlayer == null) {
                            return;
                        }

                        if (wave < 4) {

                            Player p =
                                    Bukkit.getPlayer(
                                            d.activePlayer
                                    );

                            if (p != null) {

                                p.showTitle(
                                        Title.title(
                                                c(
                                                        "§a§lWAVE "
                                                                + wave
                                                                + " SPLNĚNA"
                                                ),
                                                c(
                                                        "§fDalší vlna se připravuje..."
                                                )
                                        )
                                );

                                p.playSound(
                                        p.getLocation(),
                                        Sound.UI_TOAST_CHALLENGE_COMPLETE,
                                        1f,
                                        1.15f
                                );
                            }

                            Bukkit.getScheduler()
                                    .runTaskLater(
                                            this,
                                            () -> {

                                                if (d.activePlayer != null) {

                                                    startWave(
                                                            d,
                                                            wave + 1
                                                    );
                                                }
                                            },
                                            60L
                                    );

                        } else {

                            spawnBoss(d);
                        }
                    }
            );
        }
    }


    private void updateLastFiveGlow(Dungeon d) {
        if (d.remaining > 5) return;
        for (UUID mobId : new HashSet<>(d.spawned)) {
            Entity ent = Bukkit.getEntity(mobId);
            if (ent instanceof LivingEntity le && !le.isDead()) le.setGlowing(true);
        }
    }


    // =========================================================
    // BOSS
    // =========================================================

    private void spawnBoss(
            Dungeon d
    ) {

        Player p =
                d.activePlayer == null
                        ? null
                        : Bukkit.getPlayer(
                        d.activePlayer
                );

        if (p == null) {

            endDungeon(
                    d,
                    false
            );

            return;
        }

        Location loc =
                findSpawnLocation(
                        d,
                        null
                );

        if (loc == null &&
                d.spawn != null) {

            loc =
                    d.spawn.clone();
        }

        if (loc == null) {

            endDungeon(
                    d,
                    false
            );

            return;
        }

        World world =
                loc.getWorld();

        if (world == null) {

            endDungeon(
                    d,
                    false
            );

            return;
        }

        Entity entity =
                world.spawnEntity(
                        loc,
                        EntityType.WITHER_SKELETON
                );

        if (!(entity instanceof LivingEntity boss)) {

            endDungeon(
                    d,
                    false
            );

            return;
        }

        boss.getPersistentDataContainer()
                .set(
                        bossKey,
                        PersistentDataType.STRING,
                        d.id
                );

        d.boss =
                boss.getUniqueId();

        entityDungeon.put(
                boss.getUniqueId(),
                d.id
        );

        if (boss.getAttribute(
                Attribute.MAX_HEALTH
        ) != null) {

            boss.getAttribute(
                    Attribute.MAX_HEALTH
            ).setBaseValue(100.0);

            boss.setHealth(100.0);
        }

        if (boss.getAttribute(
                Attribute.MOVEMENT_SPEED
        ) != null) {

            boss.getAttribute(
                    Attribute.MOVEMENT_SPEED
            ).setBaseValue(0.30);
        }

        boss.customName(
                c("§5§lBOSS")
        );

        boss.setCustomNameVisible(true);

        boss.addPotionEffect(
                new PotionEffect(
                        PotionEffectType.STRENGTH,
                        Integer.MAX_VALUE,
                        1,
                        false,
                        false,
                        true
                )
        );

        boss.addPotionEffect(
                new PotionEffect(
                        PotionEffectType.RESISTANCE,
                        Integer.MAX_VALUE,
                        0,
                        false,
                        false,
                        true
                )
        );

        boss.addPotionEffect(
                new PotionEffect(
                        PotionEffectType.SPEED,
                        Integer.MAX_VALUE,
                        0,
                        false,
                        false,
                        true
                )
        );

        ItemStack helmet =
                new ItemStack(
                        Material.DIAMOND_HELMET
                );

        ItemStack chest =
                new ItemStack(
                        Material.DIAMOND_CHESTPLATE
                );

        ItemStack legs =
                new ItemStack(
                        Material.DIAMOND_LEGGINGS
                );

        ItemStack boots =
                new ItemStack(
                        Material.DIAMOND_BOOTS
                );

        ItemStack sword =
                new ItemStack(
                        Material.DIAMOND_SWORD
                );

        sword.addUnsafeEnchantment(
                Enchantment.SHARPNESS,
                2
        );

        helmet.addUnsafeEnchantment(
                Enchantment.PROTECTION,
                2
        );

        chest.addUnsafeEnchantment(
                Enchantment.PROTECTION,
                2
        );

        legs.addUnsafeEnchantment(
                Enchantment.PROTECTION,
                2
        );

        boots.addUnsafeEnchantment(
                Enchantment.PROTECTION,
                2
        );

        if (boss.getEquipment() != null) {

            boss.getEquipment()
                    .setHelmet(helmet);

            boss.getEquipment()
                    .setChestplate(chest);

            boss.getEquipment()
                    .setLeggings(legs);

            boss.getEquipment()
                    .setBoots(boots);

            boss.getEquipment()
                    .setItemInMainHand(sword);

            boss.getEquipment()
                    .setHelmetDropChance(0);

            boss.getEquipment()
                    .setChestplateDropChance(0);

            boss.getEquipment()
                    .setLeggingsDropChance(0);

            boss.getEquipment()
                    .setBootsDropChance(0);

            boss.getEquipment()
                    .setItemInMainHandDropChance(0);
        }

        d.remaining = 1;

        updateBossBar(d);

        p.showTitle(
                Title.title(
                        c(visual("boss.title", "§5§l☠ BOSS ☠")),
                        c(visual("boss.subtitle", "§dStrážce dungeonu se objevil!"))
                )
        );

        playConfiguredSound(p, "sounds.boss-spawn", Sound.ENTITY_WITHER_SPAWN, 1f, 0.7f);

        final BukkitTask[] task =
                new BukkitTask[1];

        task[0] =
                Bukkit.getScheduler().runTaskTimer(
                        this,
                        () -> {

                            if (d.activePlayer == null ||
                                    d.boss == null) {

                                if (task[0] != null) {
                                    task[0].cancel();
                                }

                                return;
                            }

                            Player active =
                                    Bukkit.getPlayer(
                                            d.activePlayer
                                    );

                            if (active == null ||
                                    !active.isOnline()) {

                                if (task[0] != null) {
                                    task[0].cancel();
                                }

                                endDungeon(
                                        d,
                                        false
                                );

                                return;
                            }

                            Entity e =
                                    Bukkit.getEntity(
                                            d.boss
                                    );

                            if (!(e instanceof LivingEntity le) ||
                                    le.isDead()) {

                                if (task[0] != null) {
                                    task[0].cancel();
                                }

                                return;
                            }

                            if (!d.contains(
                                    le.getLocation()
                            )) {

                                Location newLocation =
                                        findSpawnLocation(
                                                d,
                                                null
                                        );

                                if (newLocation != null) {

                                    le.teleport(
                                            newLocation
                                    );
                                }
                            }



                            active.spawnParticle(
                                    Particle.SOUL_FIRE_FLAME,
                                    le.getLocation()
                                            .add(0, 1, 0),
                                    8,
                                    .3,
                                    .6,
                                    .3,
                                    .01
                            );

                            updateBossBar(d);

                        },
                        20L,
                        20L
                );
    }


    @EventHandler
    public void onBossDeath(
            EntityDeathEvent e
    ) {

        LivingEntity entity =
                e.getEntity();

        String dId =
                entity.getPersistentDataContainer()
                        .get(
                                bossKey,
                                PersistentDataType.STRING
                        );

        if (dId == null) {
            return;
        }

        Dungeon d =
                dungeons.get(dId);

        if (d == null ||
                d.activePlayer == null) {

            return;
        }

        e.getDrops().clear();
        e.setDroppedExp(0);

        Player p =
                Bukkit.getPlayer(
                        d.activePlayer
                );

        if (p == null) {

            endDungeon(
                    d,
                    false
            );

            return;
        }

        double bossReward = rewardsConfig == null
                ? getConfig().getDouble("rewards.boss-money", 2000)
                : rewardsConfig.getDouble("boss.money", getConfig().getDouble("rewards.boss-money", 2000));

        giveMoney(
                p,
                bossReward
        );

        executeRewardCommands("boss.commands", p, d, 4);
        sendConfiguredMessage(p, "boss.message", "§5§lBOSS PORAŽEN!", d, 4);

        d.completed = true;

        d.remaining = 0;

        d.boss = null;

        entityDungeon.remove(
                entity.getUniqueId()
        );

        entityWave.remove(
                entity.getUniqueId()
        );

        removeBossBar(d);

        long cooldown = getArenaCooldownSeconds(cooldownKey(d));

        setCooldown(
                p.getUniqueId(),
                cooldownKey(d),
                System.currentTimeMillis()
                        + cooldown * 1000L
        );

        p.showTitle(
                Title.title(
                        c("§a§lDUNGEON VYČIŠTĚN!"),
                        c(bossReward > 0 ? "§fBoss byl poražen • §a+$" + formatMoney(bossReward) : "§fBoss byl poražen!")
                )
        );

        p.playSound(
                p.getLocation(),
                Sound.UI_TOAST_CHALLENGE_COMPLETE,
                1f,
                0.65f
        );

        p.playSound(
                p.getLocation(),
                Sound.ENTITY_ENDER_DRAGON_DEATH,
                0.65f,
                1.25f
        );

        Location fx =
                entity.getLocation();

        if (fx.getWorld() != null) {

            fx.getWorld().spawnParticle(
                    Particle.EXPLOSION_EMITTER,
                    fx,
                    3,
                    0.5,
                    0.5,
                    0.5,
                    0
            );

            fx.getWorld().spawnParticle(
                    Particle.REVERSE_PORTAL,
                    fx,
                    220,
                    2.0,
                    2.0,
                    2.0,
                    0.18
            );

            fx.getWorld().spawnParticle(
                    Particle.TOTEM_OF_UNDYING,
                    fx,
                    180,
                    1.5,
                    1.5,
                    1.5,
                    0.35
            );

            fx.getWorld().spawnParticle(
                    Particle.FIREWORK,
                    fx,
                    160,
                    1.2,
                    1.2,
                    1.2,
                    0.15
            );
        }

        for (int i = 0; i < 5; i++) {

            int n =
                    5 - i;

            Bukkit.getScheduler().runTaskLater(
                    this,
                    () -> {

                        if (!p.isOnline()) {
                            return;
                        }

                        p.sendActionBar(
                                c(
                                        "§5Teleportace ke crate za §d"
                                                + n
                                                + "§5..."
                                )
                        );

                        p.playSound(
                                p.getLocation(),
                                Sound.BLOCK_NOTE_BLOCK_PLING,
                                1f,
                                0.7f +
                                        (5 - n) * 0.18f
                        );

                    },
                    i * 20L
            );
        }

        Bukkit.getScheduler().runTaskLater(
                this,
                () -> {

                    if (!p.isOnline()) {

                        endDungeon(
                                d,
                                true
                        );

                        return;
                    }

                    p.showTitle(
                            Title.title(
                                    c("§5§lTELEPORT!"),
                                    c("§fOdpočet dokončen.")
                            )
                    );

                    safeTeleport(
                            p,
                            d.exit != null
                                    ? d.exit
                                    : d.spawn
                    );

                    p.spawnParticle(
                            Particle.PORTAL,
                            p.getLocation()
                                    .add(0, 1, 0),
                            140,
                            1,
                            1,
                            1,
                            0.08
                    );

                    p.playSound(
                            p.getLocation(),
                            Sound.ENTITY_ENDERMAN_TELEPORT,
                            1f,
                            1f
                    );

                    endDungeon(
                            d,
                            true
                    );

                },
                100L
        );
    }


    // =========================================================
    // BOSS PROTECTION
    // =========================================================

    @EventHandler
    public void preventBossOutside(
            EntityDamageEvent e
    ) {

        if (!(e.getEntity()
                instanceof LivingEntity le)) {

            return;
        }

        String dId =
                le.getPersistentDataContainer()
                        .get(
                                bossKey,
                                PersistentDataType.STRING
                        );

        if (dId == null) {
            return;
        }

        Dungeon d =
                dungeons.get(dId);

        if (d == null) {
            return;
        }

        if (!d.contains(
                le.getLocation()
        )) {

            e.setCancelled(true);

            Player p =
                    d.activePlayer == null
                            ? null
                            : Bukkit.getPlayer(
                            d.activePlayer
                    );

            if (p != null) {

                Location newLocation =
                        findSpawnLocation(
                                d,
                                null
                        );

                if (newLocation != null) {

                    le.teleport(
                            newLocation
                    );
                }
            }
        }
    }


    // =========================================================
    // ACTIVE DUNGEON MOVEMENT / TELEPORT PROTECTION
    // =========================================================

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDungeonMove(PlayerMoveEvent e) {
        // Hráč může aktivní dungeon arénu fyzicky opustit.
        // Aréna určuje pouze prostor pro spawn dungeon mobů.
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDungeonTeleport(PlayerTeleportEvent e) {
        // Teleport mimo arénu během runu je povolen.
    }


    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerDeath(PlayerDeathEvent e) {
        Player p = e.getEntity();
        String dId = activeByPlayer.get(p.getUniqueId());
        if (dId == null) return;
        Dungeon d = dungeons.get(dId);
        if (d == null) return;

        // Dungeon je na vlastní riziko: při smrti hráče se drop smaže.
        e.setKeepInventory(false);
        e.getDrops().clear();
        e.setDroppedExp(0);

        long cooldown = getArenaCooldownSeconds(cooldownKey(d));
        setCooldown(p.getUniqueId(), cooldownKey(d), System.currentTimeMillis() + cooldown * 1000L);
        p.sendMessage(visualMessage("messages.death", "§5§lDungeon §8» §cZemřel jsi. Dungeon byl ukončen.", p, d));
        endDungeon(d, false);
        playersInside.remove(p.getUniqueId());
    }


    // =========================================================
    // QUIT
    // =========================================================

    @EventHandler
    public void onQuit(
            PlayerQuitEvent e
    ) {

        UUID id =
                e.getPlayer()
                        .getUniqueId();

        playersInside.remove(id);

        lastSafeLocation.remove(id);

        String dId =
                activeByPlayer.get(id);

        if (dId == null) {
            return;
        }

        Dungeon d =
                dungeons.get(dId);

        if (d != null) {
            long cooldown = getArenaCooldownSeconds(cooldownKey(d));
            setCooldown(id, cooldownKey(d), System.currentTimeMillis() + cooldown * 1000L);
            endDungeon(d, false);
        }
    }


    // =========================================================
    // END / CLEANUP
    // =========================================================

    private void endDungeon(
            Dungeon d,
            boolean completed
    ) {

        if (d == null) {
            return;
        }

        UUID player =
                d.activePlayer;

        cleanupDungeonEntities(d);

        removeBossBar(d);

        d.activePlayer = null;

        d.boss = null;

        d.remaining = 0;
        d.waveTotal = 0;
        d.waveSpawned = 0;
        d.waveKilled = 0;
        d.transitioning = false;

        d.wave = 0;

        d.completed = completed;

        if (player != null) {

            activeByPlayer.remove(
                    player
            );

            dungeonOwner.remove(
                    player
            );
        }
    }


    private void cleanupDungeonEntities(
            Dungeon d
    ) {

        for (UUID id :
                new HashSet<>(
                        d.spawned
                )) {

            Entity e =
                    Bukkit.getEntity(id);

            if (e != null &&
                    !e.isDead()) {

                e.remove();
            }

            entityDungeon.remove(id);

            entityWave.remove(id);
        }

        d.spawned.clear();

        if (d.boss != null) {

            UUID bossId =
                    d.boss;

            Entity e =
                    Bukkit.getEntity(
                            bossId
                    );

            if (e != null &&
                    !e.isDead()) {

                e.remove();
            }

            entityDungeon.remove(
                    bossId
            );

            entityWave.remove(
                    bossId
            );

            d.boss = null;
        }
    }


    // =========================================================
    // BOSSBAR
    // =========================================================

    private void updateBossBar(
            Dungeon d
    ) {
        Player p = d.activePlayer == null ? null : Bukkit.getPlayer(d.activePlayer);
        if (p == null) return;

        BossBar bar = bossBars.computeIfAbsent(p.getUniqueId(), x ->
                BossBar.bossBar(c("Dungeon"), 1f, BossBar.Color.PURPLE, BossBar.Overlay.PROGRESS));
        bar.addViewer(p);

        long speed = Math.max(1L, appearanceConfig == null ? 3L : appearanceConfig.getLong("wavebar.rgb.speed-ticks", 3L));
        int phase = (int) ((System.currentTimeMillis() / (speed * 50L)) % 10000L);

        if (d.boss != null) {
            Entity e = Bukkit.getEntity(d.boss);
            if (e instanceof LivingEntity le) {
                float progress = (float) Math.max(0, Math.min(1, le.getHealth() / 100.0));
                bar.progress(progress);
                String raw = visual("boss.bossbar.title", "☠ BOSS • %health%/%max_health% HP")
                        .replace("%health%", String.valueOf((int)Math.ceil(le.getHealth())))
                        .replace("%max_health%", "100")
                        .replace("%arena%", d.id);
                bar.name(gradientText(stripLegacy(raw), getColorway("boss.rgb.colors",
                        List.of("#26004D", "#6500A8", "#B52BFF", "#F0B0FF", "#8B00E8")), phase, true));
                bar.color(BossBar.Color.PURPLE);
            }
        } else {
            int max = Math.max(1, d.waveTotal > 0 ? d.waveTotal : getWaveCount(d.wave));
            float progress = (float)Math.max(0, Math.min(1, d.remaining / (double)max));
            bar.progress(progress);
            String raw = visual("wavebar.title", "⚔ WAVE %wave% • %remaining%/%total% ZBÝVÁ")
                    .replace("%wave%", String.valueOf(d.wave))
                    .replace("%remaining%", String.valueOf(d.remaining))
                    .replace("%total%", String.valueOf(max))
                    .replace("%arena%", d.id);
            bar.name(gradientText(stripLegacy(raw), getColorway("wave.colorways." + d.wave, defaultWaveColors(d.wave)), phase, true));
            bar.color(d.wave >= 4 ? BossBar.Color.RED : d.wave == 3 ? BossBar.Color.YELLOW : d.wave == 2 ? BossBar.Color.BLUE : BossBar.Color.GREEN);
        }
    }

    private void removeBossBar(
            Dungeon d
    ) {

        if (d.activePlayer == null) {
            return;
        }

        UUID playerId =
                d.activePlayer;

        BossBar bar =
                bossBars.remove(
                        playerId
                );

        if (bar == null) {
            return;
        }

        Player p =
                Bukkit.getPlayer(
                        playerId
                );

        if (p != null) {

            bar.removeViewer(p);
        }
    }


    // =========================================================
    // REWARDS
    // =========================================================

    private void giveMoney(Player p, double amount) {
        if (amount <= 0 || rewardsConfig == null) return;
        String command = rewardsConfig.getString("money.command", "").trim();
        if (command.isEmpty()) return;
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), replaceRewardPlaceholders(command, p, null, 0, amount));
    }

    private void executeRewardCommands(String path, Player p, Dungeon d, int wave) {
        if (rewardsConfig == null) return;
        for (String command : rewardsConfig.getStringList(path)) {
            if (command == null || command.isBlank()) continue;
            String clean = command.startsWith("/") ? command.substring(1) : command;
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), replaceRewardPlaceholders(clean, p, d, wave, 0));
        }
    }

    private void sendConfiguredMessage(Player p, String path, String fallback, Dungeon d, int wave) {
        String msg = rewardsConfig == null ? fallback : rewardsConfig.getString(path, fallback);
        if (msg == null || msg.isBlank()) return;
        msg = msg.replace('&', '§')
                .replace("%player%", p.getName())
                .replace("%arena%", d == null ? "" : d.id)
                .replace("%wave%", String.valueOf(wave));
        p.sendMessage(msg);
    }

    private String replaceRewardPlaceholders(String command, Player p, Dungeon d, int wave, double amount) {
        return command
                .replace("%player%", p.getName())
                .replace("%uuid%", p.getUniqueId().toString())
                .replace("%arena%", d == null ? "" : d.id)
                .replace("%wave%", String.valueOf(wave))
                .replace("%amount%", formatMoney(amount));
    }

    private String formatMoney(double n) {
        if (n == Math.rint(n)) return String.format(Locale.US, "%.0f", n);
        return String.format(Locale.US, "%.2f", n);
    }


    // =========================================================
    // PUSH OUT
    // =========================================================

    private void pushOut(
            Player p,
            Dungeon d
    ) {

        Location out =
                lastSafeLocation.get(
                        p.getUniqueId()
                );

        if (out == null ||
                d.contains(out)) {

            out =
                    d.exit != null &&
                            !d.contains(
                                    d.exit
                            )
                            ? d.exit.clone()
                            : p.getLocation()
                            .clone()
                            .add(
                                    2,
                                    0,
                                    2
                            );

            if (d.contains(out)) {

                out.setX(
                        d.maxX + 2
                );
            }
        }

        safeTeleport(
                p,
                out
        );

        playersInside.remove(
                p.getUniqueId()
        );
    }


    private void safeTeleport(
            Player p,
            Location loc
    ) {

        if (loc == null ||
                loc.getWorld() == null) {

            return;
        }

        UUID uuid = p.getUniqueId();
        allowedTeleports.add(uuid);
        p.teleport(
                loc.clone()
        );
        allowedTeleports.remove(uuid);
    }


    // =========================================================
    // COOLDOWN
    // =========================================================

    private long cooldownRemaining(
            UUID player,
            String dungeon
    ) {

        Long end =
                cooldowns.get(
                        player + ":" + dungeon
                );

        if (end == null) {
            return 0;
        }

        long left =
                end -
                        System.currentTimeMillis();

        if (left <= 0) {

            cooldowns.remove(
                    player + ":" + dungeon
            );

            return 0;
        }

        return left;
    }


    private void setCooldown(
            UUID player,
            String dungeon,
            long end
    ) {

        cooldowns.put(
                player + ":" + dungeon,
                end
        );

        saveCooldowns();
    }


    private String formatDuration(
            long millis
    ) {

        long sec =
                Math.max(
                        0,
                        millis / 1000
                );

        long h =
                sec / 3600;

        long m =
                (sec % 3600) / 60;

        long s =
                sec % 60;

        if (h > 0) {

            return h + "h "
                    + m + "m "
                    + s + "s";
        }

        if (m > 0) {

            return m + "m "
                    + s + "s";
        }

        return s + "s";
    }


    // =========================================================
    // LOCATION
    // =========================================================

    private String formatLoc(
            Location l
    ) {

        if (l == null ||
                l.getWorld() == null) {

            return "neznámá";
        }

        return l.getWorld().getName()
                + " "
                + l.getBlockX()
                + " "
                + l.getBlockY()
                + " "
                + l.getBlockZ();
    }


    // =========================================================
    // LIVE DUNGEON EFFECTS
    // =========================================================

    private void tickDungeonEffects() {

        for (Dungeon d : dungeons.values()) {

            if (d.activePlayer == null) {
                continue;
            }

            Player p = Bukkit.getPlayer(d.activePlayer);
            if (p == null || !p.isOnline()) {
                continue;
            }

            // RGB boss/wave bar se průběžně posouvá.
            updateBossBar(d);

            // Hráč má kolem sebe jemný fialový/duchovní trail.
            Location playerFx = p.getLocation().clone().add(0, 0.15, 0);
            p.spawnParticle(
                    Particle.END_ROD,
                    playerFx,
                    2,
                    0.25,
                    0.05,
                    0.25,
                    0.01
            );

            // Aktivní mobové mají vlastní trail, takže je v aréně dobře vidět.
            for (UUID mobId : new HashSet<>(d.spawned)) {

                Entity e = Bukkit.getEntity(mobId);
                if (!(e instanceof LivingEntity mob) || mob.isDead()) {
                    continue;
                }

                Location fx = mob.getLocation().clone().add(0, 1.0, 0);

                p.spawnParticle(
                        Particle.SOUL_FIRE_FLAME,
                        fx,
                        2,
                        0.18,
                        0.35,
                        0.18,
                        0.01
                );

                if (d.wave >= 3) {
                    p.spawnParticle(
                            Particle.REVERSE_PORTAL,
                            fx,
                            2,
                            0.22,
                            0.35,
                            0.22,
                            0.01
                    );
                }
            }
        }

        updateInfoDisplays();
    }


    // =========================================================
    // DUNGEON PARTICLE BORDER
    // =========================================================

    /**
     * Permanentní obrys všech arén.
     *
     * Používáme FLAME particles místo bloků.
     *
     * Obrys:
     *
     *       X
     *   ┌───────────┐
     *   │           │
     * Z │   ARENA   │ Z
     *   │           │
     *   └───────────┘
     *       X
     *
     * Pokud má aréna výšku, zobrazí se i vertikální
     * hrany ve všech 4 rozích.
     */
    private void drawDungeonBorders() {

        for (Dungeon d :
                dungeons.values()) {

            World world =
                    Bukkit.getWorld(
                            d.world
                    );

            if (world == null) {
                continue;
            }

            drawDungeonBorder(
                    d,
                    world
            );
        }
    }


    private void drawDungeonBorder(
            Dungeon d,
            World world
    ) {

        double minX =
                d.minX;

        double maxX =
                d.maxX + 1.0;

        double minZ =
                d.minZ;

        double maxZ =
                d.maxZ + 1.0;

        double bottomY =
                d.minY + 0.08;

        double topY =
                d.maxY + 1.0;

        /*
         * Spodní obdélník.
         */
        drawParticleLine(
                world,
                minX,
                bottomY,
                minZ,
                maxX,
                bottomY,
                minZ
        );

        drawParticleLine(
                world,
                maxX,
                bottomY,
                minZ,
                maxX,
                bottomY,
                maxZ
        );

        drawParticleLine(
                world,
                maxX,
                bottomY,
                maxZ,
                minX,
                bottomY,
                maxZ
        );

        drawParticleLine(
                world,
                minX,
                bottomY,
                maxZ,
                minX,
                bottomY,
                minZ
        );

        /*
         * Horní obdélník.
         *
         * Pokud je výška pouze jeden blok,
         * horní obrys se stále zobrazí.
         */
        drawParticleLine(
                world,
                minX,
                topY,
                minZ,
                maxX,
                topY,
                minZ
        );

        drawParticleLine(
                world,
                maxX,
                topY,
                minZ,
                maxX,
                topY,
                maxZ
        );

        drawParticleLine(
                world,
                maxX,
                topY,
                maxZ,
                minX,
                topY,
                maxZ
        );

        drawParticleLine(
                world,
                minX,
                topY,
                maxZ,
                minX,
                topY,
                minZ
        );

        /*
         * 4 vertikální rohy.
         */
        drawParticleLine(
                world,
                minX,
                bottomY,
                minZ,
                minX,
                topY,
                minZ
        );

        drawParticleLine(
                world,
                maxX,
                bottomY,
                minZ,
                maxX,
                topY,
                minZ
        );

        drawParticleLine(
                world,
                maxX,
                bottomY,
                maxZ,
                maxX,
                topY,
                maxZ
        );

        drawParticleLine(
                world,
                minX,
                bottomY,
                maxZ,
                minX,
                topY,
                maxZ
        );

        /*
         * Silnější označení rohů.
         */
        drawCornerParticle(
                world,
                minX,
                bottomY,
                minZ
        );

        drawCornerParticle(
                world,
                maxX,
                bottomY,
                minZ
        );

        drawCornerParticle(
                world,
                maxX,
                bottomY,
                maxZ
        );

        drawCornerParticle(
                world,
                minX,
                bottomY,
                maxZ
        );
    }


    private void drawParticleLine(
            World world,
            double x1,
            double y1,
            double z1,
            double x2,
            double y2,
            double z2
    ) {

        double dx =
                x2 - x1;

        double dy =
                y2 - y1;

        double dz =
                z2 - z1;

        double distance =
                Math.sqrt(
                        dx * dx +
                                dy * dy +
                                dz * dz
                );

        if (distance <= 0.01) {
            return;
        }

        /*
         * Přibližně jedna částice každých 0.8 bloku.
         *
         * Udržujeme zároveň limit, aby obří aréna
         * nevytvořila absurdní počet částic.
         */
        int points =
                Math.max(
                        1,
                        Math.min(
                                250,
                                (int) Math.ceil(
                                        distance / 0.8
                                )
                        )
                );

        for (int i = 0; i <= points; i++) {

            double t =
                    i / (double) points;

            double x =
                    x1 + dx * t;

            double y =
                    y1 + dy * t;

            double z =
                    z1 + dz * t;

            world.spawnParticle(
                    Particle.END_ROD,
                    x,
                    y,
                    z,
                    1,
                    0,
                    0,
                    0,
                    0
            );
        }
    }


    private void drawCornerParticle(
            World world,
            double x,
            double y,
            double z
    ) {

        world.spawnParticle(
                Particle.END_ROD,
                x,
                y + 0.4,
                z,
                5,
                0.15,
                0.4,
                0.15,
                0.01
        );
    }


    // =========================================================
    // LEAVE DUNGEON
    // =========================================================

    private boolean leaveDungeon(Player p) {

        UUID uuid = p.getUniqueId();
        String dId = activeByPlayer.get(uuid);

        if (dId == null) {
            p.sendMessage("§5§lDungeon §8» §cMomentálně nejsi v žádném aktivním dungeonu.");
            return true;
        }

        Dungeon d = dungeons.get(dId);

        if (d == null) {
            activeByPlayer.remove(uuid);
            p.sendMessage("§cAktivní dungeon už neexistuje.");
            return true;
        }

        long cooldown = getArenaCooldownSeconds(cooldownKey(d));

        setCooldown(
                uuid,
                cooldownKey(d),
                System.currentTimeMillis() + cooldown * 1000L
        );

        Location exit = d.exit != null
                ? d.exit.clone()
                : d.spawn != null
                ? d.spawn.clone()
                : null;

        endDungeon(d, false);

        if (exit != null) {
            safeTeleport(p, exit);
        }

        playersInside.remove(uuid);
        lastSafeLocation.put(uuid, p.getLocation().clone());

        p.showTitle(
                Title.title(
                        c(visual("leave.title", "§c§lDUNGEON UKONČEN")),
                        c(visual("leave.subtitle", "§fRun byl ukončen • cooldown §d%time%").replace("%time%", formatDuration(cooldown * 1000L)))
                )
        );

        p.playSound(
                p.getLocation(),
                Sound.ENTITY_ENDERMAN_TELEPORT,
                0.9f,
                0.75f
        );

        p.sendMessage(visualMessage("messages.leave", "§5§lDungeon §8» §fOpustil jsi dungeon §d%arena%§f.", p, d));
        p.sendMessage("§8Cooldown: §d" + formatDuration(cooldown * 1000L));
        return true;
    }


    // =========================================================
    // COMMANDS
    // =========================================================

    @Override
    public boolean onCommand(
            CommandSender sender,
            Command cmd,
            String label,
            String[] args
    ) {

        if (cmd.getName()
                .equalsIgnoreCase(
                        "spawndinfo"
                )) {

            if (!sender.hasPermission(
                    "houskadungeon.admin"
            )) {

                return noPerm(sender);
            }

            if (!(sender instanceof Player p)) {

                sender.sendMessage(
                        "Tento příkaz musí použít hráč."
                );

                return true;
            }

            if (args.length == 1 &&
                    args[0].equalsIgnoreCase(
                            "remove"
                    )) {

                removeInfoHologram(p);

                return true;
            }

            if (args.length < 1) {

                sender.sendMessage(
                        "§5§lDungeon §8» §f/spawndinfo <dungeon> §8| §f/spawndinfo remove"
                );

                return true;
            }

            DungeonGroup g = groups.get(args[0].toLowerCase(Locale.ROOT));
            if (g == null) { sender.sendMessage("§cDungeon group neexistuje."); return true; }
            Dungeon d = representativeArena(g);
            if (d == null) { sender.sendMessage("§cGroup zatím nemá žádnou arénu."); return true; }
            removeInfoHologram(p);
            g.info = p.getLocation().clone();
            d.info = g.info.clone();
            spawnInfoDisplay(d);
            saveGroups(); saveDungeons();
            sender.sendMessage("§aHologram pro group §d" + g.id + " §abyl vytvořen na tvojí lokaci.");

            return true;
        }


        // /dungeon leave je hráčský příkaz a nepotřebuje admin permission.
        if (args.length > 0 &&
                args[0].equalsIgnoreCase("leave")) {

            if (!(sender instanceof Player p)) {
                sender.sendMessage("Tento příkaz musí použít hráč.");
                return true;
            }

            return leaveDungeon(p);
        }

        if (!sender.hasPermission(
                "houskadungeon.admin"
        )) {

            return noPerm(sender);
        }


        if (args.length == 0) {

            sendHelp(sender);

            return true;
        }


        switch (args[0].toLowerCase()) {

            // =================================================
            // GROUP / ARENA INSTANCING
            // =================================================
            case "group" -> {
                if (args.length < 3) {
                    sender.sendMessage("§cPoužití: /dungeon group <create|setwall|setexit|toggle|info|delete> <group>");
                    return true;
                }
                String action = args[1].toLowerCase(Locale.ROOT);
                String gid = args[2].toLowerCase(Locale.ROOT);

                if (action.equals("create")) {
                    if (groups.containsKey(gid)) { sender.sendMessage("§cGroup už existuje."); return true; }
                    groups.put(gid, new DungeonGroup(gid));
                    saveGroups(); ensureArenaCooldownEntries();
                    sender.sendMessage("§5§lDungeon §8» §aVytvořena group §d" + gid + "§a. Teď vytvoř její arény přes §f/dungeon arena create " + gid + " <arena>§a.");
                    return true;
                }

                DungeonGroup g = groups.get(gid);
                if (g == null) { sender.sendMessage("§cDungeon group neexistuje."); return true; }

                if (action.equals("setwall")) {
                    if (!(sender instanceof Player p)) return true;
                    Selection sel = selections.get(p.getUniqueId());
                    if (sel == null || sel.one == null || sel.two == null || sel.one.getWorld() == null || sel.two.getWorld() == null || !sel.one.getWorld().equals(sel.two.getWorld())) {
                        sender.sendMessage("§cNejdřív označ společný vstup dvěma pozicemi pomocí /dungeon wand."); return true;
                    }
                    g.wallWorld = sel.one.getWorld().getName();
                    g.wallMinX = Math.min(sel.one.getBlockX(), sel.two.getBlockX()); g.wallMaxX = Math.max(sel.one.getBlockX(), sel.two.getBlockX());
                    g.wallMinY = Math.min(sel.one.getBlockY(), sel.two.getBlockY()); g.wallMaxY = Math.max(sel.one.getBlockY(), sel.two.getBlockY());
                    g.wallMinZ = Math.min(sel.one.getBlockZ(), sel.two.getBlockZ()); g.wallMaxZ = Math.max(sel.one.getBlockZ(), sel.two.getBlockZ());
                    saveGroups(); selections.remove(p.getUniqueId());
                    sender.sendMessage("§aSpolečný SETWALL pro group §d" + gid + " §anastaven."); return true;
                }

                if (action.equals("setexit")) {
                    if (!(sender instanceof Player p)) return true;
                    g.exit = p.getLocation().clone();
                    for (String aid : g.arenas) { Dungeon a = dungeons.get(aid); if (a != null) a.exit = g.exit.clone(); }
                    saveGroups(); saveDungeons();
                    sender.sendMessage("§aSpolečný EXIT pro group §d" + gid + " §anastaven."); return true;
                }

                if (action.equals("toggle")) {
                    if (g.enabled && g.arenas.stream().map(dungeons::get).filter(Objects::nonNull).anyMatch(a -> a.activePlayer != null)) {
                        sender.sendMessage("§cGroup nejde vypnout, dokud v některé její aréně někdo hraje."); return true;
                    }
                    if (!g.enabled && (!g.hasWall() || g.exit == null || g.arenas.stream().map(dungeons::get).filter(Objects::nonNull).noneMatch(a -> a.spawn != null))) {
                        sender.sendMessage("§cGroup nejde zapnout. Potřebuje SETWALL, EXIT a alespoň jednu arénu se spawnem."); return true;
                    }
                    g.enabled = !g.enabled;
                    for (String aid : g.arenas) { Dungeon a = dungeons.get(aid); if (a != null) a.enabled = g.enabled; }
                    saveGroups(); saveDungeons();
                    sender.sendMessage("§aGroup §d" + gid + (g.enabled ? " §aje ZAPNUTÁ." : " §cje VYPNUTÁ.")); return true;
                }

                if (action.equals("info")) {
                    long busy = g.arenas.stream().map(dungeons::get).filter(Objects::nonNull).filter(a -> a.activePlayer != null).count();
                    sender.sendMessage("§5§lGROUP §d" + gid + " §8• §f" + g.arenas.size() + " arén §8• §f" + busy + " aktivních §8• " + (g.enabled ? "§aON" : "§cOFF"));
                    sender.sendMessage("§7Arény: §f" + String.join(", ", g.arenas)); return true;
                }

                if (action.equals("delete")) {
                    if (g.arenas.stream().map(dungeons::get).filter(Objects::nonNull).anyMatch(a -> a.activePlayer != null)) { sender.sendMessage("§cGroup má aktivní run."); return true; }
                    for (String aid : new ArrayList<>(g.arenas)) dungeons.remove(aid);
                    groups.remove(gid); saveGroups(); saveDungeons();
                    sender.sendMessage("§aGroup §d" + gid + " §aa všechny její arény byly smazány."); return true;
                }

                sender.sendMessage("§cNeznámá group akce."); return true;
            }

            case "arena" -> {
                if (args.length < 2) { sender.sendMessage("§cPoužití: /dungeon arena <create|setspawn|info|delete> ..."); return true; }
                String action = args[1].toLowerCase(Locale.ROOT);
                if (action.equals("create")) {
                    if (!(sender instanceof Player p)) return true;
                    if (args.length < 4) { sender.sendMessage("§cPoužití: /dungeon arena create <group> <arena>"); return true; }
                    String gid = args[2].toLowerCase(Locale.ROOT), aid = args[3].toLowerCase(Locale.ROOT);
                    DungeonGroup g = groups.get(gid);
                    if (g == null) { sender.sendMessage("§cGroup neexistuje."); return true; }
                    if (dungeons.containsKey(aid)) { sender.sendMessage("§cArena s tímto ID už existuje."); return true; }
                    Selection sel = selections.get(p.getUniqueId());
                    if (sel == null || sel.one == null || sel.two == null || sel.one.getWorld() == null || sel.two.getWorld() == null || !sel.one.getWorld().equals(sel.two.getWorld())) { sender.sendMessage("§cNejdřív označ region arény Dungeon Wandou v jejím světě."); return true; }
                    Dungeon a = Dungeon.fromSelection(aid, sel.one, sel.two);
                    a.groupId = gid; a.enabled = g.enabled; a.exit = g.exit == null ? null : g.exit.clone();
                    dungeons.put(aid, a); if (!g.arenas.contains(aid)) g.arenas.add(aid);
                    selections.remove(p.getUniqueId()); saveGroups(); saveDungeons();
                    sender.sendMessage("§aArena §d" + aid + " §avytvořena ve světě §f" + a.world + " §aa automaticky připojena do §d" + gid + "§a.");
                    sender.sendMessage("§7Teď se postav na start a dej §f/dungeon arena setspawn " + aid); return true;
                }
                if (args.length < 3) { sender.sendMessage("§cChybí ID arény."); return true; }
                String aid = args[2].toLowerCase(Locale.ROOT); Dungeon a = dungeons.get(aid);
                if (a == null) { sender.sendMessage("§cArena neexistuje."); return true; }
                if (action.equals("setspawn")) {
                    if (!(sender instanceof Player p)) return true;
                    if (!p.getWorld().getName().equals(a.world)) { sender.sendMessage("§cSpawn musíš nastavit ve světě §f" + a.world); return true; }
                    a.spawn = p.getLocation().clone(); saveDungeons(); sender.sendMessage("§aSpawn arény §d" + aid + " §anastaven."); return true;
                }
                if (action.equals("info")) { sendInfo(sender, a); sender.sendMessage("§7Group: §d" + (a.groupId == null ? "—" : a.groupId)); return true; }
                if (action.equals("delete")) {
                    if (a.activePlayer != null) { sender.sendMessage("§cArena je právě aktivní."); return true; }
                    DungeonGroup g = groupOf(a); if (g != null) g.arenas.remove(a.id); dungeons.remove(a.id); saveGroups(); saveDungeons();
                    sender.sendMessage("§aArena §d" + aid + " §asmazána."); return true;
                }
                sender.sendMessage("§cNeznámá arena akce."); return true;
            }

            // =================================================
            // WAND
            // =================================================

            case "wand" -> {

                if (!(sender instanceof Player p)) {
                    return true;
                }

                p.getInventory()
                        .addItem(
                                wand.clone()
                        );

                sender.sendMessage(
                        "§5§lDungeon §8» §aDungeon Wand přidána."
                );
            }


            // =================================================
            // CREATE
            // =================================================

            case "create" -> {

                if (!(sender instanceof Player p)) {
                    return true;
                }

                if (args.length < 2) {

                    sender.sendMessage(
                            "§cPoužití: /dungeon create <id>"
                    );

                    return true;
                }

                Selection s =
                        selections.get(
                                p.getUniqueId()
                        );

                if (s == null ||
                        s.one == null ||
                        s.two == null) {

                    sender.sendMessage(
                            "§cNejdřív musíš nastavit obě pozice pomocí Dungeon Wandy."
                    );

                    return true;
                }

                if (s.one.getWorld() == null ||
                        s.two.getWorld() == null ||
                        !s.one.getWorld()
                                .getName()
                                .equals(
                                        s.two.getWorld()
                                                .getName()
                                )) {

                    sender.sendMessage(
                            "§cObě pozice musí být ve stejném světě."
                    );

                    return true;
                }

                String id =
                        args[1].toLowerCase();

                if (dungeons.containsKey(id)) {

                    sender.sendMessage(
                            "§cDungeon s tímto ID už existuje."
                    );

                    return true;
                }

                Dungeon d =
                        Dungeon.fromSelection(
                                id,
                                s.one,
                                s.two
                        );

                /*
                 * NOVĚ:
                 * Dungeon je po vytvoření automaticky OFF.
                 */
                d.enabled = false;

                dungeons.put(
                        id,
                        d
                );

                saveDungeons();
                ensureArenaCooldownEntries();

                /*
                 * Ihned zobrazíme particle obrys.
                 */
                sender.sendMessage(
                        "§5§lDungeon §8» §aDungeon §d"
                                + id
                                + " §abyl vytvořen."
                );

                sender.sendMessage(
                        "§7Status: §cVYPNUTÝ"
                );

                sender.sendMessage(
                        "§7Nyní nastav:"
                );

                sender.sendMessage(
                        "§f/dungeon setspawn "
                                + id
                );

                sender.sendMessage(
                        "§f/dungeon setexit "
                                + id
                );

                sender.sendMessage(
                        "§7Až bude vše hotové:"
                );

                sender.sendMessage(
                        "§f/dungeon toggle "
                                + id
                );

                /*
                 * Vyčištění výběru.
                 */
                selections.remove(
                        p.getUniqueId()
                );
            }


            // =================================================
            // SETWALL
            // =================================================
            case "setwall" -> {
                if (!(sender instanceof Player p)) return true;
                if (args.length < 2) { sender.sendMessage("§cPoužití: /dungeon setwall <id>"); return true; }
                Dungeon d = dungeons.get(args[1].toLowerCase());
                if (d == null) return notFound(sender);
                Selection sel = selections.get(p.getUniqueId());
                if (sel == null || sel.one == null || sel.two == null || sel.one.getWorld() == null || sel.two.getWorld() == null
                        || !sel.one.getWorld().equals(sel.two.getWorld())) {
                    sender.sendMessage("§cNejdřív označ wall dvěma pozicemi pomocí /dungeon wand.");
                    return true;
                }
                d.wallWorld = sel.one.getWorld().getName();
                d.wallMinX = Math.min(sel.one.getBlockX(), sel.two.getBlockX());
                d.wallMinY = Math.min(sel.one.getBlockY(), sel.two.getBlockY());
                d.wallMinZ = Math.min(sel.one.getBlockZ(), sel.two.getBlockZ());
                d.wallMaxX = Math.max(sel.one.getBlockX(), sel.two.getBlockX());
                d.wallMaxY = Math.max(sel.one.getBlockY(), sel.two.getBlockY());
                d.wallMaxZ = Math.max(sel.one.getBlockZ(), sel.two.getBlockZ());
                saveDungeons();
                selections.remove(p.getUniqueId());
                sender.sendMessage("§5§lDungeon §8» §aStart wall pro §d" + d.id + " §abyl nastaven.");
            }

            // =================================================
            // SETSPAWN
            // =================================================

            case "setspawn" -> {

                if (!(sender instanceof Player p)) {
                    return true;
                }

                if (args.length < 2) {

                    sender.sendMessage(
                            "§cPoužití: /dungeon setspawn <id>"
                    );

                    return true;
                }

                Dungeon d =
                        dungeons.get(
                                args[1].toLowerCase()
                        );

                if (d == null) {
                    return notFound(sender);
                }

                d.spawn =
                        p.getLocation().clone();

                saveDungeons();

                sender.sendMessage(
                        "§aSpawn dungeonu §d"
                                + d.id
                                + " §anastaven."
                );

                sender.sendMessage(
                        "§7Dungeon je stále: "
                                + (
                                d.enabled
                                        ? "§aON"
                                        : "§cOFF"
                        )
                );
            }


            // =================================================
            // SETEXIT
            // =================================================

            case "setexit" -> {

                if (!(sender instanceof Player p)) {
                    return true;
                }

                if (args.length < 2) {

                    sender.sendMessage(
                            "§cPoužití: /dungeon setexit <id>"
                    );

                    return true;
                }

                Dungeon d =
                        dungeons.get(
                                args[1].toLowerCase()
                        );

                if (d == null) {
                    return notFound(sender);
                }

                d.exit =
                        p.getLocation().clone();

                saveDungeons();

                sender.sendMessage(
                        "§aExit/crate teleport pro dungeon §d"
                                + d.id
                                + " §anastaven."
                );
            }


            // =================================================
            // TOGGLE
            // =================================================

            case "toggle" -> {

                if (args.length < 2) {

                    sender.sendMessage(
                            "§cPoužití: /dungeon toggle <id>"
                    );

                    return true;
                }

                Dungeon d =
                        dungeons.get(
                                args[1].toLowerCase()
                        );

                if (d == null) {
                    return notFound(sender);
                }

                /*
                 * ON -> OFF
                 */
                if (d.enabled) {

                    /*
                     * Nechceme vypnout arénu
                     * uprostřed běžícího dungeon runu.
                     */
                    if (d.activePlayer != null) {

                        Player active =
                                Bukkit.getPlayer(
                                        d.activePlayer
                                );

                        sender.sendMessage(
                                "§cDungeon nejde vypnout, protože je právě obsazený"
                                        + (
                                        active == null
                                                ? "."
                                                : " hráčem §f"
                                                + active.getName()
                                                + "§c."
                                )
                        );

                        return true;
                    }

                    d.enabled = false;

                    saveDungeons();

                    sender.sendMessage(
                            "§5§lDungeon §8» §cDungeon §d"
                                    + d.id
                                    + " §cbyl VYPNUT."
                    );

                    sender.sendMessage(
                            "§7Hráči mohou arénou procházet, "
                                    + "ale dungeon se při vstupu nespustí."
                    );

                    return true;
                }

                /*
                 * OFF -> ON
                 *
                 * Před zapnutím zkontrolujeme nastavení.
                 */
                if (d.spawn == null) {

                    sender.sendMessage(
                            "§cDungeon §d"
                                    + d.id
                                    + " §cnelze zapnout."
                    );

                    sender.sendMessage(
                            "§7Nejdřív nastav spawn pomocí:"
                    );

                    sender.sendMessage(
                            "§f/dungeon setspawn "
                                    + d.id
                    );

                    return true;
                }

                if (!d.hasWall()) {
                    sender.sendMessage("§cDungeon §d" + d.id + " §cnelze zapnout.");
                    sender.sendMessage("§7Nejdřív nastav start wall: §f/dungeon setwall " + d.id);
                    return true;
                }

                d.enabled = true;

                saveDungeons();

                sender.sendMessage(
                        "§5§lDungeon §8» §aDungeon §d"
                                + d.id
                                + " §abyl ZAPNUT."
                );

                sender.sendMessage(
                        "§7Od této chvíle vstup do arény "
                                + "spustí dungeon."
                );
            }


            // =================================================
            // INFO
            // =================================================

            case "info" -> {

                if (args.length < 2) {

                    sender.sendMessage(
                            "§cPoužití: /dungeon info <id>"
                    );

                    return true;
                }

                Dungeon d =
                        dungeons.get(
                                args[1].toLowerCase()
                        );

                if (d == null) {
                    return notFound(sender);
                }

                sendInfo(
                        sender,
                        d
                );
            }


            // =================================================
            // DELETE
            // =================================================

            case "delete" -> {

                if (args.length < 2) {

                    sender.sendMessage(
                            "§cPoužití: /dungeon delete <id>"
                    );

                    return true;
                }

                Dungeon d =
                        dungeons.remove(
                                args[1].toLowerCase()
                        );

                if (d == null) {
                    return notFound(sender);
                }

                if (d.activePlayer != null) {

                    Player active =
                            Bukkit.getPlayer(
                                    d.activePlayer
                            );

                    if (active != null) {

                        safeTeleport(
                                active,
                                d.exit != null
                                        ? d.exit
                                        : d.spawn
                        );

                        activeByPlayer.remove(
                                d.activePlayer
                        );

                        playersInside.remove(
                                d.activePlayer
                        );
                    }
                }

                cleanupDungeonEntities(d);

                removeBossBar(d);

                saveDungeons();

                sender.sendMessage(
                        "§aDungeon §d"
                                + d.id
                                + " §abyla smazána."
                );
            }


            // =================================================
            // RESET COOLDOWNS
            // =================================================
            case "reset" -> {
                if (args.length < 2) { sender.sendMessage("§cPoužití: /dungeon reset <hráč>"); return true; }
                OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]);
                UUID targetId = target.getUniqueId();
                int removed = 0;
                for (String id : new ArrayList<>(groups.isEmpty() ? dungeons.keySet() : groups.keySet())) {
                    if (cooldowns.remove(targetId + ":" + id) != null) removed++;
                }
                saveCooldowns();
                sender.sendMessage("§5§lDungeon §8» §aResetováno cooldownů: §f" + removed + " §apro §d" + (target.getName() == null ? args[1] : target.getName()));
            }

            // =================================================
            // RELOAD
            // =================================================

            case "reload" -> {

                boolean active =
                        dungeons.values()
                                .stream()
                                .anyMatch(
                                        d ->
                                                d.activePlayer != null
                                );

                if (active) {

                    sender.sendMessage(
                            "§cReload nelze provést, protože některý dungeon je právě aktivní."
                    );

                    sender.sendMessage(
                            "§7Nejdřív dokonči nebo ukonči aktivní dungeon."
                    );

                    return true;
                }

                reloadConfig();
                loadExtraConfigs();

                removeAllInfoDisplays();

                loadGroups();
                loadDungeons();
                ensureArenaCooldownEntries();

                loadCooldowns();

                for (Dungeon d :
                        dungeons.values()) {

                    if (d.info != null) {

                        spawnInfoDisplay(d);
                    }
                }

                sender.sendMessage(
                        "§aHouskaDungeon reloadnut."
                );
            }


            // =================================================
            // LIST
            // =================================================

            case "list" -> {

                sender.sendMessage(
                        "§5§lDungeony:"
                );

                if (dungeons.isEmpty()) {

                    sender.sendMessage(
                            "§7Žádné."
                    );

                } else {

                    dungeons.values()
                            .stream()
                            .sorted(
                                    Comparator.comparing(
                                            d -> d.id
                                    )
                            )
                            .forEach(
                                    d ->
                                            sender.sendMessage(
                                                    "§d• §f"
                                                            + d.id
                                                            + " §8["
                                                            + (
                                                            d.enabled
                                                                    ? "§aON"
                                                                    : "§cOFF"
                                                    )
                                                            + "§8] "
                                                            + (
                                                            d.activePlayer == null
                                                                    ? "§7volný"
                                                                    : "§cobsazený"
                                                    )
                                            )
                            );
                }
            }


            default ->
                    sendHelp(sender);
        }

        return true;
    }


    private boolean noPerm(
            CommandSender s
    ) {

        s.sendMessage(
                "§cNa toto nemáš oprávnění."
        );

        return true;
    }


    private boolean notFound(
            CommandSender s
    ) {

        s.sendMessage(
                "§cDungeon neexistuje."
        );

        return true;
    }


    private void sendHelp(
            CommandSender s
    ) {

        s.sendMessage(
                "§5§lHouskaDungeon"
        );

        s.sendMessage(
                "§d/dungeon leave §8- §fUkončit vlastní dungeon run"
        );

        s.sendMessage(
                "§d/dungeon wand §8- §fDungeon Wand"
        );

        s.sendMessage(
                "§d/dungeon create <id> §8- §fVytvořit z označení"
        );

        s.sendMessage(
                "§d/dungeon setwall <id> §8- §fNastavit vstupní wall"
        );

        s.sendMessage(
                "§d/dungeon setspawn <id> §8- §fStart hráče"
        );

        s.sendMessage(
                "§d/dungeon setexit <id> §8- §fVýstup ke crate"
        );

        s.sendMessage(
                "§d/dungeon toggle <id> §8- §fZapnout/vypnout dungeon"
        );

        s.sendMessage(
                "§d/dungeon info <id> §8- §fInfo"
        );

        s.sendMessage(
                "§d/dungeon list §8- §fSeznam"
        );

        s.sendMessage(
                "§d/dungeon delete <id> §8- §fSmazat"
        );

        s.sendMessage(
                "§d/dungeon reset <hráč> §8- §fReset všech cooldownů hráče"
        );

        s.sendMessage(
                "§d/dungeon reload §8- §fReload"
        );

        s.sendMessage(
                "§d/spawndinfo <id> §8- §fInfo hologram"
        );

        s.sendMessage(
                "§d/spawndinfo remove §8- §fOdstranit hologram"
        );
    }


    // =========================================================
    // INFO
    // =========================================================

    private void sendInfo(
            CommandSender s,
            Dungeon d
    ) {

        s.sendMessage(
                "§5§l===== DUNGEON "
                        + d.id.toUpperCase()
                        + " ====="
        );

        s.sendMessage(
                "§7Status: "
                        + (
                        d.enabled
                                ? "§aON §8– §faktivní"
                                : "§cOFF §8– §fneaktivní"
                )
        );

        s.sendMessage(
                "§7World: §f"
                        + d.world
        );

        s.sendMessage(
                "§7Region: §f"
                        + d.minX
                        + ","
                        + d.minY
                        + ","
                        + d.minZ
                        + " §8→ §f"
                        + d.maxX
                        + ","
                        + d.maxY
                        + ","
                        + d.maxZ
        );

        s.sendMessage(
                "§7Obsazení: "
                        + (
                        d.activePlayer == null
                                ? "§aVOLNÝ"
                                : "§cOBSAZENÝ"
                )
        );

        if (d.activePlayer != null) {

            s.sendMessage(
                    "§7Hráč: §f"
                            + Optional.ofNullable(
                                    Bukkit.getPlayer(
                                            d.activePlayer
                                    )
                            )
                            .map(Player::getName)
                            .orElse("offline")
            );
        }

        s.sendMessage(
                "§7Wave: §f"
                        + d.wave
                        + " §8| §7Mobů: §f"
                        + d.remaining
        );

        s.sendMessage(
                "§7Spawn: §f"
                        + (
                        d.spawn == null
                                ? "nenastaven"
                                : formatLoc(
                                d.spawn
                        )
                )
        );

        s.sendMessage(
                "§7Exit: §f"
                        + (
                        d.exit == null
                                ? "nenastaven"
                                : formatLoc(
                                d.exit
                        )
                )
        );

        s.sendMessage("§7Start wall: " + (d.hasWall() ? "§aNASTAVEN" : "§cNENASTAVEN"));
        s.sendMessage("§7Cooldown: §f" + formatDuration(getArenaCooldownSeconds(cooldownKey(d)) * 1000L));

        s.sendMessage(
                "§7Particle obrys: §aAKTIVNÍ"
        );

        s.sendMessage(
                "§5§l============================"
        );
    }


    // =========================================================
    // INFO HOLOGRAM
    // =========================================================

    private void spawnInfoDisplay(
            Dungeon d
    ) {
        if (d.info == null || d.info.getWorld() == null) return;
        removeInfoHologramAt(d.info);

        TextDisplay td = (TextDisplay) d.info.getWorld().spawnEntity(d.info, EntityType.TEXT_DISPLAY);
        td.getPersistentDataContainer().set(infoKey, PersistentDataType.BYTE, (byte) 1);
        td.getPersistentDataContainer().set(new NamespacedKey(this, "info_arena"), PersistentDataType.STRING, d.id);
        td.setBillboard(Display.Billboard.CENTER);
        td.setAlignment(TextDisplay.TextAlignment.CENTER);
        td.setBackgroundColor(Color.fromARGB(appearanceConfig == null ? 175 : appearanceConfig.getInt("info.background-alpha", 175), 6, 8, 14));
        td.setShadowed(true);
        td.setSeeThrough(false);
        td.setDefaultBackground(false);
        td.text(buildInfoComponent(d, nearestInfoViewer(d), 0));

        float scale = appearanceConfig == null ? 1.05f : (float) appearanceConfig.getDouble("info.scale", 1.05);
        td.setTransformation(new org.bukkit.util.Transformation(
                new org.joml.Vector3f(0,0,0), new org.joml.Quaternionf(),
                new org.joml.Vector3f(scale,scale,scale), new org.joml.Quaternionf()));
    }

    private Player nearestInfoViewer(Dungeon d) {
        if (d.info == null || d.info.getWorld() == null) return null;
        Player best = null; double bestDist = Double.MAX_VALUE;
        double radius = appearanceConfig == null ? 12.0 : appearanceConfig.getDouble("info.viewer-radius", 12.0);
        for (Player p : d.info.getWorld().getPlayers()) {
            double ds = p.getLocation().distanceSquared(d.info);
            if (ds <= radius * radius && ds < bestDist) { best = p; bestDist = ds; }
        }
        return best;
    }

    private Component buildInfoComponent(Dungeon d, Player viewer, int phase) {
        DungeonGroup g = groupOf(d);
        String displayId = g == null ? d.id : g.id;
        List<String> headerColors = getColorway("info.rgb.colors", List.of("#6B21FF", "#A855F7", "#D8B4FE", "#22D3EE", "#A855F7"));
        Component out = gradientText("✦  DUNGEON  •  " + displayId.toUpperCase(Locale.ROOT) + "  ✦", headerColors, phase, true);

        if (g != null) {
            long total = g.arenas.stream().map(dungeons::get).filter(Objects::nonNull).count();
            long busy = g.arenas.stream().map(dungeons::get).filter(Objects::nonNull).filter(a -> a.activePlayer != null).count();
            long free = g.arenas.stream().map(dungeons::get).filter(Objects::nonNull).filter(a -> a.enabled && a.spawn != null && a.activePlayer == null).count();
            String status = !g.enabled ? "§8● §7VYPNUTÝ" : free > 0 ? "§a● §a§lDOSTUPNÝ" : "§c● §c§lPLNĚ OBSAZENO";
            out = out.append(Component.newline()).append(c(status));
            out = out.append(Component.newline()).append(c("§8──────────────"));
            out = out.append(Component.newline()).append(c("§7Volné arény §8» §a" + free + "§7/§f" + total));
            out = out.append(Component.newline()).append(c("§7Právě hraje §8» §f" + busy + " §7hráčů"));
            out = out.append(Component.newline()).append(c("§7Vlny §8» §f4 §8• §7Finále §8» §d☠ BOSS"));
            out = out.append(Component.newline()).append(c("§8──────────────"));
            if (viewer != null) {
                long left = cooldownRemaining(viewer.getUniqueId(), g.id);
                String cd = left > 0 ? "§c⏱ " + formatDuration(left) : "§a✔ MŮŽEŠ VSTOUPIT";
                out = out.append(Component.newline()).append(c("§7Tvůj cooldown §8» " + cd));
            } else {
                out = out.append(Component.newline()).append(c("§7Přibliž se pro zobrazení cooldownu"));
            }
            out = out.append(Component.newline()).append(c(g.enabled && free > 0 ? "§a§l➜ VSTUP DO DUNGEONU" : "§8DUNGEON NENÍ DOSTUPNÝ"));
            return out;
        }

        String status = !d.enabled ? "§8● §7VYPNUTÝ" : d.activePlayer != null ? "§c● §c§lOBSAZENÝ" : "§a● §a§lVOLNÝ";
        out = out.append(Component.newline()).append(c(status));
        out = out.append(Component.newline()).append(c("§8──────────────"));
        out = out.append(Component.newline()).append(c("§7Arena §8» §f" + d.id));
        if (viewer != null) {
            long left = cooldownRemaining(viewer.getUniqueId(), cooldownKey(d));
            out = out.append(Component.newline()).append(c("§7Tvůj cooldown §8» " + (left > 0 ? "§c⏱ " + formatDuration(left) : "§a✔ MŮŽEŠ VSTOUPIT")));
        }
        return out;
    }

    private void updateInfoDisplays() {
        long speed = Math.max(1L, appearanceConfig == null ? 4L : appearanceConfig.getLong("info.rgb.speed-ticks", 4L));
        int phase = (int)((System.currentTimeMillis() / (speed * 50L)) % 10000L);
        NamespacedKey arenaKey = new NamespacedKey(this, "info_arena");
        for (World w : Bukkit.getWorlds()) {
            for (TextDisplay td : w.getEntitiesByClass(TextDisplay.class)) {
                if (!td.getPersistentDataContainer().has(infoKey, PersistentDataType.BYTE)) continue;
                String id = td.getPersistentDataContainer().get(arenaKey, PersistentDataType.STRING);
                Dungeon d = id == null ? null : dungeons.get(id);
                if (d != null) td.text(buildInfoComponent(d, nearestInfoViewer(d), phase));
            }
        }
    }

    private void removeInfoHologram(
            Player p
    ) {

        int removed = 0;

        for (Entity e :
                p.getNearbyEntities(
                        3,
                        5,
                        3
                )) {

            if (e instanceof TextDisplay td &&
                    td.getPersistentDataContainer()
                            .has(
                                    infoKey,
                                    PersistentDataType.BYTE
                            )) {

                e.remove();

                removed++;
            }
        }

        p.sendMessage(
                "§aOdstraněno hologramů: §f"
                        + removed
        );
    }


    private void removeInfoHologramAt(
            Location location
    ) {

        if (location == null ||
                location.getWorld() == null) {

            return;
        }

        for (Entity e :
                location.getWorld()
                        .getNearbyEntities(
                                location,
                                2,
                                3,
                                2
                        )) {

            if (e instanceof TextDisplay td &&
                    td.getPersistentDataContainer()
                            .has(
                                    infoKey,
                                    PersistentDataType.BYTE
                            )) {

                e.remove();
            }
        }
    }


    private void removeAllInfoDisplays() {

        for (World w :
                Bukkit.getWorlds()) {

            for (Entity e :
                    w.getEntitiesByClass(
                            TextDisplay.class
                    )) {

                if (e.getPersistentDataContainer()
                        .has(
                                infoKey,
                                PersistentDataType.BYTE
                        )) {

                    e.remove();
                }
            }
        }
    }


    private void loadExtraConfigs() {
        rewardsFile = new File(getDataFolder(), "odmeny.yml");
        appearanceFile = new File(getDataFolder(), "vzhled.yml");
        cooldownFile = new File(getDataFolder(), "cooldown.yml");
        try {
            getDataFolder().mkdirs();
            if (!rewardsFile.exists()) rewardsFile.createNewFile();
            if (!appearanceFile.exists()) appearanceFile.createNewFile();
            if (!cooldownFile.exists()) cooldownFile.createNewFile();
        } catch (IOException ex) {
            getLogger().warning("Nelze vytvořit doplňkové YAML: " + ex.getMessage());
        }
        rewardsConfig = YamlConfiguration.loadConfiguration(rewardsFile);
        appearanceConfig = YamlConfiguration.loadConfiguration(appearanceFile);
        cooldownConfig = YamlConfiguration.loadConfiguration(cooldownFile);
        ensureArenaCooldownEntries();
    }

    private long getArenaCooldownSeconds(String arena) {
        if (cooldownConfig == null) return getConfig().getLong("cooldown-seconds", 3600);
        return Math.max(0L, cooldownConfig.getLong("areny." + arena + ".seconds", getConfig().getLong("cooldown-seconds", 3600)));
    }

    private void ensureArenaCooldownEntries() {
        if (cooldownConfig == null) return;
        boolean changed = false;
        Collection<String> ids = groups.isEmpty() ? dungeons.keySet() : groups.keySet();
        for (String id : ids) {
            String path = "areny." + id + ".seconds";
            if (!cooldownConfig.contains(path)) { cooldownConfig.set(path, getConfig().getLong("cooldown-seconds", 3600)); changed = true; }
        }
        if (changed) try { cooldownConfig.save(cooldownFile); } catch (IOException ignored) {}
    }

    // =========================================================
    // DATA
    // =========================================================

    private void loadGroups() {
        groups.clear();
        ConfigurationSection sec = dataConfig.getConfigurationSection("groups");
        if (sec == null) return;
        for (String id : sec.getKeys(false)) {
            ConfigurationSection c = sec.getConfigurationSection(id);
            if (c == null) continue;
            DungeonGroup g = DungeonGroup.fromConfig(id, c);
            groups.put(id, g);
        }
    }

    private void saveGroups() {
        dataConfig.set("groups", null);
        for (DungeonGroup g : groups.values()) g.save(dataConfig);
        saveData();
    }

    private void loadDungeons() {

        dungeons.clear();

        ConfigurationSection sec =
                dataConfig.getConfigurationSection(
                        "dungeons"
                );

        if (sec == null) {
            return;
        }

        for (String id :
                sec.getKeys(false)) {

            ConfigurationSection c =
                    sec.getConfigurationSection(
                            id
                    );

            if (c == null) {
                continue;
            }

            Dungeon d =
                    Dungeon.fromConfig(
                            id,
                            c
                    );

            if (d != null) {
                dungeons.put(id, d);
                if (d.groupId != null) {
                    DungeonGroup g = groups.get(d.groupId);
                    if (g != null) {
                        if (!g.arenas.contains(id)) g.arenas.add(id);
                        d.exit = g.exit == null ? d.exit : g.exit.clone();
                        d.enabled = g.enabled;
                    }
                }
            }
        }
    }


    private void saveDungeons() {

        dataConfig.set(
                "dungeons",
                null
        );

        for (Dungeon d :
                dungeons.values()) {

            d.save(
                    dataConfig
            );
        }

        saveData();
    }


    private void loadCooldowns() {

        cooldowns.clear();

        ConfigurationSection sec =
                dataConfig.getConfigurationSection(
                        "cooldowns"
                );

        if (sec == null) {
            return;
        }

        for (String key :
                sec.getKeys(false)) {

            cooldowns.put(
                    key,
                    sec.getLong(key)
            );
        }
    }


    private void saveCooldowns() {

        dataConfig.set(
                "cooldowns",
                null
        );

        for (
                Map.Entry<String, Long> e :
                cooldowns.entrySet()
        ) {

            dataConfig.set(
                    "cooldowns."
                            + e.getKey(),
                    e.getValue()
            );
        }

        saveData();
    }


    private void saveData() {

        try {

            dataConfig.save(
                    dataFile
            );

        } catch (IOException e) {

            getLogger().warning(
                    "Nelze uložit data.yml: "
                            + e.getMessage()
            );
        }
    }


    // =========================================================
    // DATA CLASSES
    // =========================================================

    private static final class Selection {

        Location one;

        Location two;
    }


    private static final class DungeonGroup {
        final String id;
        final List<String> arenas = new ArrayList<>();
        boolean enabled = false;
        Location exit;
        Location info;
        String wallWorld;
        Integer wallMinX, wallMinY, wallMinZ, wallMaxX, wallMaxY, wallMaxZ;

        DungeonGroup(String id) { this.id = id; }
        boolean hasWall() { return wallWorld != null && wallMinX != null; }

        static DungeonGroup fromConfig(String id, ConfigurationSection c) {
            DungeonGroup g = new DungeonGroup(id);
            g.enabled = c.getBoolean("enabled", false);
            g.arenas.addAll(c.getStringList("arenas"));
            g.exit = Dungeon.readLoc(c.getConfigurationSection("exit"));
            g.info = Dungeon.readLoc(c.getConfigurationSection("info"));
            ConfigurationSection wall = c.getConfigurationSection("wall");
            if (wall != null) {
                g.wallWorld = wall.getString("world");
                g.wallMinX = wall.getInt("min.x"); g.wallMinY = wall.getInt("min.y"); g.wallMinZ = wall.getInt("min.z");
                g.wallMaxX = wall.getInt("max.x"); g.wallMaxY = wall.getInt("max.y"); g.wallMaxZ = wall.getInt("max.z");
            }
            return g;
        }

        void save(YamlConfiguration y) {
            String p = "groups." + id;
            y.set(p + ".enabled", enabled);
            y.set(p + ".arenas", arenas);
            Dungeon.writeLoc(y, p + ".exit", exit);
            Dungeon.writeLoc(y, p + ".info", info);
            if (!hasWall()) y.set(p + ".wall", null);
            else {
                y.set(p + ".wall.world", wallWorld);
                y.set(p + ".wall.min.x", wallMinX); y.set(p + ".wall.min.y", wallMinY); y.set(p + ".wall.min.z", wallMinZ);
                y.set(p + ".wall.max.x", wallMaxX); y.set(p + ".wall.max.y", wallMaxY); y.set(p + ".wall.max.z", wallMaxZ);
            }
        }
    }

    private static final class Dungeon {

        final String id;

        String groupId;

        final String world;

        final int minX;

        final int minY;

        final int minZ;

        final int maxX;

        final int maxY;

        final int maxZ;

        Location spawn;

        Location exit;

        Location info;

        String wallWorld;
        Integer wallMinX, wallMinY, wallMinZ, wallMaxX, wallMaxY, wallMaxZ;

        UUID activePlayer;

        UUID boss;

        Set<UUID> spawned =
                new HashSet<>();

        int wave;

        int remaining;
        int waveTotal;
        int waveSpawned;
        int waveKilled;
        boolean transitioning;

        boolean completed;

        /*
         * NOVÉ:
         *
         * false = dungeon OFF
         * true  = dungeon ON
         */
        boolean enabled;


        Dungeon(
                String id,
                String world,
                int minX,
                int minY,
                int minZ,
                int maxX,
                int maxY,
                int maxZ
        ) {

            this.id = id;

            this.world = world;

            this.minX = minX;

            this.minY = minY;

            this.minZ = minZ;

            this.maxX = maxX;

            this.maxY = maxY;

            this.maxZ = maxZ;

            /*
             * Bezpečný default:
             * nový dungeon je vždy OFF.
             */
            this.enabled = false;
        }


        static Dungeon fromSelection(
                String id,
                Location a,
                Location b
        ) {

            return new Dungeon(
                    id,
                    a.getWorld().getName(),

                    Math.min(
                            a.getBlockX(),
                            b.getBlockX()
                    ),

                    Math.min(
                            a.getBlockY(),
                            b.getBlockY()
                    ),

                    Math.min(
                            a.getBlockZ(),
                            b.getBlockZ()
                    ),

                    Math.max(
                            a.getBlockX(),
                            b.getBlockX()
                    ),

                    Math.max(
                            a.getBlockY(),
                            b.getBlockY()
                    ),

                    Math.max(
                            a.getBlockZ(),
                            b.getBlockZ()
                    )
            );
        }


        static Dungeon fromConfig(
                String id,
                ConfigurationSection c
        ) {

            String world =
                    c.getString(
                            "world"
                    );

            if (world == null) {
                return null;
            }

            Dungeon d =
                    new Dungeon(
                            id,
                            world,

                            c.getInt("min.x"),
                            c.getInt("min.y"),
                            c.getInt("min.z"),

                            c.getInt("max.x"),
                            c.getInt("max.y"),
                            c.getInt("max.z")
                    );

            d.groupId = c.getString("group");

            d.enabled =
                    c.getBoolean(
                            "enabled",
                            false
                    );

            d.spawn =
                    readLoc(
                            c.getConfigurationSection(
                                    "spawn"
                            )
                    );

            d.exit =
                    readLoc(
                            c.getConfigurationSection(
                                    "exit"
                            )
                    );

            d.info =
                    readLoc(
                            c.getConfigurationSection(
                                    "info"
                            )
                    );

            ConfigurationSection wall = c.getConfigurationSection("wall");
            if (wall != null) {
                d.wallWorld = wall.getString("world");
                d.wallMinX = wall.getInt("min.x"); d.wallMinY = wall.getInt("min.y"); d.wallMinZ = wall.getInt("min.z");
                d.wallMaxX = wall.getInt("max.x"); d.wallMaxY = wall.getInt("max.y"); d.wallMaxZ = wall.getInt("max.z");
            }

            return d;
        }


        void save(
                YamlConfiguration y
        ) {

            String p =
                    "dungeons." + id;

            y.set(
                    p + ".world",
                    world
            );

            y.set(p + ".group", groupId);

            y.set(
                    p + ".enabled",
                    enabled
            );

            y.set(
                    p + ".min.x",
                    minX
            );

            y.set(
                    p + ".min.y",
                    minY
            );

            y.set(
                    p + ".min.z",
                    minZ
            );

            y.set(
                    p + ".max.x",
                    maxX
            );

            y.set(
                    p + ".max.y",
                    maxY
            );

            y.set(
                    p + ".max.z",
                    maxZ
            );

            writeLoc(
                    y,
                    p + ".spawn",
                    spawn
            );

            writeLoc(
                    y,
                    p + ".exit",
                    exit
            );

            writeLoc(
                    y,
                    p + ".info",
                    info
            );

            if (wallWorld == null || wallMinX == null) {
                y.set(p + ".wall", null);
            } else {
                y.set(p + ".wall.world", wallWorld);
                y.set(p + ".wall.min.x", wallMinX); y.set(p + ".wall.min.y", wallMinY); y.set(p + ".wall.min.z", wallMinZ);
                y.set(p + ".wall.max.x", wallMaxX); y.set(p + ".wall.max.y", wallMaxY); y.set(p + ".wall.max.z", wallMaxZ);
            }
        }


        boolean contains(
                Location l
        ) {

            return l != null &&
                    l.getWorld() != null &&
                    l.getWorld()
                            .getName()
                            .equals(world) &&

                    l.getX() >= minX &&
                    l.getX() <= maxX + 1 &&

                    l.getY() >= minY &&
                    l.getY() <= maxY + 1 &&

                    l.getZ() >= minZ &&
                    l.getZ() <= maxZ + 1;
        }


        boolean containsWall(Location l) {
            return wallWorld != null && wallMinX != null && l != null && l.getWorld() != null
                    && l.getWorld().getName().equals(wallWorld)
                    && l.getX() >= wallMinX && l.getX() <= wallMaxX + 1
                    && l.getY() >= wallMinY && l.getY() <= wallMaxY + 1
                    && l.getZ() >= wallMinZ && l.getZ() <= wallMaxZ + 1;
        }

        boolean hasWall() { return wallWorld != null && wallMinX != null; }

        long volume() {

            return (long)
                    (maxX - minX + 1)
                    *
                    (maxY - minY + 1)
                    *
                    (maxZ - minZ + 1);
        }


        private static Location readLoc(
                ConfigurationSection s
        ) {

            if (s == null ||
                    s.getString("world") == null) {

                return null;
            }

            World w =
                    Bukkit.getWorld(
                            s.getString(
                                    "world"
                            )
                    );

            if (w == null) {
                return null;
            }

            return new Location(
                    w,
                    s.getDouble("x"),
                    s.getDouble("y"),
                    s.getDouble("z"),
                    (float) s.getDouble("yaw"),
                    (float) s.getDouble("pitch")
            );
        }


        private static void writeLoc(
                YamlConfiguration y,
                String p,
                Location l
        ) {

            if (l == null) {

                y.set(
                        p,
                        null
                );

                return;
            }

            if (l.getWorld() == null) {

                y.set(
                        p,
                        null
                );

                return;
            }

            y.set(
                    p + ".world",
                    l.getWorld().getName()
            );

            y.set(
                    p + ".x",
                    l.getX()
            );

            y.set(
                    p + ".y",
                    l.getY()
            );

            y.set(
                    p + ".z",
                    l.getZ()
            );

            y.set(
                    p + ".yaw",
                    l.getYaw()
            );

            y.set(
                    p + ".pitch",
                    l.getPitch()
            );
        }
    }


    // =========================================================
    // TAB COMPLETE
    // =========================================================

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (command.getName().equalsIgnoreCase("spawndinfo")) {
            if (args.length == 1) { List<String> out = new ArrayList<>(groups.keySet()); out.add("remove"); return partial(out, args[0]); }
            return List.of();
        }
        if (args.length == 1) return partial(List.of("leave", "wand", "group", "arena", "list", "reset", "reload"), args[0]);
        if (args.length == 2 && args[0].equalsIgnoreCase("group")) return partial(List.of("create", "setwall", "setexit", "toggle", "info", "delete"), args[1]);
        if (args.length == 3 && args[0].equalsIgnoreCase("group") && !args[1].equalsIgnoreCase("create")) return partial(new ArrayList<>(groups.keySet()), args[2]);
        if (args.length == 2 && args[0].equalsIgnoreCase("arena")) return partial(List.of("create", "setspawn", "info", "delete"), args[1]);
        if (args.length == 3 && args[0].equalsIgnoreCase("arena") && args[1].equalsIgnoreCase("create")) return partial(new ArrayList<>(groups.keySet()), args[2]);
        if (args.length == 3 && args[0].equalsIgnoreCase("arena") && !args[1].equalsIgnoreCase("create")) return partial(new ArrayList<>(dungeons.keySet()), args[2]);
        return List.of();
    }

    private List<String> partial(
            List<String> values,
            String prefix
    ) {

        return values.stream()
                .filter(
                        x ->
                                x.toLowerCase()
                                        .startsWith(
                                                prefix.toLowerCase()
                                        )
                )
                .sorted()
                .toList();
    }
}