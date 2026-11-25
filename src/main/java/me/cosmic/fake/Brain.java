package me.cosmic.fake;

import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.block.data.type.RespawnAnchor;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.CrossbowMeta;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.potion.PotionType;
import org.bukkit.util.Vector;

import java.util.*;
import java.util.function.Predicate;

/** The PvP brain: targeting, melee with crits, shields, bows/crossbows, mace + wind charges, crystal and anchor PvP,
 *  auto armor/weapon/totem/shield/eat/potion/mend, retreat, cobwebs, bhop, idle wandering, path walking. */
final class Brain {
    final CosmicBots pl; final Store st; final Random rnd = new Random();
    Brain(CosmicBots pl) { this.pl = pl; this.st = pl.store; }

    // =================================================================== main tick
    void tick(Bot b, long now) {
        Player p = b.p;
        b.forward = 0; b.strafe = 0; b.jump = false;
        int every = Math.max(1, st.i("interval"));
        if (now % 10 == 0) upkeep(b, now);
        LivingEntity t = st.b("combat") ? target(b, now) : null;
        if (b.eatTicks > 0) { b.eatTicks--; if (!Nms.isUsing(b.nms)) b.eatTicks = 0; if (t == null || dist(p, t) > 5) return; }
        if (t != null) { if (now % every == 0 || true) fight(b, t, now); return; }
        if (b.blocking) { Nms.stopUsing(b.nms); b.blocking = false; }
        if (b.path != null) { walkPath(b, now); return; }
        if (st.b("idle")) idle(b, now);
    }

    // =================================================================== targeting
    LivingEntity target(Bot b, long now) {
        Player p = b.p; double vd = st.d("view-distance");
        if (b.forced != null) { Entity e = Bukkit.getEntity(b.forced); if (e instanceof LivingEntity le && valid(b, le, vd * 2, true)) return le; b.forced = null; }
        if (b.revenge != null && st.b("revenge") && now - b.revengeAt < 300) { Entity e = Bukkit.getEntity(b.revenge); if (e instanceof LivingEntity le && valid(b, le, vd, true)) return le; }
        LivingEntity best = null; double bd = 1e9;
        for (Entity e : p.getNearbyEntities(vd, vd, vd)) {
            if (!(e instanceof LivingEntity le) || !valid(b, le, vd, false)) continue;
            boolean want = false;
            if (le instanceof Player o && st.hostile(b.name, o.getName())) want = true;
            else if (st.b("auto-target")) {
                if (le instanceof Player o) want = pl.isBot(o) ? st.b("target-bots") : st.b("target-players");
                else want = st.b("target-mobs") && le instanceof Enemy;
            }
            if (!want) continue;
            double d = dist(p, le); if (d < bd) { bd = d; best = le; }
        }
        return best;
    }
    boolean valid(Bot b, LivingEntity e, double range, boolean ordered) {
        if (e.equals(b.p) || e.isDead() || !e.isValid() || e instanceof ArmorStand || !e.getWorld().equals(b.p.getWorld())) return false;
        if (dist(b.p, e) > range) return false;
        if (e instanceof Player o) {
            if (!st.b("attack-invincible") && (o.getGameMode() == GameMode.CREATIVE || o.getGameMode() == GameMode.SPECTATOR)) return false;
            if (!ordered && st.sameFaction(b.name, o.getName()) && !st.b("friendly-fire")) return false;
        }
        return true;
    }

