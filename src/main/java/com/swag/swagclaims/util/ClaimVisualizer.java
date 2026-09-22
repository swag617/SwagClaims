package com.swag.swagclaims.util;

import com.swag.swagclaims.SwagClaimsPlugin;
import com.swag.swagclaims.model.Claim;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
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

    /**
     * Renders a claim's boundary to a single viewer using per-player {@link Particle#DUST}
     * points traced along all four edges, at the viewer's feet and roughly eye height. Unlike
     * {@link #show}, this spawns no real entities and self-cleans (particles are one-shot), so
     * it's cheap enough to call repeatedly on an interval for every player holding a claim tool
     * — see {@code ClaimToolListener#renderBorderPreviews}. {@link Player#spawnParticle} sends
     * the packet only to {@code viewer}, so other nearby players never see it.
     *
     * <p>Color communicates relationship to the viewer: gold for an admin claim, green for a
     * claim the viewer owns, red for anyone else's — matching the "an administrator" treatment
     * already used elsewhere (see {@code ClaimCommandUtil#sendClaimInfo}, {@code OVERLAP} in
     * {@code ClaimToolListener#handleResult}).
     */
    public void showBorderParticles(Player viewer, Claim claim) {
        World world = viewer.getWorld();
        if (!world.getName().equalsIgnoreCase(claim.getWorld())) return;

        Particle.DustOptions dust = colorFor(claim, viewer);
        double y = viewer.getLocation().getY();

        for (int[] point : edgePoints(claim)) {
            double x = point[0] + 0.5;
            double z = point[1] + 0.5;
            viewer.spawnParticle(Particle.DUST, x, y + 0.2, z, 1, 0.0, 0.0, 0.0, 0.0, dust);
            viewer.spawnParticle(Particle.DUST, x, y + 1.2, z, 1, 0.0, 0.0, 0.0, 0.0, dust);
        }
    }

    private Particle.DustOptions colorFor(Claim claim, Player viewer) {
        if (claim.isAdminClaim()) {
            return new Particle.DustOptions(Color.fromRGB(255, 170, 0), 1.0f); // gold
        }
        if (claim.getOwnerUuid() != null && claim.getOwnerUuid().equals(viewer.getUniqueId())) {
            return new Particle.DustOptions(Color.fromRGB(60, 220, 60), 1.0f); // green — your own claim
        }
        return new Particle.DustOptions(Color.fromRGB(220, 60, 60), 1.0f); // red — someone else's claim
    }

    /**
     * Points traced along all four edges of the claim's horizontal bounds, spaced adaptively so
     * the total point count stays bounded (~120) regardless of how large the claim is — this
     * method is called on a fixed interval for every tool-holding player (see
     * {@code ClaimToolListener#renderBorderPreviews}), so an unbounded point count on a huge
     * claim would scale badly; a coarser step on bigger claims keeps the per-call cost flat.
     */
    private List<int[]> edgePoints(Claim claim) {
        int minX = claim.getMinX(), maxX = claim.getMaxX();
        int minZ = claim.getMinZ(), maxZ = claim.getMaxZ();
        long perimeter = 2L * (claim.getWidthX() + claim.getWidthZ());
        int step = Math.max(2, (int) (perimeter / 120L));

        List<int[]> points = new ArrayList<>();
        for (int x = minX; x < maxX; x += step) {
            points.add(new int[]{x, minZ});
            points.add(new int[]{x, maxZ});
        }
        points.add(new int[]{maxX, minZ});
        points.add(new int[]{maxX, maxZ});
        for (int z = minZ; z < maxZ; z += step) {
            points.add(new int[]{minX, z});
            points.add(new int[]{maxX, z});
        }
        points.add(new int[]{minX, maxZ});
        points.add(new int[]{maxX, maxZ});
        return points;
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
