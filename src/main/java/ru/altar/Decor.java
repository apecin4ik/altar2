package ru.altar;

import org.bukkit.HeightMap;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.Axis;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Orientable;
import org.bukkit.block.data.Rotatable;
import org.bukkit.block.data.type.Candle;
import org.bukkit.block.data.type.Lantern;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Мрачный декор вокруг постройки. Рисуется ТОЛЬКО вне схематики (плюс «корона» на её вершине).
 * Всё это входит в снимок местности и откатывается при завершении ивента.
 */
final class Decor {

    private Decor() { }

    private static int surface(World w, int x, int z) {
        return w.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
    }

    /** @return верхние точки столбов (для частиц дыма) */
    static List<Block> apply(World w, int minX, int minZ, int sx, int sy, int sz, int baseY, int margin, Random r) {
        int maxX = minX + sx - 1, maxZ = minZ + sz - 1;

        corruptGround(w, minX, minZ, maxX, maxZ, margin, r);
        lavaPools(w, minX, minZ, maxX, maxZ, r);
        graves(w, minX, minZ, maxX, maxZ, r);
        bonePiles(w, minX, minZ, maxX, maxZ, r);
        ruins(w, minX, minZ, maxX, maxZ, r);
        for (int i = 0; i < 12; i++) deadTree(w, minX, minZ, maxX, maxZ, r);

        List<Block> tops = new ArrayList<>();
        int[][] corners = {{minX - 4, minZ - 4}, {maxX + 4, minZ - 4}, {minX - 4, maxZ + 4}, {maxX + 4, maxZ + 4}};
        for (int[] c : corners) tops.add(pillar(w, c[0], c[1], 8 + r.nextInt(3), true, r));
        int mx = (minX + maxX) / 2, mz = (minZ + maxZ) / 2;
        int[][] mids = {{mx + 6, minZ - 6}, {mx - 6, maxZ + 6}, {minX - 6, mz - 6}, {maxX + 6, mz + 6}};
        for (int[] c : mids) tops.add(pillar(w, c[0], c[1], 5, false, r));

        crown(w, mx, mz, baseY, sy);
        return tops;
    }

    /* ───────── земля ───────── */

    private static void corruptGround(World w, int minX, int minZ, int maxX, int maxZ, int margin, Random r) {
        for (int x = minX - margin; x <= maxX + margin; x++) {
            for (int z = minZ - margin; z <= maxZ + margin; z++) {
                int dx = Math.max(Math.max(minX - x, x - maxX), 0);
                int dz = Math.max(Math.max(minZ - z, z - maxZ), 0);
                int d = Math.max(dx, dz);
                if (d == 0) continue;
                if (r.nextDouble() > 1.0 - d * 0.055) continue;

                int y = surface(w, x, z);
                Block top = w.getBlockAt(x, y, z);
                if (top.isLiquid() || top.getType().isAir()) continue;
                Block above = top.getRelative(BlockFace.UP);
                boolean free = above.getType().isAir();

                Material m = ground(r, d);
                top.setType(m, false);
                if (!free) continue;

                double p = r.nextDouble();
                switch (m) {
                    case SOUL_SOIL -> { if (p < 0.25) above.setType(Material.SOUL_FIRE, false); }
                    case BLACKSTONE -> {
                        if (p < 0.05) skull(above, r);
                        else if (p < 0.05 + (d >= 2 ? 0.04 : 0)) spike(above, r);
                    }
                    case CRIMSON_NYLIUM -> {
                        if (p < 0.20) above.setType(Material.CRIMSON_ROOTS, false);
                        else if (p < 0.27) above.setType(Material.CRIMSON_FUNGUS, false);
                    }
                    case COBBLED_DEEPSLATE -> { if (p < 0.05) above.setType(Material.COBWEB, false); }
                    default -> { }
                }
            }
        }
    }

    /** Мрачная «порча» земли. Используется и в {@link Terrain} для ровной площадки. */
    static Material ground(Random r, int d) {
        int v = r.nextInt(100);
        if (d <= 2 && v < 25) return Material.CRYING_OBSIDIAN;
        if (v < 33) return Material.BLACKSTONE;
        if (v < 53) return Material.SOUL_SOIL;
        if (v < 66) return Material.COBBLED_DEEPSLATE;
        if (v < 75) return Material.BASALT;
        if (v < 84) return Material.CRIMSON_NYLIUM;
        if (v < 91) return Material.NETHERRACK;
        if (v < 95) return Material.NETHER_WART_BLOCK;   // «кровавые» пятна
        return Material.CRYING_OBSIDIAN;
    }