    // =================================================================== fighting
    void fight(Bot b, LivingEntity t, long now) {
        Player p = b.p; double d = dist(p, t);
        double hp = p.getHealth(), max = maxHp(p);
        // retreat when low
        if (st.b("retreat") && hp < max * 0.3 && d < 10) {
            if (st.b("cobwebs") && d < 3.5) cobweb(b, t, now);
            Vector away = p.getLocation().toVector().subtract(t.getLocation().toVector()).setY(0);
            if (away.lengthSquared() < 0.01) away = new Vector(1, 0, 0);
            face(b, p.getLocation().clone().add(away.normalize().multiply(5)).add(0, 1.6, 0), st.d("aim-speed") * 2);
            run(b, true, now);
            if (st.b("auto-shield") && hasOff(p, Material.SHIELD) && d < 4) shield(b, now, 20);
            return;
        }
        if (st.b("cobwebs") && d < 2.5 && now - b.lastCobweb > 200) cobweb(b, t, now);
        // pick a fighting style
        PlayerInventory inv = p.getInventory();
        boolean canCrystal = st.b("crystal-pvp") && has(inv, Material.END_CRYSTAL) && has(inv, Material.OBSIDIAN) && d > 2.5 && d < 6;
        boolean canAnchor = st.b("anchor-pvp") && has(inv, Material.RESPAWN_ANCHOR) && has(inv, Material.GLOWSTONE) && p.getWorld().getEnvironment() != World.Environment.NETHER && d > 2.5 && d < 6;
        boolean canRanged = st.b("ranged") && (has(inv, Material.BOW) || has(inv, Material.CROSSBOW)) && hasArrows(inv) && d > st.d("ranged-min-range") && d < st.d("ranged-max-range");
        boolean canMace = st.b("mace") && has(inv, Material.MACE) && has(inv, Material.WIND_CHARGE) && d < 7;
        if (canCrystal && now - b.lastCrystal > 12) { crystal(b, t, now); return; }
        if (canAnchor && now - b.lastAnchor > 25) { anchor(b, t, now); return; }
        if (canRanged && !(d < st.d("melee-range") + 1)) { ranged(b, t, d, now); return; }
        if (canMace) { mace(b, t, d, now); return; }
        melee(b, t, d, now);
    }

    void melee(Bot b, LivingEntity t, double d, long now) {
        Player p = b.p; double range = st.d("melee-range");
        if (b.drawTicks > 0) { Nms.stopUsing(b.nms); b.drawTicks = 0; }
        boolean breakShield = st.b("shield-break") && t instanceof Player o && o.isBlocking() && rnd.nextInt(100) < st.i("shield-break-chance");
        if (st.b("auto-weapon")) hold(p, breakShield ? axe() : weapon());
        aim(b, t, now);
        // shields: block an incoming swing, a falling mace, or when low
        if (hasOff(p, Material.SHIELD)) {
            boolean danger = t instanceof Player o && d < range + 0.5 && o.getAttackCooldown() > 0.9f && rnd.nextInt(5) == 0;
            boolean maceDrop = st.b("shield-mace") && t instanceof Player o2 && o2.getInventory().getItemInMainHand().getType() == Material.MACE && o2.getVelocity().getY() < -0.3 && t.getLocation().getY() > p.getLocation().getY() + 1.5;
            boolean low = st.b("auto-shield") && p.getHealth() < maxHp(p) * 0.4;
            if ((danger || maceDrop || low) && !b.blocking) shield(b, now, st.i("shield-hold-ticks"));
        }
        if (b.blocking) {
            if (now >= b.shieldUntil || (p.getAttackCooldown() >= 1f && !(t instanceof Player o && o.getAttackCooldown() > 0.9f))) { Nms.stopUsing(b.nms); b.blocking = false; }
            else { approach(b, t, d, range, now); return; }
        }
        approach(b, t, d, range, now);
        if (d > range) return;
        boolean ready = p.getAttackCooldown() >= 0.95f && now - b.lastAttack >= st.i("attack-cooldown");
        if (!ready) return;
        if (st.b("criticals") && !breakShield) {
            if (b.critWait < 0 && Nms.onGround(b.nms) && !p.isInWater()) { Nms.jumpNow(b.nms); b.critWait = st.i("crit-fall-ticks") + 5; return; }
            if (b.critWait >= 0) { b.critWait--; boolean falling = !Nms.onGround(b.nms) && p.getVelocity().getY() < 0; if (!falling && b.critWait > 0) return; }
        }
        hit(b, t, now);
        b.critWait = -1;
    }

