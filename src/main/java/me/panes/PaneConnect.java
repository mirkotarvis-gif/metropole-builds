package me.panes;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockSupport;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.MultipleFacing;
import org.bukkit.block.data.type.Gate;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.concurrent.CompletableFuture;

/** /connect [radius]: fixes glass panes, iron bars and fences that came in unconnected (WorldEdit / paste). */
public final class PaneConnect extends JavaPlugin {
    private static final BlockFace[] SIDES = {BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST};
    private static final int MAX_RADIUS = 400, CHUNKS_IN_FLIGHT = 8;
    private Job job;

    @Override public boolean onCommand(CommandSender s, Command c, String l, String[] a) {
        if (a.length > 0 && a[0].equalsIgnoreCase("stop")) {
            if (job == null) { s.sendMessage(ChatColor.GRAY + "Nothing running."); return true; }
            job.finish(true); return true;
        }
        if (!(s instanceof Player p)) { s.sendMessage("Run this in game."); return true; }
        if (job != null) { s.sendMessage(ChatColor.RED + "Already running. /connect stop to cancel."); return true; }
        int r = 64;
        if (a.length > 0) try { r = Integer.parseInt(a[0]); } catch (NumberFormatException e) { s.sendMessage(ChatColor.RED + "Usage: /connect [radius]"); return true; }
        if (r < 1 || r > MAX_RADIUS) { s.sendMessage(ChatColor.RED + "Radius has to be 1-" + MAX_RADIUS + "."); return true; }
        job = new Job(p, r); job.start();
        return true;
    }

    // ---------------------------------------------------------------- what connects to what
    static boolean isPane(Material m) { return m == Material.IRON_BARS || m == Material.GLASS_PANE || m.name().endsWith("_STAINED_GLASS_PANE"); }
    static boolean isFence(Material m) { return Tag.FENCES.isTagged(m); }
    static boolean target(Material m) { return isPane(m) || isFence(m); }

    static boolean exception(Material m) {
        return Tag.LEAVES.isTagged(m) || Tag.SHULKER_BOXES.isTagged(m) || m == Material.BARRIER || m == Material.PUMPKIN
                || m == Material.CARVED_PUMPKIN || m == Material.JACK_O_LANTERN || m == Material.MELON;
    }

    static boolean connects(Material self, BlockFace dir, Block other) {
        Material m = other.getType();
        BlockData d = other.getBlockData();
        boolean sturdy = !exception(m) && d.isFaceSturdy(dir.getOppositeFace(), BlockSupport.FULL);
        if (isPane(self)) return isPane(m) || Tag.WALLS.isTagged(m) || sturdy;
        // fences: wooden fences join each other, nether brick joins nether brick, both join gates side-on and full faces
        if (isFence(m)) return Tag.WOODEN_FENCES.isTagged(self) == Tag.WOODEN_FENCES.isTagged(m);
        if (d instanceof Gate g) return axis(g.getFacing()) != axis(dir);
        return sturdy;
    }
    static int axis(BlockFace f) { return (f == BlockFace.NORTH || f == BlockFace.SOUTH) ? 0 : 1; }

    // ---------------------------------------------------------------- the job
    private final class Job {
        final Player p; final World w; final int r, cx, cz, bx, bz;
        final ArrayDeque<long[]> chunks = new ArrayDeque<>();
        final Set<Long> tickets = new HashSet<>(), asked = new HashSet<>();
        int inFlight = 0, total, scanned = 0, fixed = 0, found = 0;
        final ArrayDeque<int[]> todo = new ArrayDeque<>();
        BukkitTask task; long lastMsg = 0;

        Job(Player p, int r) {
            this.p = p; this.w = p.getWorld(); this.r = r;
            bx = p.getLocation().getBlockX(); bz = p.getLocation().getBlockZ();
            cx = bx >> 4; cz = bz >> 4;
            int cr = (r >> 4) + 1;
            for (int x = cx - cr; x <= cx + cr; x++) for (int z = cz - cr; z <= cz + cr; z++) chunks.add(new long[]{x, z});
            total = chunks.size();
        }

        void start() {
            p.sendMessage(ChatColor.GOLD + "Connecting panes, iron bars and fences in a " + r + " block radius (" + total + " chunks)...");
            task = Bukkit.getScheduler().runTaskTimer(PaneConnect.this, this::tick, 1, 1);
        }