    /* ───────── лавовые лужицы 3x3 с чёрным бортом ───────── */

    private static void lavaPools(World w, int minX, int minZ, int maxX, int maxZ, Random r) {
        int made = 0;
        for (int tries = 0; tries < 40 && made < 3; tries++) {
            int[] p = ringPoint(minX, minZ, maxX, maxZ, 6, 10, r);
            int y = surface(w, p[0], p[1]);
            boolean ok = true;
            for (int dx = -1; dx <= 1 && ok; dx++)
                for (int dz = -1; dz <= 1 && ok; dz++) {
                    int yy = surface(w, p[0] + dx, p[1] + dz);
                    Block b = w.getBlockAt(p[0] + dx, yy, p[1] + dz);
                    if (yy != y || b.isLiquid() || !b.getType().isSolid()) ok = false;
                    if (!w.getBlockAt(p[0] + dx, yy + 1, p[1] + dz).getType().isAir()) ok = false;
                }
            if (!ok) continue;
            for (int dx = -1; dx <= 1; dx++)
                for (int dz = -1; dz <= 1; dz++)
                    w.getBlockAt(p[0] + dx, y, p[1] + dz).setType(dx == 0 && dz == 0 ? Material.LAVA : Material.BLACKSTONE, false);
            made++;
        }
    }

    /* ───────── могилы ───────── */

    private static void graves(World w, int minX, int minZ, int maxX, int maxZ, Random r) {
        for (int i = 0; i < 9; i++) {
            int[] p = ringPoint(minX, minZ, maxX, maxZ, 3, 10, r);
            int y = surface(w, p[0], p[1]);
            Block g = w.getBlockAt(p[0], y, p[1]);
            if (g.isLiquid() || !g.getType().isSolid()) continue;
            Block a1 = g.getRelative(BlockFace.UP);
            if (!a1.getType().isAir()) continue;
            g.setType(Material.COARSE_DIRT, false);
            w.getBlockAt(p[0] + 1, surface(w, p[0] + 1, p[1]), p[1]).setType(Material.COARSE_DIRT, false);
            a1.setType(r.nextBoolean() ? Material.COBBLED_DEEPSLATE_WALL : Material.POLISHED_BLACKSTONE_BRICK_WALL, false);
            Block a2 = a1.getRelative(BlockFace.UP);
            if (a2.getType().isAir()) a2.setType(Material.POLISHED_BLACKSTONE_BRICK_SLAB, false);

            // чёрные/красные свечи у могилы
            if (r.nextInt(3) != 0) {
                Block cg = w.getBlockAt(p[0] - 1, surface(w, p[0] - 1, p[1]), p[1]);
                Block c = cg.getRelative(BlockFace.UP);
                if (cg.getType().isSolid() && !cg.isLiquid() && c.getType().isAir()) candle(c, r);
            }
        }
    }

    /* ───────── кости ───────── */

    private static void bonePiles(World w, int minX, int minZ, int maxX, int maxZ, Random r) {
        for (int i = 0; i < 8; i++) {
            int[] p = ringPoint(minX, minZ, maxX, maxZ, 2, 9, r);
            Block g = w.getBlockAt(p[0], surface(w, p[0], p[1]), p[1]);
            if (g.isLiquid() || !g.getType().isSolid()) continue;
            Block a = g.getRelative(BlockFace.UP);
            if (!a.getType().isAir()) continue;
            a.setType(Material.BONE_BLOCK, false);
            Block a2 = a.getRelative(BlockFace.UP);
            if (a2.getType().isAir()) {
                int v = r.nextInt(4);
                if (v < 2) skullOf(a2, v == 0 ? Material.SKELETON_SKULL : Material.WITHER_SKELETON_SKULL, r);
                else if (v == 2) candle(a2, r);
            }
        }
    }

    /* ───────── руины: обломки стен вокруг ───────── */