    void approach(Bot b, LivingEntity t, double d, double range, long now) {
        if (d > range - 0.6) { b.forward = 1; run(b, d > 6, now); }
        else if (d < 1.2) b.forward = -0.5f;
        if (d < range + 1 && rnd.nextInt(40) == 0) b.strafeDir = -b.strafeDir;
        if (d < range + 2) b.strafe = 0.35f * b.strafeDir;
    }

    void hit(Bot b, LivingEntity t, long now) {
        Player p = b.p; b.lastAttack = now;
        if (rnd.nextInt(100) < st.i("miss-chance")) { p.swingMainHand(); return; }
        p.attack(t); p.swingMainHand();
    }

    void shield(Bot b, long now, int ticks) {
        if (b.blocking) return;
        Nms.startUsing(b.nms, true); b.blocking = true; b.shieldUntil = now + Math.max(ticks, st.i("shield-raise-ticks"));
    }

    // ------------------------------------------------------------------- ranged
    void ranged(Bot b, LivingEntity t, double d, long now) {
        Player p = b.p; PlayerInventory inv = p.getInventory();
        boolean bow = has(inv, Material.BOW);
        hold(p, s -> s.getType() == (bow ? Material.BOW : Material.CROSSBOW));
        // keep the best distance, strafe
        double opt = st.d("ranged-optimal-range");
        if (d < opt - 3 && st.b("ranged-retreat")) b.forward = -1; else if (d > opt + 4) { b.forward = 1; run(b, d > opt + 10, now); }
        if (st.b("ranged-strafe")) { if (now > b.strafeSwitch) { b.strafeDir = -b.strafeDir; b.strafeSwitch = now + 25 + rnd.nextInt(40); } b.strafe = 0.8f * b.strafeDir; }
        Location aimAt = lead(p, t, d);
        face(b, aimAt, st.d("aim-speed"));
        if (bow) {
            if (!Nms.isUsing(b.nms)) { Nms.startUsing(b.nms, false); b.drawTicks = 0; }
            if (++b.drawTicks >= st.i("bow-draw-ticks")) { face(b, aimAt, 180); Nms.releaseUsing(b.nms); b.drawTicks = 0; b.lastAttack = now; }
        } else {
            ItemStack cb = inv.getItemInMainHand();
            boolean loaded = cb.getItemMeta() instanceof CrossbowMeta cm && cm.hasChargedProjectiles();
            if (loaded) { face(b, aimAt, 180); try { Nms.invoke(Nms.getField(b.nms, "gameMode"), "useItem", b.nms, Nms.invoke(b.nms, "level"), Nms.invoke(b.nms, "getItemInHand", Nms.hand(false)), Nms.hand(false)); } catch (Throwable ignored) {} b.lastAttack = now; }
            else { if (!Nms.isUsing(b.nms)) { Nms.startUsing(b.nms, false); b.drawTicks = 0; } if (++b.drawTicks >= 27) { Nms.releaseUsing(b.nms); b.drawTicks = 0; } }
        }
    }
    /** where to aim: the target's chest, moved forward by its speed and raised for the arrow drop */
    Location lead(Player p, LivingEntity t, double d) {
        Location l = t.getLocation().clone().add(0, t.getHeight() * 0.6, 0);
        if (st.b("arrow-prediction")) {
            double ticks = d / 2.6;
            Vector v = t.getVelocity().clone(); v.setY(Math.max(v.getY(), -0.1));
            l.add(v.multiply(ticks));
            l.add(0, 0.012 * ticks * ticks + d * 0.012, 0);
        }
        return l;
    }

