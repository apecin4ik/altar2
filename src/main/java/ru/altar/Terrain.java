package ru.altar;

import org.bukkit.HeightMap;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;

import java.util.Random;

/**
 * Выравнивание местности под алтарь.
 * <p>
 * Вокруг постройки делается ровная площадка на уровне {@code baseY} (радиус {@code flat}),
 * дальше идёт плавный склон ({@code blend} блоков) обратно к естественному рельефу.
 * Деревья, листва и вода в зоне убираются. Всё это попадает в снимок местности
 * и откатывается при завершении ивента.
 */
final class Terrain {

    private Terrain() { }

    /** Верхний блок колонки — жидкость? */
    static boolean isWet(World w, int x, int z) {
        int y = w.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
        return w.getBlockAt(x, y, z).isLiquid();
    }

    /** Высота настоящей земли: пропускаем стволы, листву, траву и жидкость сверху. */
    static int groundY(World w, int x, int z) {
        int y = w.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
        int min = w.getMinHeight() + 1;
        while (y > min) {
            Block b = w.getBlockAt(x, y, z);
            Material t = b.getType();
            if (Tag.LOGS.isTagged(t) || Tag.LEAVES.isTagged(t) || b.isPassable()) y--;
            else break;
        }
        return y;
    }

    private static Material fill(Random r) {
        int v = r.nextInt(10);
        if (v < 5) return Material.BLACKSTONE;
        if (v < 8) return Material.COBBLED_DEEPSLATE;
        return Material.BASALT;
    }

    /**
     * @param minX,minZ,maxX,maxZ границы постройки (включительно)
     * @param baseY               уровень земли под постройкой (слой y=0 схематики)
     * @param flat                радиус ровной площадки вокруг постройки
     * @param blend               ширина плавного перехода к естественному рельефу
     * @param yMin,yMax           границы снимка местности — за них ничего не трогаем
     */
    static void level(World w, int minX, int minZ, int maxX, int maxZ, int baseY,
                      int flat, int blend, int yMin, int yMax, Random r) {
        int reach = flat + blend;
        for (int x = minX - reach; x <= maxX + reach; x++) {
            for (int z = minZ - reach; z <= maxZ + reach; z++) {
                int dx = Math.max(Math.max(minX - x, x - maxX), 0);
                int dz = Math.max(Math.max(minZ - z, z - maxZ), 0);
                double dist = Math.sqrt((double) dx * dx + (double) dz * dz);
                if (dist > reach) continue;

                int gy = groundY(w, x, z);
                int target;
                if (dist <= flat) {
                    target = baseY;
                } else {
                    double t = (dist - flat) / blend;
                    double s = t * t * (3 - 2 * t);           // smoothstep: без резких ступеней
                    target = (int) Math.round(baseY + (gy - baseY) * s);
                }
                if (target <= yMin + 1 || target >= yMax - 1) continue;

                // 1. всё выше целевого уровня убираем: холмы, деревья, листву, воду
                int top = Math.min(w.getHighestBlockYAt(x, z, HeightMap.WORLD_SURFACE), yMax);
                for (int y = top; y > target; y--) {
                    Block b = w.getBlockAt(x, y, z);
                    if (!b.getType().isAir()) b.setType(Material.AIR, false);
                }

                // 2. рельеф был ниже — досыпаем до целевого уровня
                for (int y = Math.max(gy + 1, yMin); y <= target; y++) {
                    w.getBlockAt(x, y, z).setType(fill(r), false);
                }

                // 3. верхний слой — мрачная земля (на склоне только там, где рельеф реально менялся)
                if (dist <= flat || target != gy) {
                    int d = Math.max(1, (int) Math.round(dist));
                    w.getBlockAt(x, target, z).setType(Decor.ground(r, d), false);
                }

                // 4. под площадкой не оставляем пустот (пещеры, ямы)
                for (int y = target - 1; y >= Math.max(yMin, target - 14); y--) {
                    Block b = w.getBlockAt(x, y, z);
                    if (b.getType().isSolid()) break;
                    b.setType(fill(r), false);
                }
            }
        }
    }
}