    private static void ruins(World w, int minX, int minZ, int maxX, int maxZ, Random r) {
        for (int i = 0; i < 7; i++) {
            int[] p = ringPoint(minX, minZ, maxX, maxZ, 5, 10, r);
            boolean alongX = r.nextBoolean();
            int len = 3 + r.nextInt(5);
            for (int k = 0; k < len; k++) {
                int x = p[0] + (alongX ? k : 0), z = p[1] + (alongX ? 0 : k);
                Block g = w.getBlockAt(x, surface(w, x, z), z);
                if (g.isLiquid() || !g.getType().isSolid()) continue;
                int h = 1 + r.nextInt(4);                       // обвалившаяся, рваная кладка
                if (k == 0 || k == len - 1) h = Math.max(1, h - 1);
                Block last = g;
                for (int j = 1; j <= h; j++) {
                    Block b = g.getRelative(0, j, 0);
                    if (!b.getType().isAir()) break;
                    int v = r.nextInt(10);
                    b.setType(v < 5 ? Material.POLISHED_BLACKSTONE_BRICKS
                            : v < 8 ? Material.CRACKED_POLISHED_BLACKSTONE_BRICKS
                            : Material.BLACKSTONE, false);
                    last = b;
                }
                Block cap = last.getRelative(BlockFace.UP);
                if (cap.getType().isAir()) {
                    int v = r.nextInt(8);
                    if (v == 0) cap.setType(Material.IRON_BARS, false);
                    else if (v == 1) cap.setType(Material.COBWEB, false);
                    else if (v == 2) skullOf(cap, Material.WITHER_SKELETON_SKULL, r);
                }
            }
        }
    }

    /* ───────── мёртвые деревья ───────── */

    private static void deadTree(World w, int minX, int minZ, int maxX, int maxZ, Random r) {
        int[] p = ringPoint(minX, minZ, maxX, maxZ, 5, 10, r);
        int y = surface(w, p[0], p[1]);
        Block g = w.getBlockAt(p[0], y, p[1]);
        if (g.isLiquid() || !g.getType().isSolid()) return;
        g.setType(Material.SOUL_SOIL, false);
        int h = 5 + r.nextInt(4);
        for (int i = 1; i <= h; i++) {
            Block b = w.getBlockAt(p[0], y + i, p[1]);
            if (!b.getType().isAir() && !b.isPassable()) return;
            b.setType(Material.STRIPPED_CRIMSON_STEM, false);
        }
        BlockFace[] dirs = {BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST};
        int branches = 2 + r.nextInt(3);
        for (int k = 0; k < branches; k++) {
            BlockFace f = dirs[r.nextInt(4)];
            int by = y + 3 + r.nextInt(Math.max(1, h - 3));
            int len = 1 + r.nextInt(3);
            Block cur = w.getBlockAt(p[0], by, p[1]);
            for (int i = 0; i < len; i++) {
                cur = cur.getRelative(f);
                if (!cur.getType().isAir()) break;
                setLog(cur, f == BlockFace.NORTH || f == BlockFace.SOUTH ? Axis.Z : Axis.X);
                if (i == len - 1 && r.nextBoolean()) {
                    Block up = cur.getRelative(BlockFace.UP);
                    if (up.getType().isAir()) setLog(up, Axis.Y);
                }
            }
        }
    }

    private static void setLog(Block b, Axis axis) {
        b.setType(Material.STRIPPED_CRIMSON_STEM, false);
        BlockData bd = b.getBlockData();
        if (bd instanceof Orientable o) { o.setAxis(axis); b.setBlockData(o, false); }
    }

    /* ───────── столбы ───────── */