    // ------------------------------------------------------------------- mace + wind charge
    void mace(Bot b, LivingEntity t, double d, long now) {
        Player p = b.p;
        if (b.macePhase == 0) {
            approach(b, t, d, 2.5, now);
            if (Nms.onGround(b.nms) && d < 5 && now - b.lastWind > 12) {
                hold(p, s -> s.getType() == Material.WIND_CHARGE);
                p.setRotation(p.getLocation().getYaw(), 90);
                try { Nms.invoke(Nms.getField(b.nms, "gameMode"), "useItem", b.nms, Nms.invoke(b.nms, "level"), Nms.invoke(b.nms, "getItemInHand", Nms.hand(false)), Nms.hand(false)); } catch (Throwable ignored) {}
                Nms.jumpNow(b.nms);
                b.lastWind = now; b.macePhase = 1;
            } else { hold(p, weapon()); aim(b, t, now); if (d < st.d("melee-range") && p.getAttackCooldown() >= 0.95f) hit(b, t, now); }
            return;
        }
        hold(p, s -> s.getType() == Material.MACE);
        aim(b, t, now);
        Vector to = t.getLocation().toVector().subtract(p.getLocation().toVector()).setY(0);
        if (to.lengthSquared() > 1) b.forward = 1;
        boolean falling = p.getVelocity().getY() < 0 && !Nms.onGround(b.nms);
        if (falling && d < st.d("melee-range") + 0.8) { hit(b, t, now); b.macePhase = 0; }
        if (Nms.onGround(b.nms) && now - b.lastWind > 30) b.macePhase = 0;
    }

