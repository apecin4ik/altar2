package ru.altar;

import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.extent.clipboard.BlockArrayClipboard;
import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.function.operation.ForwardExtentCopy;
import com.sk89q.worldedit.function.operation.Operations;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.CuboidRegion;
import com.sk89q.worldedit.regions.Region;
import com.sk89q.worldedit.session.ClipboardHolder;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.WeatherType;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.Block;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionData;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionType;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

final class AltarSession {

    final AltarPlugin pl;
    final World world;
    final Random rnd = new Random();

    int minX, minZ, baseY, sx, sy, sz;
    int flat, blend;
    Location center, chestLoc;
    BlockArrayClipboard snap;
    BlockVector3 snapMin;

    Inventory inv;
    boolean opened;
    int elapsed;
    int endAt;
    int totalGuards;
    int tickCount;

    private final Set<UUID> guards = new HashSet<>();
    private final Set<UUID> raged = new HashSet<>();
    private final Map<UUID, Location> homes = new HashMap<>();
    private final List<Block> pillarTops = new ArrayList<>();
    private final List<int[]> tickets = new ArrayList<>();
    private final Set<UUID> darkened = new HashSet<>();
    private UUID holoId;
    private BossBar bar;
    private BukkitTask task;

    AltarSession(AltarPlugin pl, World world) {
        this.pl = pl;
        this.world = world;
    }

    private FileConfiguration cfg() { return pl.getConfig(); }

    /* ═════════════════ старт ═════════════════ */

    boolean start() {
        Clipboard cb = pl.schem;
        if (cb == null) return false;
        Region reg = cb.getRegion();
        sx = reg.getWidth(); sy = reg.getHeight(); sz = reg.getLength();
        flat = Math.max(0, cfg().getInt("flatten-radius", 11));
        blend = Math.max(1, cfg().getInt("flatten-blend", 7));
        if (!findSpot(flat + blend)) return false;

        com.sk89q.worldedit.world.World wew = BukkitAdapter.adapt(world);
        // отступ снимка должен покрывать и ровную площадку со склоном, и декор
        int m = Math.max(cfg().getInt("decor-margin", 20), flat + blend + 2);
        snapMin = BlockVector3.at(minX - m, Math.max(world.getMinHeight(), baseY - 14), minZ - m);
        BlockVector3 snapMax = BlockVector3.at(minX + sx - 1 + m, Math.min(world.getMaxHeight() - 1, baseY + sy + 6), minZ + sz - 1 + m);
        CuboidRegion sr = new CuboidRegion(wew, snapMin, snapMax);
        snap = new BlockArrayClipboard(sr);

        try {
            try (EditSession es = WorldEdit.getInstance().newEditSession(wew)) {
                ForwardExtentCopy fc = new ForwardExtentCopy(es, sr, snap, sr.getMinimumPoint());
                fc.setCopyingEntities(false);
                Operations.complete(fc);
            }
            pl.saveSnapshot(snap, world, snapMin);

            // ровная площадка: постройка и декор стоят на одном уровне, склон плавно уходит в природу
            Terrain.level(world, minX, minZ, minX + sx - 1, minZ + sz - 1, baseY,
                    flat, blend, snapMin.getBlockY(), snapMax.getBlockY(), rnd);

            try (EditSession es = WorldEdit.getInstance().newEditSession(wew)) {
                // в схематике origin может быть далеко от самой постройки — выравниваем по её углу
                BlockVector3 to = BlockVector3.at(minX, baseY, minZ).subtract(reg.getMinimumPoint()).add(cb.getOrigin());
                Operations.complete(new ClipboardHolder(cb).createPaste(es).to(to).ignoreAirBlocks(false).build());
            }
        } catch (Exception ex) {
            pl.getLogger().warning("Ошибка вставки схематики: " + ex);
            restore();
            pl.clearState();
            return false;
        }

        darkenSchematic();

        // подсыпка под постройкой (чтобы не висела над ямами)
        for (int x = minX; x < minX + sx; x++)
            for (int z = minZ; z < minZ + sz; z++)
                for (int y = baseY - 1; y > baseY - 14 && y > world.getMinHeight(); y--) {
                    Block b = world.getBlockAt(x, y, z);
                    if (b.getType().isSolid()) break;
                    b.setType(Material.BLACKSTONE, false);
                }

        pillarTops.addAll(Decor.apply(world, minX, minZ, sx, sy, sz, baseY, m, rnd));

        List<Integer> cl = cfg().getIntegerList("chest-local");
        if (cl.size() < 3) cl = List.of(sx / 2, 4, sz / 2);
        chestLoc = new Location(world, minX + cl.get(0), baseY + cl.get(1), minZ + cl.get(2));
        int clear = cfg().getInt("clear-above", 3);
        for (int i = 1; i <= clear; i++) chestLoc.clone().add(0, i, 0).getBlock().setType(Material.AIR, false);
        chestLoc.getBlock().setType(Material.RED_SHULKER_BOX, false);
        center = new Location(world, minX + sx / 2.0, baseY, minZ + sz / 2.0);

        // держим чанки загруженными на время ивента
        double lr = cfg().getDouble("guardians.leash-radius", 22) + 4;
        for (int cx = ((int) (center.getX() - lr)) >> 4; cx <= ((int) (center.getX() + lr)) >> 4; cx++)
            for (int cz = ((int) (center.getZ() - lr)) >> 4; cz <= ((int) (center.getZ() + lr)) >> 4; cz++) {
                world.addPluginChunkTicket(cx, cz, pl);
                tickets.add(new int[]{cx, cz});
            }

        endAt = cfg().getInt("open-seconds", 300) + cfg().getInt("after-open-seconds", 900);
        spawnHolo();
        spawnGuardians();

        bar = Bukkit.createBossBar("", BarColor.RED, BarStyle.SEGMENTED_20);
        bar.setVisible(true);
        updateBar();

        announceStart();
        world.strikeLightningEffect(chestLoc);
        task = Bukkit.getScheduler().runTaskTimer(pl, this::tick, 10L, 10L);
        return true;
    }