        void tick() {
            long end = System.nanoTime() + 20_000_000L;
            // load a few chunks at a time (async), scan them as they arrive
            while (inFlight < CHUNKS_IN_FLIGHT && !chunks.isEmpty()) {
                long[] c = chunks.poll(); inFlight++;
                int x = (int) c[0], z = (int) c[1];
                CompletableFuture<Chunk> f = w.getChunkAtAsync(x, z, false);
                f.whenComplete((ch, err) -> Bukkit.getScheduler().runTask(PaneConnect.this, () -> {
                    inFlight--;
                    if (job != this) return;
                    if (ch != null) scan(ch);
                    scanned++;
                }));
            }
            // fix blocks, but only once all 4 neighbours' chunks are loaded so nothing loads on the main thread
            int n = todo.size();
            while (n-- > 0 && System.nanoTime() < end) {
                int[] b = todo.poll();
                if (!neighboursLoaded(b[0], b[2])) { todo.add(b); continue; }
                fix(w.getBlockAt(b[0], b[1], b[2]));
            }
            if (System.currentTimeMillis() - lastMsg > 3000) {
                lastMsg = System.currentTimeMillis();
                p.sendActionBar(ChatColor.YELLOW + "Connect: " + scanned + "/" + total + " chunks, " + fixed + " fixed");
            }
            if (chunks.isEmpty() && inFlight == 0 && todo.isEmpty()) finish(false);
            else if (chunks.isEmpty() && inFlight == 0) {
                // something is still waiting on an unloaded neighbour chunk: load it
                for (int[] b : todo) for (BlockFace f : SIDES) {
                    int x = (b[0] + f.getModX()) >> 4, z = (b[2] + f.getModZ()) >> 4;
                    if (!w.isChunkLoaded(x, z) && asked.add(Chunk.getChunkKey(x, z))) {
                        CompletableFuture<Chunk> fc = w.getChunkAtAsync(x, z, false);
                        inFlight++;
                        fc.whenComplete((ch, err) -> Bukkit.getScheduler().runTask(PaneConnect.this, () -> {
                            inFlight--;
                            if (ch != null && tickets.add(Chunk.getChunkKey(ch.getX(), ch.getZ()))) ch.addPluginChunkTicket(PaneConnect.this);
                        }));
                    }
                }
            }
        }

        boolean neighboursLoaded(int x, int z) {
            for (BlockFace f : SIDES) if (!w.isChunkLoaded((x + f.getModX()) >> 4, (z + f.getModZ()) >> 4)) return false;
            return true;
        }

        void scan(Chunk ch) {
            long key = Chunk.getChunkKey(ch.getX(), ch.getZ());
            if (tickets.add(key)) ch.addPluginChunkTicket(PaneConnect.this);
            ChunkSnapshot snap = ch.getChunkSnapshot(false, false, false);
            int minY = w.getMinHeight(), maxY = w.getMaxHeight();
            int ox = ch.getX() << 4, oz = ch.getZ() << 4;
            for (int sec = minY >> 4; sec < maxY >> 4; sec++) {
                if (snap.isSectionEmpty(sec - (minY >> 4))) continue;
                for (int y = sec << 4; y < (sec << 4) + 16; y++)
                    for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
                        int wx = ox + x, wz = oz + z;
                        if ((long) (wx - bx) * (wx - bx) + (long) (wz - bz) * (wz - bz) > (long) r * r) continue;
                        if (target(snap.getBlockType(x, y, z))) { todo.add(new int[]{wx, y, wz}); found++; }
                    }
            }
        }

        void fix(Block b) {
            Material m = b.getType();
            if (!target(m) || !(b.getBlockData() instanceof MultipleFacing mf)) return;
            boolean changed = false;
            for (BlockFace f : SIDES) {
                if (!mf.getAllowedFaces().contains(f)) continue;
                boolean want = connects(m, f, b.getRelative(f));
                if (mf.hasFace(f) != want) { mf.setFace(f, want); changed = true; }
            }
            if (changed) { b.setBlockData(mf, false); fixed++; }
        }

        void finish(boolean cancelled) {
            if (task != null) task.cancel();
            for (long k : tickets) {
                int x = (int) k, z = (int) (k >> 32);
                if (w.isChunkLoaded(x, z)) w.getChunkAt(x, z).removePluginChunkTicket(PaneConnect.this);
            }
            job = null;
            p.sendMessage((cancelled ? ChatColor.RED + "Stopped. " : ChatColor.GREEN + "Done! ")
                    + ChatColor.WHITE + "Checked " + found + " panes/bars/fences, fixed " + fixed + ".");
        }
    }

    @Override public void onDisable() { if (job != null) job.finish(true); }
}
