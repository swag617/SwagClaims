package com.swag.swagclaims.util;

import com.swag.swagclaims.SwagClaimsPlugin;
import com.swag.swagclaims.model.Claim;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * Shows a claim's boundary using temporary, real {@link BlockDisplay} entities (glowing gold
 * blocks) at the four corners and four edge midpoints, removed automatically after
 * {@code visualization-duration-seconds}. This is deliberately simple — real client-side-only
 * packet ghosts are out of scope for this phase; spawning short-lived real entities is visible
 * to everyone nearby but requires no packet work and is good enough for a first vertical slice.
 */
public class ClaimVisualizer {

    private final SwagClaimsPlugin plugin;

    public ClaimVisualizer(SwagClaimsPlugin plugin) {
        this.plugin = plugin;
    }

    public void show(Player viewer, Claim claim) {
        World world = Bukkit.getWorld(claim.getWorld());
        if (world == null) return;

        int y = viewer.getLocation().getBlockY();

        List<BlockDisplay> spawned = new ArrayList<>();
        for (int[] point : boundaryPoints(claim)) {
            Location loc = new Location(world, point[0] + 0.5, y + 1.0, point[1] + 0.5);
            try {
                BlockDisplay display = world.spawn(loc, BlockDisplay.class, bd -> {
                    bd.setBlock(Material.GOLD_BLOCK.createBlockData());
                    bd.setGlowing(true);
                    bd.setPersistent(false);
                    bd.setBrightness(new Display.Brightness(15, 15));
                    Transformation transform = new Transformation(
                            new Vector3f(-0.15f, -0.15f, -0.15f),
                            new AxisAngle4f(0f, 0f, 0f, 1f),
                            new Vector3f(0.3f, 0.3f, 0.3f),
                            new AxisAngle4f(0f, 0f, 0f, 1f));
                    bd.setTransformation(transform);
                });
                spawned.add(display);
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to spawn claim visualization display: " + e.getMessage());
            }
        }

        if (spawned.isEmpty()) return;

        long ticks = plugin.getClaimsConfig().getVisualizationDurationSeconds() * 20L;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            for (BlockDisplay display : spawned) {
                if (display != null && !display.isDead()) {
                    display.remove();
                }
            }
        }, ticks);
    }

    /** Four corners plus four edge midpoints of the claim's horizontal bounds. */
    private List<int[]> boundaryPoints(Claim claim) {
        int minX = claim.getMinX(), maxX = claim.getMaxX();
        int minZ = claim.getMinZ(), maxZ = claim.getMaxZ();
        int midX = (minX + maxX) / 2;
        int midZ = (minZ + maxZ) / 2;

        List<int[]> points = new ArrayList<>();
        points.add(new int[]{minX, minZ});
        points.add(new int[]{minX, maxZ});
        points.add(new int[]{maxX, minZ});
        points.add(new int[]{maxX, maxZ});
        points.add(new int[]{midX, minZ});
        points.add(new int[]{midX, maxZ});
        points.add(new int[]{minX, midZ});
        points.add(new int[]{maxX, midZ});
        return points;
    }
}