    // ------------------------------------------------------------------- crystal + anchor PvP
    void crystal(Bot b, LivingEntity t, long now) {
        Player p = b.p; World w = p.getWorld();
        aim(b, t, now);
        for (Entity e : t.getNearbyEntities(4, 3, 4)) if (e instanceof EnderCrystal c && dist(p, c) < 5) { face(b, c.getLocation(), 180); p.attack(c); p.swingMainHand(); b.lastCrystal = now; return; }
        Block base = spot(p, t); if (base == null) { melee(b, t, dist(p, t), now); return; }
        if (base.getType() != Material.OBSIDIAN && base.getType() != Material.BEDROCK) { base.setType(Material.OBSIDIAN); take(p.getInventory(), Material.OBSIDIAN); }
        w.spawn(base.getLocation().add(0.5, 1, 0.5), EnderCrystal.class, c -> c.setShowingBottom(false));
        take(p.getInventory(), Material.END_CRYSTAL);
        p.swingMainHand(); b.lastCrystal = now - 8;
    }
    void anchor(Bot b, LivingEntity t, long now) {
        Player p = b.p; aim(b, t, now);
        Block base = spot(p, t); if (base == null) { melee(b, t, dist(p, t), now); return; }
        Block a = base.getRelative(0, 1, 0);
        a.setType(Material.RESPAWN_ANCHOR);
        if (a.getBlockData() instanceof RespawnAnchor ra) { ra.setCharges(1); a.setBlockData(ra); }
        take(p.getInventory(), Material.RESPAWN_ANCHOR); take(p.getInventory(), Material.GLOWSTONE);
        p.swingMainHand();
        Location c = a.getLocation().add(0.5, 0.5, 0.5);
        Bukkit.getScheduler().runTaskLater(pl, () -> { if (a.getType() == Material.RESPAWN_ANCHOR) { a.setType(Material.AIR); c.getWorld().createExplosion(c, 5f, true, true, p); } }, 3L);
        b.lastAnchor = now;
    }
    /** a solid block next to the target (on its side towards the bot) with 2 air blocks above it */
    Block spot(Player p, LivingEntity t) {
        Location tl = t.getLocation();
        Vector dir = p.getLocation().toVector().subtract(tl.toVector()).setY(0);
        if (dir.lengthSquared() < 0.01) dir = new Vector(1, 0, 0);
        dir.normalize();
        int[][] tries = {{(int) Math.round(dir.getX()), (int) Math.round(dir.getZ())}, {1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {-1, -1}};
        for (int[] o : tries) {
            Block g = tl.getBlock().getRelative(o[0], -1, o[1]);
            if ((g.getType().isSolid() || g.getType() == Material.OBSIDIAN) && g.getRelative(0, 1, 0).isPassable() && g.getRelative(0, 2, 0).isPassable() && g.getRelative(0, 1, 0).getType() != Material.WATER) return g;
        }
        return null;
    }
    void cobweb(Bot b, LivingEntity t, long now) {
        if (now - b.lastCobweb < 200 || !has(b.p.getInventory(), Material.COBWEB)) return;
        Block f = t.getLocation().getBlock();
        if (f.getType().isAir()) { f.setType(Material.COBWEB); take(b.p.getInventory(), Material.COBWEB); b.p.swingMainHand(); b.lastCobweb = now; }
    }

    // =================================================================== upkeep (every 10 ticks)
    void upkeep(Bot b, long now) {
        Player p = b.p; PlayerInventory inv = p.getInventory();
        if (st.b("auto-armor")) armor(p);
        if (st.b("auto-totem") && has(inv, Material.TOTEM_OF_UNDYING) && inv.getItemInOffHand().getType() != Material.TOTEM_OF_UNDYING
                && (st.b("totem-priority") || inv.getItemInOffHand().getType().isAir() || p.getHealth() < maxHp(p) * 0.5)) toOff(p, Material.TOTEM_OF_UNDYING);
        else if (st.b("auto-shield") && inv.getItemInOffHand().getType().isAir() && has(inv, Material.SHIELD)) toOff(p, Material.SHIELD);
        if (st.b("drop-weapon")) dropWorse(p, s -> s.getType().name().endsWith("_SWORD"));
        if (st.b("drop-weapon")) dropWorse(p, s -> s.getType().name().endsWith("_AXE"));
        boolean calm = nearestEnemy(b) > 6;
        // eat
        if (st.b("auto-eat") && b.eatTicks == 0 && calm && (p.getFoodLevel() < 17 || (p.getHealth() < maxHp(p) * 0.6 && p.getFoodLevel() < 20))) {
            int slot = bestFood(inv, p.getHealth() < maxHp(p) * 0.5);
            if (slot >= 0) { toHand(p, slot); Nms.startUsing(b.nms, false); b.eatTicks = 40; }
        }
        // potions
        if (st.b("auto-potion") && now - b.lastPotion > 30) potions(b, now);
        // mend armor with xp bottles
        if (st.b("auto-mend") && calm && now - b.lastMend > 5 && damagedMending(p) && has(inv, Material.EXPERIENCE_BOTTLE)) {
            p.setRotation(p.getLocation().getYaw(), 90);
            p.launchProjectile(ThrownExpBottle.class); take(inv, Material.EXPERIENCE_BOTTLE); b.lastMend = now;
        }
    }
    double nearestEnemy(Bot b) { double m = 1e9; for (Entity e : b.p.getNearbyEntities(8, 4, 8)) if (e instanceof Player || e instanceof Enemy) m = Math.min(m, dist(b.p, e)); return m; }

    void potions(Bot b, long now) {
        Player p = b.p; PlayerInventory inv = p.getInventory();
        boolean low = p.getHealth() < maxHp(p) * 0.5, fight = nearestEnemy(b) < 12, burning = p.getFireTicks() > 0;
        String want = low ? "HEAL" : burning && !p.hasPotionEffect(PotionEffectType.FIRE_RESISTANCE) ? "FIRE" : fight && !p.hasPotionEffect(PotionEffectType.STRENGTH) ? "STRENGTH" : fight && !p.hasPotionEffect(PotionEffectType.SPEED) ? "SWIFT" : null;
        if (want == null) return;
        for (int i = 0; i < 36; i++) {
            ItemStack s = inv.getItem(i); if (s == null || !(s.getItemMeta() instanceof PotionMeta pm)) continue;
            PotionType ty = pm.getBasePotionType(); String n = ty == null ? "" : ty.name();
            if (!n.contains(want) && !(want.equals("HEAL") && n.contains("REGEN"))) continue;
            if (s.getType() == Material.SPLASH_POTION || s.getType() == Material.LINGERING_POTION) {
                p.setRotation(p.getLocation().getYaw(), 90);
                ThrownPotion tp = p.launchProjectile(ThrownPotion.class); ItemStack one = s.clone(); one.setAmount(1); tp.setItem(one);
                s.setAmount(s.getAmount() - 1); inv.setItem(i, s.getAmount() > 0 ? s : null);
            } else if (s.getType() == Material.POTION) { toHand(p, i); Nms.startUsing(b.nms, false); b.eatTicks = 36; }
            b.lastPotion = now; return;
        }
    }

    // =================================================================== paths + idle
    void walkPath(Bot b, long now) {
        Store.PathDef pd = st.paths.get(b.path.toLowerCase(Locale.ROOT));
        if (pd == null || pd.points.isEmpty()) { b.path = null; return; }
        if (b.pathIdx < 0 || b.pathIdx >= pd.points.size()) b.pathIdx = 0;
        Location goal = pd.points.get(b.pathIdx);
        if (!goal.getWorld().equals(b.p.getWorld())) { b.p.teleport(goal); return; }
        if (flat(b.p.getLocation(), goal) < 1.3) {
            int n = pd.points.size();
            switch (pd.loop) {
                case "circle" -> b.pathIdx = (b.pathIdx + 1) % n;
                case "backforth" -> { if (b.pathIdx + b.pathDir < 0 || b.pathIdx + b.pathDir >= n) b.pathDir = -b.pathDir; b.pathIdx += b.pathDir; }
                default -> { if (b.pathIdx + 1 >= n) { b.path = null; return; } b.pathIdx++; }
            }
            return;
        }
        face(b, goal.clone().add(0, 1.6, 0), st.d("aim-speed") * 2);
        b.forward = 1;
        b.p.setSprinting(!pd.walk.equals("walk"));
        if (pd.walk.equals("bhop") && Nms.onGround(b.nms)) b.jump = true;
        unstick(b);
    }
    void idle(Bot b, long now) {
        if (b.idleGoal == null || now > b.nextIdle || flat(b.p.getLocation(), b.idleGoal) < 1.2) {
            if (b.idleGoal != null && flat(b.p.getLocation(), b.idleGoal) < 1.2) { b.idleGoal = null; b.nextIdle = now + 40 + rnd.nextInt(100); }
            if (now < b.nextIdle) return;
            double r = st.d("idle-radius"), a = rnd.nextDouble() * Math.PI * 2, rr = rnd.nextDouble() * r;
            b.idleGoal = b.home.clone().add(Math.cos(a) * rr, 0, Math.sin(a) * rr); b.nextIdle = now + 200;
        }
        face(b, b.idleGoal.clone().add(0, 1.6, 0), st.d("aim-speed"));
        b.forward = 0.6f; b.p.setSprinting(false); unstick(b);
    }

    // =================================================================== movement helpers
    void run(Bot b, boolean far, long now) {
        b.p.setSprinting(true);
        if (st.b("bhop") && far && Nms.onGround(b.nms)) b.jump = true;
        unstick(b);
    }
    void unstick(Bot b) {
        Player p = b.p; Location l = p.getLocation();
        Vector f = l.getDirection().setY(0); if (f.lengthSquared() < 1e-4) return; f.normalize();
        Block ahead = l.clone().add(f.clone().multiply(0.8)).getBlock();
        if (ahead.getType().isSolid() && ahead.getRelative(0, 1, 0).isPassable() && ahead.getRelative(0, 2, 0).isPassable()) b.jump = true;
        if (b.lastPos != null && b.lastPos.getWorld().equals(l.getWorld()) && b.lastPos.distanceSquared(l) < 0.002 && b.forward != 0) { if (++b.stuck > 8) { b.jump = true; b.stuck = 0; b.strafe = rnd.nextBoolean() ? 1 : -1; } } else b.stuck = 0;
        b.lastPos = l.clone();
    }
    void aim(Bot b, LivingEntity t, long now) {
        Location l = t.getLocation().clone().add(0, t.getHeight() * 0.75, 0);
        if (rnd.nextInt(100) < st.i("mistake-chance")) l.add(rnd.nextGaussian() * 1.2, rnd.nextGaussian() * 0.4, rnd.nextGaussian() * 1.2);
        face(b, l, st.d("aim-speed"));
    }
    /** turn towards a point, at most maxStep degrees per tick */
    void face(Bot b, Location to, double maxStep) {
        Location e = b.p.getEyeLocation();
        double dx = to.getX() - e.getX(), dy = to.getY() - e.getY(), dz = to.getZ() - e.getZ();
        float wantYaw = (float) Math.toDegrees(Math.atan2(-dx, dz)), wantPitch = (float) -Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz)));
        Location cur = b.p.getLocation();
        float dyaw = wrap(wantYaw - cur.getYaw()), dp = wantPitch - cur.getPitch();
        float step = (float) maxStep;
        float yaw = cur.getYaw() + Math.max(-step, Math.min(step, dyaw)), pitch = cur.getPitch() + Math.max(-step, Math.min(step, dp));
        b.p.setRotation(yaw, Math.max(-90, Math.min(90, pitch)));
    }
    static float wrap(float a) { a %= 360; if (a > 180) a -= 360; if (a < -180) a += 360; return a; }
    static double dist(Entity a, Entity b) { return a.getLocation().distance(b.getLocation()); }
    static double flat(Location a, Location b) { return Math.hypot(a.getX() - b.getX(), a.getZ() - b.getZ()); }
    static double maxHp(Player p) { AttributeInstance ai = p.getAttribute(Attribute.MAX_HEALTH); return ai == null ? 20 : ai.getValue(); }

    // =================================================================== inventory helpers
    static boolean has(PlayerInventory inv, Material m) { if (inv.getItemInOffHand().getType() == m) return true; for (ItemStack s : inv.getStorageContents()) if (s != null && s.getType() == m) return true; return false; }
    static boolean hasOff(Player p, Material m) { return p.getInventory().getItemInOffHand().getType() == m; }
    static boolean hasArrows(PlayerInventory inv) { for (ItemStack s : inv.getStorageContents()) if (s != null && (s.getType() == Material.ARROW || s.getType() == Material.SPECTRAL_ARROW || s.getType() == Material.TIPPED_ARROW)) return true; return inv.getItemInOffHand().getType().name().endsWith("ARROW"); }
    static void take(PlayerInventory inv, Material m) {
        for (int i = 0; i < 36; i++) { ItemStack s = inv.getItem(i); if (s != null && s.getType() == m) { s.setAmount(s.getAmount() - 1); inv.setItem(i, s.getAmount() > 0 ? s : null); return; } }
        ItemStack o = inv.getItemInOffHand(); if (o.getType() == m) { o.setAmount(o.getAmount() - 1); inv.setItemInOffHand(o.getAmount() > 0 ? o : null); }
    }
    static int tier(Material m) {
        String n = m.name();
        if (n.startsWith("NETHERITE")) return 6; if (n.startsWith("DIAMOND")) return 5; if (n.startsWith("IRON")) return 4;
        if (n.startsWith("CHAINMAIL") || n.startsWith("TURTLE") || n.startsWith("STONE")) return 3; if (n.startsWith("GOLDEN") || n.startsWith("COPPER")) return 2; if (n.startsWith("LEATHER") || n.startsWith("WOODEN")) return 1;
        return 0;
    }
    static double score(ItemStack s) {
        if (s == null || s.getType().isAir()) return -1;
        double v = tier(s.getType()) * 10;
        for (Map.Entry<Enchantment, Integer> e : s.getEnchantments().entrySet()) v += e.getValue();
        return v;
    }
    Predicate<ItemStack> weapon() {
        boolean sword = st.b("prefer-sword");
        return s -> { String n = s.getType().name(); return n.endsWith("_SWORD") || n.endsWith("_AXE") || (!sword && n.equals("MACE")); };
    }
    static Predicate<ItemStack> axe() { return s -> s.getType().name().endsWith("_AXE"); }
    /** put the best matching item into the main hand (hotbar swap if needed) */
    void hold(Player p, Predicate<ItemStack> want) {
        PlayerInventory inv = p.getInventory();
        int best = -1; double bs = -1; boolean sword = st.b("prefer-sword");
        for (int i = 0; i < 36; i++) {
            ItemStack s = inv.getItem(i); if (s == null || s.getType().isAir() || !want.test(s)) continue;
            double sc = score(s) + (sword && s.getType().name().endsWith("_SWORD") ? 5 : 0);
            if (sc > bs) { bs = sc; best = i; }
        }
        if (best >= 0 && best != inv.getHeldItemSlot()) toHand(p, best);
    }
    static void toHand(Player p, int slot) {
        PlayerInventory inv = p.getInventory();
        if (slot < 9) { inv.setHeldItemSlot(slot); return; }
        int h = inv.getHeldItemSlot(); ItemStack a = inv.getItem(h), s = inv.getItem(slot);
        inv.setItem(h, s); inv.setItem(slot, a);
    }
    static void toOff(Player p, Material m) {
        PlayerInventory inv = p.getInventory();
        for (int i = 0; i < 36; i++) { ItemStack s = inv.getItem(i); if (s != null && s.getType() == m) { ItemStack o = inv.getItemInOffHand(); inv.setItemInOffHand(s); inv.setItem(i, o.getType().isAir() ? null : o); return; } }
    }
    void armor(Player p) {
        PlayerInventory inv = p.getInventory();
        String[] suf = {"_HELMET", "_CHESTPLATE", "_LEGGINGS", "_BOOTS"};
        ItemStack[] cur = {inv.getHelmet(), inv.getChestplate(), inv.getLeggings(), inv.getBoots()};
        for (int k = 0; k < 4; k++) {
            final String sfx = suf[k];
            int best = -1; double bs = score(cur[k]);
            for (int i = 0; i < 36; i++) { ItemStack s = inv.getItem(i); if (s == null || !s.getType().name().endsWith(sfx) && !(k == 0 && s.getType() == Material.TURTLE_HELMET)) continue; double sc = score(s); if (sc > bs) { bs = sc; best = i; } }
            if (best < 0) continue;
            ItemStack nw = inv.getItem(best), old = cur[k];
            switch (k) { case 0 -> inv.setHelmet(nw); case 1 -> inv.setChestplate(nw); case 2 -> inv.setLeggings(nw); default -> inv.setBoots(nw); }
            inv.setItem(best, null);
            if (old != null && !old.getType().isAir()) {
                if (st.b("drop-armor")) drop(p, old); else inv.setItem(best, old);
            }
        }
    }
    void dropWorse(Player p, Predicate<ItemStack> kind) {
        PlayerInventory inv = p.getInventory(); int best = -1; double bs = -1;
        for (int i = 0; i < 36; i++) { ItemStack s = inv.getItem(i); if (s != null && kind.test(s) && score(s) > bs) { bs = score(s); best = i; } }
        for (int i = 0; i < 36; i++) { ItemStack s = inv.getItem(i); if (i != best && s != null && kind.test(s)) { drop(p, s); inv.setItem(i, null); } }
    }
    void drop(Player p, ItemStack s) {
        Location l = p.getLocation().add(p.getLocation().getDirection().setY(0).normalize().multiply(st.d("drop-distance")));
        p.getWorld().dropItemNaturally(l, s);
    }
    static int bestFood(PlayerInventory inv, boolean low) {
        int best = -1; int bs = -1;
        for (int i = 0; i < 36; i++) {
            ItemStack s = inv.getItem(i); if (s == null || !s.getType().isEdible()) continue;
            String n = s.getType().name(); if (n.contains("ROTTEN") || n.contains("SPIDER") || n.contains("PUFFER") || n.contains("CHORUS")) continue;
            int sc = n.contains("GOLDEN_APPLE") ? (low ? 100 : 5) : n.contains("STEAK") || n.contains("PORKCHOP") || n.contains("GOLDEN_CARROT") ? 30 : 10;
            if (sc > bs) { bs = sc; best = i; }
        }
        return best;
    }
    static boolean damagedMending(Player p) {
        for (ItemStack s : p.getInventory().getArmorContents()) {
            if (s == null) continue; ItemMeta m = s.getItemMeta();
            if (m instanceof Damageable d && d.getDamage() > 0 && s.containsEnchantment(Enchantment.MENDING)) return true;
        }
        return false;
    }
}