    private boolean findSpot(int reach) {
        int lo = cfg().getInt("spawn-min", -5000), hi = cfg().getInt("spawn-max", 5000);
        int tol = cfg().getInt("flat-tolerance", 4);
        for (int i = 0; i < 60; i++) {
            int cx = lo + rnd.nextInt(hi - lo + 1), cz = lo + rnd.nextInt(hi - lo + 1);
            int x0 = cx - sx / 2, z0 = cz - sz / 2;
            int[][] pts = {{0, 0}, {sx - 1, 0}, {0, sz - 1}, {sx - 1, sz - 1}, {sx / 2, sz / 2},
                    {sx / 2, 0}, {0, sz / 2}, {sx - 1, sz / 2}, {sx / 2, sz - 1}};
            int mn = Integer.MAX_VALUE, mx = Integer.MIN_VALUE, sum = 0;
            boolean bad = false;
            for (int[] p : pts) {
                int x = x0 + p[0], z = z0 + p[1];
                if (Terrain.isWet(world, x, z)) { bad = true; break; }
                int y = Terrain.groundY(world, x, z);          // деревья не считаем землёй
                if (y <= world.getMinHeight() + 2) { bad = true; break; }
                mn = Math.min(mn, y); mx = Math.max(mx, y); sum += y;
            }
            if (bad || mx - mn > tol) continue;
            int by = (int) Math.round(sum / (double) pts.length);
            if (by + sy + 8 >= world.getMaxHeight()) continue;

            // края будущей площадки: без воды и без обрывов, иначе склон получится кривым
            int ex0 = x0 - reach, ez0 = z0 - reach, ex1 = x0 + sx - 1 + reach, ez1 = z0 + sz - 1 + reach;
            int[][] ring = {{ex0, ez0}, {ex1, ez0}, {ex0, ez1}, {ex1, ez1},
                    {(ex0 + ex1) / 2, ez0}, {(ex0 + ex1) / 2, ez1}, {ex0, (ez0 + ez1) / 2}, {ex1, (ez0 + ez1) / 2}};
            for (int[] p : ring) {
                if (Terrain.isWet(world, p[0], p[1]) || Math.abs(Terrain.groundY(world, p[0], p[1]) - by) > 12) {
                    bad = true; break;
                }
            }
            if (bad) continue;

            minX = x0; minZ = z0; baseY = by;
            return true;
        }
        return false;
    }