    private static Block pillar(World w, int x, int z, int h, boolean lanterns, Random r) {
        int y0 = surface(w, x, z) + 1;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int yy = y0 - 1; yy > y0 - 9 && yy > w.getMinHeight(); yy--) {
                    Block b = w.getBlockAt(x + dx, yy, z + dz);
                    if (b.getType().isSolid()) break;
                    b.setType(Material.BLACKSTONE, false);
                }
                w.getBlockAt(x + dx, y0, z + dz).setType(Material.POLISHED_BLACKSTONE_BRICKS, false);
            }
        }
        for (int i = 1; i <= h; i++) {
            Material m = (i % 3 == 0) ? Material.CHISELED_POLISHED_BLACKSTONE : Material.POLISHED_BLACKSTONE_BRICKS;
            if (i == 1) m = Material.GILDED_BLACKSTONE;
            w.getBlockAt(x, y0 + i, z).setType(m, false);
        }
        w.getBlockAt(x, y0 + h, z).setType(Material.CHISELED_POLISHED_BLACKSTONE, false);
        Block top = w.getBlockAt(x, y0 + h + 1, z);
        top.setType(Material.SOUL_CAMPFIRE, false);

        if (lanterns) {
            BlockFace[] dirs = {BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST};
            for (BlockFace f : dirs) {
                Block arm = w.getBlockAt(x, y0 + h - 1, z).getRelative(f);
                arm.setType(Material.POLISHED_BLACKSTONE_BRICK_WALL, false);
                Block c1 = arm.getRelative(BlockFace.DOWN);
                Block c2 = c1.getRelative(BlockFace.DOWN);
                Block lan = c2.getRelative(BlockFace.DOWN);
                c1.setType(Material.CHAIN, false);
                c2.setType(Material.CHAIN, false);
                lan.setType(Material.SOUL_LANTERN, false);
                if (lan.getBlockData() instanceof Lantern l) { l.setHanging(true); lan.setBlockData(l, false); }
            }
        }
        return top;
    }

    /* ───────── корона на вершине постройки ───────── */

    private static void crown(World w, int cx, int cz, int baseY, int sy) {
        int topY = -1;
        for (int y = baseY + sy + 1; y >= baseY; y--) {
            if (!w.getBlockAt(cx, y, cz).getType().isAir()) { topY = y; break; }
        }
        if (topY < 0) return;
        Block c = w.getBlockAt(cx, topY + 1, cz);
        if (!c.getType().isAir()) return;
        c.setType(Material.SOUL_CAMPFIRE, false);
        BlockFace[] dirs = {BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST};
        for (BlockFace f : dirs) {
            Block s = c.getRelative(f);
            if (s.getType().isAir() && s.getRelative(BlockFace.DOWN).getType().isSolid()) skullOf(s, Material.WITHER_SKELETON_SKULL, new Random());
        }
    }

    /* ───────── утилиты ───────── */

    private static int[] ringPoint(int minX, int minZ, int maxX, int maxZ, int dMin, int dMax, Random r) {
        int d = dMin + r.nextInt(dMax - dMin + 1);
        int side = r.nextInt(4);
        int x, z;
        switch (side) {
            case 0 -> { x = minX - d; z = minZ - d + r.nextInt(maxZ - minZ + 1 + 2 * d); }
            case 1 -> { x = maxX + d; z = minZ - d + r.nextInt(maxZ - minZ + 1 + 2 * d); }
            case 2 -> { z = minZ - d; x = minX - d + r.nextInt(maxX - minX + 1 + 2 * d); }
            default -> { z = maxZ + d; x = minX - d + r.nextInt(maxX - minX + 1 + 2 * d); }
        }
        return new int[]{x, z};
    }

    private static void candle(Block b, Random r) {
        b.setType(r.nextInt(3) == 0 ? Material.RED_CANDLE : Material.BLACK_CANDLE, false);
        if (b.getBlockData() instanceof Candle c) {
            c.setCandles(1 + r.nextInt(4));
            c.setLit(true);
            b.setBlockData(c, false);
        }
    }

    private static void skull(Block b, Random r) { skullOf(b, Material.WITHER_SKELETON_SKULL, r); }

    private static void skullOf(Block b, Material m, Random r) {
        b.setType(m, false);
        if (b.getBlockData() instanceof Rotatable rot) {
            BlockFace[] faces = {BlockFace.NORTH, BlockFace.NORTH_EAST, BlockFace.EAST, BlockFace.SOUTH_EAST,
                    BlockFace.SOUTH, BlockFace.SOUTH_WEST, BlockFace.WEST, BlockFace.NORTH_WEST,
                    BlockFace.NORTH_NORTH_EAST, BlockFace.EAST_NORTH_EAST, BlockFace.EAST_SOUTH_EAST, BlockFace.SOUTH_SOUTH_EAST,
                    BlockFace.SOUTH_SOUTH_WEST, BlockFace.WEST_SOUTH_WEST, BlockFace.WEST_NORTH_WEST, BlockFace.NORTH_NORTH_WEST};
            rot.setRotation(faces[r.nextInt(faces.length)]);
            b.setBlockData(rot, false);
        }
    }

    private static void spike(Block b, Random r) {
        int h = 2 + r.nextInt(4);
        for (int i = 0; i < h; i++) {
            Block t = b.getRelative(0, i, 0);
            if (!t.getType().isAir()) break;
            t.setType(i == h - 1 ? Material.POLISHED_BLACKSTONE_BRICK_WALL : Material.BLACKSTONE_WALL, false);
        }
        Block cap = b.getRelative(0, h, 0);
        if (r.nextInt(3) == 0 && cap.getType().isAir()) skullOf(cap, Material.WITHER_SKELETON_SKULL, r);
    }
}