    /** Убираем зелёный дёрн из схематики — остальное в постройке не трогаем. */
    private void darkenSchematic() {
        if (!cfg().getBoolean("darken-schematic", true)) return;
        for (int x = minX; x < minX + sx; x++)
            for (int z = minZ; z < minZ + sz; z++)
                for (int y = baseY; y <= baseY + 3; y++) {
                    Block b = world.getBlockAt(x, y, z);
                    Material t = b.getType();
                    if (t == Material.GRASS_BLOCK) {
                        b.setType(Decor.ground(rnd, 3), false);
                    } else if (t == Material.DIRT && !b.getRelative(0, 1, 0).getType().isSolid()) {
                        b.setType(rnd.nextBoolean() ? Material.COARSE_DIRT : Material.SOUL_SOIL, false);
                    }
                }
    }

    private void announceStart() {
        String line = "§8§m                                                ";
        int open = cfg().getInt("open-seconds", 300), max = open + cfg().getInt("after-open-seconds", 900);
        Bukkit.broadcastMessage(line);
        Bukkit.broadcastMessage("  §4§l⚔ §c§lОГНЕННЫЙ АЛТАРЬ §4§lпробудился! §4§l⚔");
        Bukkit.broadcastMessage("  §7Координаты: §f" + coords());
        Bukkit.broadcastMessage("  §7Шалкер откроется через §f" + open / 60 + " мин §8| §7алтарь исчезнет ровно через §f" + cfg().getInt("after-open-seconds", 900) / 60 + " мин после открытия");
        Bukkit.broadcastMessage("  §7Алтарь охраняют §c" + totalGuards + " стража §7в незерите. §8Команда: §f/altar");
        Bukkit.broadcastMessage(line);
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.playSound(p.getLocation(), Sound.ENTITY_WITHER_SPAWN, 0.5f, 0.7f);
            p.sendTitle("§4§l⚔ ОГНЕННЫЙ АЛТАРЬ ⚔", "§cОн пробудился...", 10, 50, 20);
        }
    }

    /* ═════════════════ стражи ═════════════════ */

    private void spawnGuardians() {
        totalGuards = cfg().getInt("guardians.count", 2);
        double mx = minX + sx / 2.0;
        double[][] spots = {{mx, minZ - 2.5}, {mx, minZ + sz + 2.5}, {minX - 2.5, minZ + sz / 2.0}, {minX + sx + 2.5, minZ + sz / 2.0}};
        for (int i = 0; i < totalGuards; i++) {
            double[] s = spots[i % spots.length];
            int y = world.getHighestBlockYAt((int) s[0], (int) s[1], HeightMap.MOTION_BLOCKING_NO_LEAVES) + 1;
            spawnGuard(new Location(world, s[0] + 0.5, y, s[1] + 0.5));
        }
    }

    private void spawnGuard(Location at) {
        EntityType type;
        try { type = EntityType.valueOf(cfg().getString("guardians.type", "WITHER_SKELETON")); }
        catch (Exception e) { type = EntityType.WITHER_SKELETON; }
        double hp = cfg().getDouble("guardians.health", 80);
        double dmg = cfg().getDouble("guardians.damage", 6);
        double speed = cfg().getDouble("guardians.speed", 0.32);

        Entity ent = world.spawnEntity(at, type);
        if (!(ent instanceof Mob m)) { ent.remove(); return; }
        setAttr(m, Attribute.GENERIC_MAX_HEALTH, hp);
        m.setHealth(hp);
        setAttr(m, Attribute.GENERIC_ATTACK_DAMAGE, attackBase(dmg));
        setAttr(m, Attribute.GENERIC_MOVEMENT_SPEED, speed);
        setAttr(m, Attribute.GENERIC_FOLLOW_RANGE, 24);
        setAttr(m, Attribute.GENERIC_KNOCKBACK_RESISTANCE, 0.6);

        m.setRemoveWhenFarAway(false);
        m.setCanPickupItems(false);
        m.setCustomNameVisible(true);
        m.getPersistentDataContainer().set(pl.markKey, PersistentDataType.BYTE, (byte) 1);
        equip(m);
        Bukkit.getScheduler().runTask(pl, () -> equip(m)); // на случай, если спавн докинул экипировку

        guards.add(m.getUniqueId());
        homes.put(m.getUniqueId(), at.clone());
        m.setCustomName(guardName(m));
    }

    private void equip(Mob m) {
        var eq = m.getEquipment();
        if (eq == null) return;
        eq.setHelmet(armor(Material.NETHERITE_HELMET));
        eq.setChestplate(armor(Material.NETHERITE_CHESTPLATE));
        eq.setLeggings(armor(Material.NETHERITE_LEGGINGS));
        eq.setBoots(armor(Material.NETHERITE_BOOTS));
        eq.setItemInMainHand(guardSword());
        if (!raged.contains(m.getUniqueId())) eq.setItemInOffHand(new ItemStack(Material.TOTEM_OF_UNDYING));
        eq.setHelmetDropChance(0); eq.setChestplateDropChance(0);
        eq.setLeggingsDropChance(0); eq.setBootsDropChance(0);
        eq.setItemInMainHandDropChance(0); eq.setItemInOffHandDropChance(0);
    }

    private ItemStack armor(Material mat) {
        ItemStack it = new ItemStack(mat);
        it.addUnsafeEnchantment(Enchantment.PROTECTION_ENVIRONMENTAL, 4);
        return it;
    }

    private void setAttr(LivingEntity e, Attribute a, double v) {
        var inst = e.getAttribute(a);
        if (inst != null) inst.setBaseValue(v);
    }

    private String guardName(LivingEntity m) {
        int hp = (int) Math.ceil(m.getHealth());
        return raged.contains(m.getUniqueId())
                ? "§4§l✖ Страж Алтаря §c§l[ЯРОСТЬ] §8[§c" + hp + "§8]"
                : "§4§l⚔ Страж Алтаря §8[§c" + hp + "§8]";
    }

    /** Тотем «снесён» (сработал) — страж впадает в ярость. */
    void onTotem(Mob m) {
        UUID id = m.getUniqueId();
        raged.add(id);
        double dmg = cfg().getDouble("guardians.damage-no-totem", 8);
        double after = cfg().getDouble("guardians.health-after-totem", 40);
        Bukkit.getScheduler().runTask(pl, () -> {
            if (m.isDead()) return;
            setAttr(m, Attribute.GENERIC_ATTACK_DAMAGE, attackBase(dmg));
            m.removePotionEffect(PotionEffectType.REGENERATION);
            m.removePotionEffect(PotionEffectType.ABSORPTION);
            var maxHp = m.getAttribute(Attribute.GENERIC_MAX_HEALTH);
            m.setHealth(Math.min(maxHp == null ? after : maxHp.getValue(), after));
            m.setCustomName(guardName(m));
            Location l = m.getLocation().add(0, 1, 0);
            world.strikeLightningEffect(m.getLocation());
            world.spawnParticle(Particle.SOUL_FIRE_FLAME, l, 60, .5, .8, .5, .08);
            world.playSound(l, Sound.ENTITY_WITHER_AMBIENT, 1.5f, 0.6f);
            for (Player p : world.getPlayers())
                if (p.getLocation().distanceSquared(l) < 40 * 40)
                    AltarPlugin.actionBar(p, "§4§l✖ §cТотем стража разбит — он в ярости! §7(урон §f" + (int) dmg + "§7)");
        });
    }

    void onGuardDeath(Entity e) {
        guards.remove(e.getUniqueId());
        raged.remove(e.getUniqueId());
        homes.remove(e.getUniqueId());
        for (Player p : world.getPlayers())
            if (p.getLocation().distanceSquared(center) < 80 * 80)
                p.sendMessage(AltarPlugin.PREFIX + "§cСтраж алтаря повержен §8(§7осталось §f" + guards.size() + "§8)");
    }

    boolean isGuard(Entity e) { return guards.contains(e.getUniqueId()); }
    int guardCount() { return guards.size(); }
    boolean owns(Entity e) { return guards.contains(e.getUniqueId()) || e.getUniqueId().equals(holoId); }

    /** Не даём стражам покидать территорию. */
    private void leash() {
        double r = cfg().getDouble("guardians.leash-radius", 22);
        for (UUID id : new ArrayList<>(guards)) {
            Entity e = Bukkit.getEntity(id);
            if (!(e instanceof Mob m) || m.isDead()) continue;
            Location home = homes.get(id);
            double dx = m.getLocation().getX() - center.getX(), dz = m.getLocation().getZ() - center.getZ();
            double d2 = dx * dx + dz * dz;
            if (d2 > r * r) {
                m.setTarget(null);
                if (d2 > (r + 3) * (r + 3)) m.teleport(home);
                else m.getPathfinder().moveTo(home);
            } else if (m.getTarget() == null && home != null && m.getLocation().distanceSquared(home) > 16) {
                m.getPathfinder().moveTo(home);
            }
            m.setCustomName(guardName(m));
        }
    }

    boolean inTerritory(Location l) {
        if (l.getWorld() != world) return false;
        double r = cfg().getDouble("guardians.leash-radius", 22);
        double dx = l.getX() - center.getX(), dz = l.getZ() - center.getZ();
        return dx * dx + dz * dz <= r * r;
    }

    /* ═════════════════ шалкер ═════════════════ */

    boolean isChest(Location l) {
        return l.getWorld() == world && l.getBlockX() == chestLoc.getBlockX()
                && l.getBlockY() == chestLoc.getBlockY() && l.getBlockZ() == chestLoc.getBlockZ();
    }

    void click(Player p) {
        if (!opened) {
            int left = cfg().getInt("open-seconds", 300) - elapsed;
            AltarPlugin.actionBar(p, "§c🔒 Шалкер закрыт §7— откроется через §f" + mmss(left));
            p.playSound(p.getLocation(), Sound.BLOCK_CHEST_LOCKED, 1f, 0.8f);
            return;
        }
        p.openInventory(inv);
    }

    private ItemStack named(ItemStack it) {
        ItemMeta m = it.getItemMeta();
        m.setDisplayName("§f⸸ §4§lПредметы алтаря §f⸸");
        it.setItemMeta(m);
        return it;
    }

    private ItemStack harmingArrows() {
        ItemStack it = new ItemStack(Material.TIPPED_ARROW, 32);
        PotionMeta pm = (PotionMeta) it.getItemMeta();
        pm.setBasePotionData(new PotionData(PotionType.INSTANT_DAMAGE, false, true)); // Вред II
        it.setItemMeta(pm);
        return named(it);
    }

    /** «Клинок Алтаря» по конфигу ExecutableItems: незерит, Острота 6, Отдача 2, Огонь 2, Прочность 3, Небесная кара 5, +2 урона. */
    private ItemStack altarSword() {
        ItemStack it = new ItemStack(Material.NETHERITE_SWORD);
        ItemMeta m = it.getItemMeta();
        m.setDisplayName("§f⸸ §4§lКлинок Алтаря §f⸸");
        m.addEnchant(Enchantment.DAMAGE_ALL, 6, true);
        m.addEnchant(Enchantment.KNOCKBACK, 2, true);
        m.addEnchant(Enchantment.FIRE_ASPECT, 2, true);
        m.addEnchant(Enchantment.DURABILITY, 3, true);
        m.addEnchant(Enchantment.DAMAGE_UNDEAD, 5, true);
        m.addAttributeModifier(Attribute.GENERIC_ATTACK_DAMAGE, new AttributeModifier(UUID.randomUUID(), "altar_base_damage", 7.0, AttributeModifier.Operation.ADD_NUMBER, EquipmentSlot.HAND));
        m.addAttributeModifier(Attribute.GENERIC_ATTACK_SPEED, new AttributeModifier(UUID.randomUUID(), "altar_speed", -2.4, AttributeModifier.Operation.ADD_NUMBER, EquipmentSlot.HAND));
        m.addAttributeModifier(Attribute.GENERIC_ATTACK_DAMAGE, new AttributeModifier(UUID.randomUUID(), "altar_bonus_damage", 2.0, AttributeModifier.Operation.ADD_NUMBER, EquipmentSlot.HAND));
        m.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        it.setItemMeta(m);
        return it;
    }

    /** Меч стражей: Острота 5, Огненный аспект 2. Атрибуты меча обнулены — урон считается из базы + Остроты. */
    private ItemStack guardSword() {
        ItemStack it = new ItemStack(Material.NETHERITE_SWORD);
        ItemMeta m = it.getItemMeta();
        m.addEnchant(Enchantment.DAMAGE_ALL, 5, true);
        m.addEnchant(Enchantment.FIRE_ASPECT, 2, true);
        m.addAttributeModifier(Attribute.GENERIC_ATTACK_DAMAGE, new AttributeModifier(UUID.randomUUID(), "guard_zero", 0.0, AttributeModifier.Operation.ADD_NUMBER, EquipmentSlot.HAND));
        it.setItemMeta(m);
        return it;
    }

    /** Бонус Остроты 5 у моба: 1 + 0.5 * (5 - 1) = 3. Вычитаем его, чтобы итоговый урон был ровно 6 / 8. */
    private double attackBase(double total) {
        return Math.max(0, total - 3.0);
    }

    private void open() {
        opened = true;
        endAt = elapsed + cfg().getInt("after-open-seconds", 900);
        chestLoc.getBlock().setType(Material.BLACK_SHULKER_BOX, false);
        inv = Bukkit.createInventory(null, 54, "§f⚔ §4§lОГНЕННЫЙ АЛТАРЬ §f⚔");

        // содержимое шалкера
        inv.setItem(12, named(new ItemStack(Material.TOTEM_OF_UNDYING)));
        inv.setItem(32, named(new ItemStack(Material.TOTEM_OF_UNDYING)));
        inv.setItem(13, named(new ItemStack(Material.END_CRYSTAL, 16)));
        inv.setItem(31, named(new ItemStack(Material.END_CRYSTAL, 16)));
        inv.setItem(14, harmingArrows());
        inv.setItem(30, harmingArrows());
        inv.setItem(21, named(new ItemStack(Material.PINK_SHULKER_BOX)));
        inv.setItem(23, named(new ItemStack(Material.YELLOW_SHULKER_BOX)));

        // «Клинок Алтаря» выпадает не всегда — шанс из конфига (по умолчанию 4%)
        double chance = Math.max(0, Math.min(100, cfg().getDouble("sword-chance-percent", 4.0)));
        if (rnd.nextDouble() * 100.0 < chance) inv.setItem(22, altarSword());

        world.strikeLightningEffect(chestLoc);
        world.playSound(chestLoc, Sound.BLOCK_END_PORTAL_SPAWN, 3f, 0.8f);
        world.spawnParticle(Particle.TOTEM, chestLoc.clone().add(.5, 1, .5), 120, .6, .8, .6, .35);
        Bukkit.broadcastMessage(AltarPlugin.PREFIX + "§a§lШалкер Огненного алтаря открыт! §7Алтарь исчезнет через §f"
                + cfg().getInt("after-open-seconds", 900) / 60 + " мин§7. Координаты: §f" + coords());
        for (Player p : Bukkit.getOnlinePlayers()) p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 0.8f);
    }

    /* ═════════════════ тик ═════════════════ */

    private void tick() {
        tickCount++;
        leash();
        fx();
        if (tickCount % 2 == 0) second();
    }

    private void second() {
        elapsed++;
        int open = cfg().getInt("open-seconds", 300);
        if (!opened && elapsed >= open) open();
        if (elapsed >= endAt) { pl.endSession("§c§lОгненный алтарь рассыпался в прах. §7Ивент завершён."); return; }

        int left = endAt - elapsed;
        if (opened && (left == 60 || left == 30 || left == 10))
            Bukkit.broadcastMessage(AltarPlugin.PREFIX + "§cАлтарь исчезнет через §f" + left + " сек§c!");
        if (!opened && open - elapsed == 60)
            Bukkit.broadcastMessage(AltarPlugin.PREFIX + "§eШалкер откроется через §f1 минуту§e!");

        updateBar();
        updateHolo();
        sky();
        if (elapsed % 12 == 0) randomStrike();
    }

    private void fx() {
        Location c = chestLoc.clone().add(.5, .8, .5);
        world.spawnParticle(Particle.SOUL_FIRE_FLAME, c, 3, .25, .3, .25, .01);
        if (opened) world.spawnParticle(Particle.TOTEM, c, 2, .3, .4, .3, .05);
        else world.spawnParticle(Particle.ENCHANTMENT_TABLE, c, 6, .4, .5, .4, .5);
        for (Block b : pillarTops) world.spawnParticle(Particle.SMOKE_LARGE, b.getLocation().add(.5, 1.2, .5), 1, .1, .2, .1, .01);
        world.spawnParticle(Particle.ASH, center.getX(), baseY + 4, center.getZ(), 30, sx / 2.0 + 6, 6, sz / 2.0 + 6, 0);
        double ang = rnd.nextDouble() * Math.PI * 2, dist = 6 + rnd.nextDouble() * 14;
        world.spawnParticle(Particle.SOUL, center.getX() + Math.cos(ang) * dist, baseY + 1, center.getZ() + Math.sin(ang) * dist, 2, .4, 1.0, .4, 0.02);
        if (tickCount % 30 == 0) world.playSound(center, Sound.AMBIENT_SOUL_SAND_VALLEY_MOOD, 2f, 0.7f);

        // ── мрачная атмосфера: низкий дым-туман, красные споры, блуждающие души ──
        double fx = sx / 2.0 + 10, fz = sz / 2.0 + 10;
        world.spawnParticle(Particle.SMOKE_LARGE, center.getX(), baseY + 0.5, center.getZ(), 5, fx, 0.3, fz, 0.002);
        world.spawnParticle(Particle.CRIMSON_SPORE, center.getX(), baseY + 3, center.getZ(), 12, fx, 3, fz, 0);
        if (tickCount % 3 == 0) {
            double a2 = rnd.nextDouble() * Math.PI * 2, d2 = 4 + rnd.nextDouble() * 18;
            world.spawnParticle(Particle.SCULK_SOUL, center.getX() + Math.cos(a2) * d2, baseY + 1.5,
                    center.getZ() + Math.sin(a2) * d2, 1, .2, .6, .2, 0.02);
        }
        if (tickCount % 16 == 0) ambient();
    }

    /** Редкие жуткие звуки над площадкой. */
    private void ambient() {
        Sound[] pool = {Sound.AMBIENT_CAVE, Sound.ENTITY_WARDEN_HEARTBEAT, Sound.ENTITY_WITHER_AMBIENT,
                Sound.AMBIENT_BASALT_DELTAS_MOOD, Sound.AMBIENT_CAVE};
        Sound s = pool[rnd.nextInt(pool.length)];
        world.playSound(center.clone().add(0, 4, 0), s, 1.6f, 0.5f + rnd.nextFloat() * 0.3f);
    }

    /** Ночное небо и дождь для игроков рядом с алтарём. */
    private void sky() {
        if (!cfg().getBoolean("dark-sky", true)) return;
        double r = cfg().getDouble("dark-sky-radius", 80);
        boolean pulse = cfg().getBoolean("darkness-pulse", false);
        for (Player p : Bukkit.getOnlinePlayers()) {
            boolean near = p.getWorld() == world && p.getLocation().distanceSquared(center) <= r * r;
            if (near) {
                p.setPlayerTime(18000, false);
                p.setPlayerWeather(WeatherType.DOWNFALL);
                darkened.add(p.getUniqueId());
                if (pulse) p.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, 100, 0, true, false, false));
            } else if (darkened.remove(p.getUniqueId())) {
                p.resetPlayerTime();
                p.resetPlayerWeather();
                if (pulse) p.removePotionEffect(PotionEffectType.DARKNESS);
            }
        }
    }

    private void resetSky() {
        for (UUID id : darkened) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                p.resetPlayerTime(); p.resetPlayerWeather();
                if (cfg().getBoolean("darkness-pulse", false)) p.removePotionEffect(PotionEffectType.DARKNESS);
            }
        }
        darkened.clear();
    }

    /** Редкие «декоративные» молнии вокруг постройки (без урона и пожаров). */
    private void randomStrike() {
        double a = rnd.nextDouble() * Math.PI * 2, d = 14 + rnd.nextDouble() * 16;
        int x = (int) (center.getX() + Math.cos(a) * d), z = (int) (center.getZ() + Math.sin(a) * d);
        Location l = new Location(world, x, world.getHighestBlockYAt(x, z), z);
        world.strikeLightningEffect(l);
        world.playSound(l, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 4f, 0.7f);
    }

    private void updateBar() {
        int open = cfg().getInt("open-seconds", 300), after = cfg().getInt("after-open-seconds", 900);
        String title; double prog;
        if (!opened) {
            int left = open - elapsed;
            prog = left / (double) open;
            title = "§4§l⚔ §c§lОГНЕННЫЙ АЛТАРЬ §4§l⚔ §8| §7Шалкер откроется через §f" + mmss(left)
                    + " §8| §7Стражи: §c" + guards.size() + "§7/" + totalGuards;
            bar.setColor(BarColor.RED); bar.setStyle(BarStyle.SEGMENTED_20);
        } else {
            int left = endAt - elapsed;
            prog = left / (double) Math.max(1, after);
            title = "§4§l⚔ §a§lШАЛКЕР ОТКРЫТ §4§l⚔ §8| §7Алтарь исчезнет через §f" + mmss(left)
                    + " §8| §7Стражи: §c" + guards.size() + "§7/" + totalGuards;
            bar.setColor(left <= 30 ? BarColor.RED : left <= 120 ? BarColor.YELLOW : BarColor.GREEN);
            bar.setStyle(BarStyle.SEGMENTED_10);
        }
        bar.setTitle(title);
        bar.setProgress(Math.max(0, Math.min(1, prog)));

        double rad = cfg().getDouble("bossbar-radius", -1);
        for (Player p : Bukkit.getOnlinePlayers()) {
            boolean show = rad < 0 || (p.getWorld() == world && p.getLocation().distanceSquared(center) <= rad * rad);
            if (show) bar.addPlayer(p); else bar.removePlayer(p);
        }
    }

    /* ═════════════════ голограмма ═════════════════ */

    private String holoText() {
        String head = "§f⚔ §4§lОГНЕННЫЙ АЛТАРЬ §f⚔";
        if (opened) return head + "\n§aОткрыто";
        return head + "\n§cЗакрыто!\n§aДо открытия: §f" + mmss(cfg().getInt("open-seconds", 300) - elapsed);
    }

    private void spawnHolo() {
        Location hl = chestLoc.clone().add(.5, 2.0, .5);
        TextDisplay td = world.spawn(hl, TextDisplay.class, t -> {
            t.setBillboard(Display.Billboard.CENTER);
            t.setBackgroundColor(Color.fromARGB(110, 0, 0, 0));
            t.setText(holoText());
            t.getPersistentDataContainer().set(pl.markKey, PersistentDataType.BYTE, (byte) 1);
        });
        holoId = td.getUniqueId();
    }

    private void updateHolo() {
        if (holoId == null) return;
        Entity e = Bukkit.getEntity(holoId);
        if (e instanceof TextDisplay td) td.setText(holoText());
    }

    /* ═════════════════ утилиты ═════════════════ */

    boolean inProtect(Location l) {
        if (l.getWorld() != world) return false;
        double pr = cfg().getDouble("protect-radius", 50);
        double dx = l.getX() - center.getX(), dz = l.getZ() - center.getZ();
        return dx * dx + dz * dz <= pr * pr;
    }

    String coords() {
        return chestLoc.getBlockX() + " " + chestLoc.getBlockY() + " " + chestLoc.getBlockZ() + " §8(§7" + world.getName() + "§8)";
    }

    Location tpLocation() {
        int x = (int) center.getX(), z = minZ - 3;
        int y = world.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES) + 1;
        Location l = new Location(world, x + .5, y, z + .5);
        l.setYaw(0f); // смотрит на юг, на алтарь
        return l;
    }

    static String mmss(int s) {
        s = Math.max(0, s);
        return String.format("%02d:%02d", s / 60, s % 60);
    }

    private void restore() {
        if (snap == null) return;
        try (EditSession es = WorldEdit.getInstance().newEditSession(BukkitAdapter.adapt(world))) {
            Operations.complete(new ClipboardHolder(snap).createPaste(es).to(snapMin).ignoreAirBlocks(false).build());
        } catch (Exception ex) {
            pl.getLogger().warning("Не удалось восстановить местность: " + ex);
        }
    }

    /* ═════════════════ завершение ═════════════════ */

    void end(String reason) {
        if (task != null) task.cancel();
        if (bar != null) bar.removeAll();
        resetSky();

        if (inv != null) for (HumanEntity h : new ArrayList<>(inv.getViewers())) h.closeInventory();
        for (UUID id : guards) { Entity e = Bukkit.getEntity(id); if (e != null) e.remove(); }
        guards.clear();
        if (holoId != null) { Entity e = Bukkit.getEntity(holoId); if (e != null) e.remove(); }
        for (Entity e : world.getEntities())
            if (e.getPersistentDataContainer().has(pl.markKey, PersistentDataType.BYTE)) e.remove();

        if (chestLoc != null) world.playSound(chestLoc, Sound.ENTITY_GENERIC_EXPLODE, 3f, 0.6f);
        restore();
        for (int[] t : tickets) world.removePluginChunkTicket(t[0], t[1], pl);
        pl.clearState();
        Bukkit.broadcastMessage(AltarPlugin.PREFIX + reason);
    }
}
