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

    // =================================================================== skill
    /** 0.01 - 1.0 : how good this bot is (per bot, else the global 'skill' setting) */
    double skill(Bot b) { int s = b.skill > 0 ? b.skill : st.i("skill"); return Math.max(1, Math.min(100, s)) / 100.0; }
    boolean roll(double pct) { return rnd.nextDouble() * 100 < pct; }
    /** reaction delay in ticks: 0 for HT1, ~8 for a beginner */
    int react(Bot b) { return (int) Math.round((1 - skill(b)) * 8); }
    double aimStep(Bot b) { return st.d("aim-speed") * (0.3 + 0.9 * skill(b)); }

    // =================================================================== main tick
    void tick(Bot b, long now) {
        Player p = b.p;
        b.forward = 0; b.strafe = 0; b.jump = false;
        if (now % 10 == 0) upkeep(b, now);
        retotem(b, now);
        if (webEscape(b, now)) return;
        LivingEntity t = st.b("combat") ? target(b, now) : null;
        if (b.eatTicks > 0) {
            b.eatTicks--;
            if (!Nms.isUsing(b.nms)) b.eatTicks = 0;
            else {
                // keep eating, but back off and strafe like a real player
                if (t != null && dist(p, t) < 8) { aim(b, t, now); b.forward = -1; b.strafe = 0.7f * b.strafeDir; if (rnd.nextInt(25) == 0) b.strafeDir = -b.strafeDir; }
                return;
            }
        }
        if (t != null) { fight(b, t, now); return; }
        if (b.blocking) { Nms.stopUsing(b.nms); b.blocking = false; b.antiMace = false; }
        if (b.path != null) { walkPath(b, now); return; }
        if (st.b("idle")) idle(b, now);
    }

    // =================================================================== targeting
    LivingEntity target(Bot b, long now) {
        Player p = b.p; double vd = st.d("view-distance");
        if (b.forced != null) { Entity e = Bukkit.getEntity(b.forced); if (e instanceof LivingEntity le && valid(b, le, vd * 4, true)) return le; b.forced = null; }
        // chosen targets (/pvpbot target): hunt the closest one
        if (!b.targets.isEmpty()) {
            LivingEntity best = null; double bd = 1e9;
            for (String n : b.targets) {
                Player o = Bukkit.getPlayerExact(n); if (o == null || !valid(b, o, vd * 4, true)) continue;
                double d = dist(p, o); if (d < bd) { bd = d; best = o; }
            }
            if (best != null) return best;
        }
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
        if (antiMace(b, t, now)) return;
        PlayerInventory inv = p.getInventory();
        // golden apple mid-fight when low (backs off while eating)
        if (st.b("auto-eat") && hp < max * 0.45 && now - b.lastGap > 70 && d > 2.2) {
            int g = slotOf(inv, s -> s.getType() == Material.ENCHANTED_GOLDEN_APPLE || s.getType() == Material.GOLDEN_APPLE);
            if (g >= 0 && !p.hasCooldown(inv.getItem(g).getType())) { if (b.blocking) { Nms.stopUsing(b.nms); b.blocking = false; } toHand(p, g); Nms.startUsing(b.nms, false); b.eatTicks = 34; b.lastGap = now; return; }
        }
        // ender pearl to close the gap
        if (st.b("pearls") && d > 14 && d < 45 && now - b.lastPearl > 80 && skill(b) >= 0.4 && has(inv, Material.ENDER_PEARL) && !p.hasCooldown(Material.ENDER_PEARL)) { pearl(b, t, now); return; }
        // retreat when low
        if (st.b("retreat") && hp < max * 0.3 && d < 10) {
            if (st.b("cobwebs") && d < 3.5) cobweb(b, t, now);
            Vector away = p.getLocation().toVector().subtract(t.getLocation().toVector()).setY(0);
            if (away.lengthSquared() < 0.01) away = new Vector(1, 0, 0);
            face(b, p.getLocation().clone().add(away.normalize().multiply(5)).add(0, 1.6, 0), aimStep(b) * 2);
            run(b, true, now);
            if (st.b("auto-shield") && hasOff(p, Material.SHIELD) && d < 4) shield(b, now, 20);
            return;
        }
        // pick a fighting style
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

    /** someone wind-charged up above us with a mace: look up and block with a shield (offhand, or pulled from the hotbar) */
    boolean antiMace(Bot b, LivingEntity t, long now) {
        Player p = b.p;
        boolean threat = false;
        if (st.b("anti-mace") && t instanceof Player o && !o.isOnGround()) {
            double dy = o.getLocation().getY() - p.getLocation().getY(), fl = flat(o.getLocation(), p.getLocation());
            boolean maceUser = o.getInventory().getItemInMainHand().getType() == Material.MACE || o.getInventory().contains(Material.MACE);
            boolean shieldReady = p.getCooldown(Material.SHIELD) == 0 && (hasOff(p, Material.SHIELD) || has(p.getInventory(), Material.SHIELD));
            threat = maceUser && shieldReady && dy > 2.2 && fl < 7 + dy * 0.3;
        }
        if (!threat) {
            if (b.antiMace) { Nms.stopUsing(b.nms); b.blocking = false; b.antiMace = false; }
            b.maceSeen = 0;
            return false;
        }
        if (b.maceSeen == 0) b.maceSeen = now;
        face(b, t.getLocation().clone().add(0, 0.9, 0), Math.max(aimStep(b) * 1.5, 25));
        if (now - b.maceSeen < react(b)) return false;
        if (!b.antiMace) {
            if (b.blocking) { Nms.stopUsing(b.nms); b.blocking = false; }
            if (hasOff(p, Material.SHIELD)) Nms.startUsing(b.nms, true);
            else { toHand(p, slotOf(p.getInventory(), s -> s.getType() == Material.SHIELD)); Nms.startUsing(b.nms, false); }
            b.blocking = true; b.antiMace = true; b.shieldUntil = now + 80;
        }
        p.setSprinting(false);
        b.forward = 0; b.strafe = 0.4f * b.strafeDir;
        return true;
    }

    void melee(Bot b, LivingEntity t, double d, long now) {
        Player p = b.p; double range = st.d("melee-range"), sk = skill(b);
        if (b.drawTicks > 0) { Nms.stopUsing(b.nms); b.drawTicks = 0; }
        // stun: the tick after the axe broke their shield, swap to the sword/mace and hit again
        if (b.stunAt > 0 && now >= b.stunAt) {
            b.stunAt = -1;
            hold(p, has(p.getInventory(), Material.MACE) && !st.b("prefer-sword") ? (s -> s.getType() == Material.MACE) : swordFirst());
            face(b, t.getLocation().clone().add(0, t.getHeight() * 0.75, 0), 180);
            if (d <= range + 0.4) { b.lastAttack = now; p.attack(t); p.swingMainHand(); }
            if (st.b("stun-web") && roll(sk * 100)) webUnder(b, t, now);
            return;
        }
        boolean blockingTarget = t instanceof Player o && o.isBlocking();
        boolean breakShield = st.b("shield-break") && blockingTarget && has(p.getInventory(), a -> a.getType().name().endsWith("_AXE"))
                && roll(st.i("shield-break-chance") * (0.4 + 0.6 * sk));
        if (st.b("auto-weapon")) hold(p, breakShield ? axe() : weapon());
        aim(b, t, now);
        // shields: block an incoming swing, a falling mace, or when low
        if (hasOff(p, Material.SHIELD) && p.getCooldown(Material.SHIELD) == 0) {
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
        // s-tap: tap S for a couple of ticks after a sprint hit, then sprint back in
        if (b.tapTicks > 0) { b.tapTicks--; b.forward = -1; p.setSprinting(false); }
        // stun web: drop a web under them when they're close
        if (st.b("stun-web") && d < 3.2 && now - b.lastCobweb > 160 - 80 * sk && roll(3 + 5 * sk)) webUnder(b, t, now);
        if (d > range) { b.critWait = -1; return; }
        double need = 0.55 + 0.4 * sk; // weak bots click early, good bots wait for full cooldown
        boolean ready = p.getAttackCooldown() >= need && now - b.lastAttack >= st.i("attack-cooldown");
        if (!ready) return;
        // crit spam: jump, hit on the way down
        if (b.critWait < 0) b.critPlan = st.b("criticals") && !breakShield && (st.b("crit-spam") ? roll(25 + 75 * sk) : roll(30 * sk)) && !p.isInWater();
        if (b.critPlan) {
            if (b.critWait < 0) { if (Nms.onGround(b.nms)) { Nms.jumpNow(b.nms); b.critWait = st.i("crit-fall-ticks") + 6; } return; }
            b.critWait--;
            boolean falling = !Nms.onGround(b.nms) && p.getVelocity().getY() < 0;
            if (!falling && b.critWait > 0) return;
            p.setSprinting(false); // sprinting cancels crits
        }
        boolean sprintHit = p.isSprinting();
        hit(b, t, now);
        b.critWait = -1;
        if (breakShield && st.b("stun") && roll(30 + 70 * sk)) b.stunAt = now + 1;
        if (sprintHit && st.b("s-tap") && roll(20 + 80 * sk)) b.tapTicks = 2 + rnd.nextInt(2);
    }

    void approach(Bot b, LivingEntity t, double d, double range, long now) {
        if (d > range - 0.6) { b.forward = 1; run(b, d > 6, now); }
        else if (d < 1.2) b.forward = -0.5f;
        if (d < range + 1 && rnd.nextInt(Math.max(8, (int) (50 - 30 * skill(b)))) == 0) b.strafeDir = -b.strafeDir;
        if (d < range + 2) b.strafe = (float) (0.2 + 0.4 * skill(b)) * b.strafeDir;
    }

    void hit(Bot b, LivingEntity t, long now) {
        Player p = b.p; b.lastAttack = now;
        double miss = st.d("miss-chance") * (1.6 - 1.4 * skill(b));
        if (roll(miss)) { p.swingMainHand(); return; }
        p.attack(t); p.swingMainHand();
    }

    void shield(Bot b, long now, int ticks) {
        if (b.blocking) return;
        Nms.startUsing(b.nms, true); b.blocking = true; b.shieldUntil = now + Math.max(ticks, st.i("shield-raise-ticks"));
    }

    // ------------------------------------------------------------------- knockback (vanilla resets it for players; we put it back)
    void knockback(Bot b, double ax, double az, float yaw, boolean sprint, int kbLevel) {
        Player p = b.p; if (!p.isOnline() || p.isDead()) return;
        AttributeInstance kr = p.getAttribute(Attribute.KNOCKBACK_RESISTANCE);
        double res = 1 - Math.max(0, Math.min(1, kr == null ? 0 : kr.getValue()));
        if (res <= 0) return;
        Vector v = p.getVelocity(); boolean ground = Nms.onGround(b.nms);
        double dx = ax - p.getLocation().getX(), dz = az - p.getLocation().getZ(), len = Math.hypot(dx, dz);
        if (len < 1e-4) { dx = rnd.nextDouble() - 0.5; dz = rnd.nextDouble() - 0.5; len = Math.hypot(dx, dz); }
        double s = 0.4 * res;
        double vx = v.getX() / 2 - dx / len * s, vz = v.getZ() / 2 - dz / len * s, vy = ground ? Math.min(0.4, v.getY() / 2 + s) : v.getY();
        double extra = ((sprint ? 1 : 0) + kbLevel) * 0.5 * res;
        if (extra > 0) {
            double r = Math.toRadians(yaw);
            vx = vx / 2 - Math.sin(r) * extra; vz = vz / 2 + Math.cos(r) * extra;
            if (ground) vy = Math.min(0.4, vy / 2 + extra);
        }
        // jump reset: jump the moment you get hit to take less knockback
        if (ground && st.b("jump-reset") && roll(85 * skill(b))) { vy = 0.42; vx *= 0.65; vz *= 0.65; }
        p.setVelocity(new Vector(vx, vy, vz));
    }

    // ------------------------------------------------------------------- cobweb escape with a water bucket
    boolean webEscape(Bot b, long now) {
        Player p = b.p; Block feet = p.getLocation().getBlock(), head = feet.getRelative(0, 1, 0);
        Block web = feet.getType() == Material.COBWEB ? feet : head.getType() == Material.COBWEB ? head : null;
        if (web == null) { b.webTicks = 0; return false; }
        PlayerInventory inv = p.getInventory();
        if (!st.b("web-escape") || !has(inv, Material.WATER_BUCKET)) return false;
        if (++b.webTicks < 2 + react(b)) return false;
        b.webTicks = 0;
        if (b.blocking) { Nms.stopUsing(b.nms); b.blocking = false; b.antiMace = false; }
        face(b, feet.getLocation().add(0.5, 0.1, 0.5), 180);
        toHand(p, slotOf(inv, s -> s.getType() == Material.WATER_BUCKET));
        ItemStack hand = inv.getItemInMainHand();
        if (hand.getType() != Material.WATER_BUCKET) return false;
        if (hand.getAmount() > 1) { hand.setAmount(hand.getAmount() - 1); inv.setItemInMainHand(hand); inv.addItem(new ItemStack(Material.BUCKET)); }
        else inv.setItemInMainHand(new ItemStack(Material.BUCKET));
        if (head.getType() == Material.COBWEB && head != web) head.setType(Material.AIR);
        if (feet.getType() == Material.COBWEB && feet != web) feet.setType(Material.AIR);
        web.setType(Material.WATER);
        p.swingMainHand();
        final Block w = web;
        // pick the water back up a moment later
        Bukkit.getScheduler().runTaskLater(pl, () -> {
            if (w.getType() == Material.WATER && w.getBlockData() instanceof org.bukkit.block.data.Levelled lv && lv.getLevel() == 0) w.setType(Material.AIR);
            if (!p.isOnline()) return;
            int sl = slotOf(p.getInventory(), s -> s.getType() == Material.BUCKET);
            if (sl >= 0) { ItemStack bk = p.getInventory().getItem(sl); bk.setAmount(bk.getAmount() - 1); p.getInventory().setItem(sl, bk.getAmount() > 0 ? bk : null); p.getInventory().addItem(new ItemStack(Material.WATER_BUCKET)); }
        }, 12L + rnd.nextInt(6));
        return true;
    }

    // ------------------------------------------------------------------- fast re-totem after a pop
    void retotem(Bot b, long now) {
        Player p = b.p; PlayerInventory inv = p.getInventory();
        if (!st.b("auto-totem") || inv.getItemInOffHand().getType() == Material.TOTEM_OF_UNDYING || !has(inv, Material.TOTEM_OF_UNDYING) || b.antiMace) { b.retotemAt = 0; return; }
        if (!(st.b("totem-priority") || inv.getItemInOffHand().getType().isAir() || p.getHealth() < maxHp(p) * 0.5)) return;
        if (b.retotemAt == 0) { b.retotemAt = now + 1 + react(b); return; }
        if (now >= b.retotemAt) { if (b.blocking) { Nms.stopUsing(b.nms); b.blocking = false; } toOff(p, Material.TOTEM_OF_UNDYING); b.retotemAt = 0; }
    }

    // ------------------------------------------------------------------- ender pearl
    void pearl(Bot b, LivingEntity t, long now) {
        Player p = b.p; Location e = p.getEyeLocation(), to = t.getLocation();
        double dx = to.getX() - e.getX(), dz = to.getZ() - e.getZ(), x = Math.hypot(dx, dz), y = to.getY() - e.getY();
        double v = 1.5, g = 0.03, disc = v * v * v * v - g * (g * x * x + 2 * y * v * v);
        b.lastPearl = now;
        if (disc < 0) return;
        double ang = Math.atan((v * v - Math.sqrt(disc)) / (g * x)) * (1 + x * 0.004); // a bit higher for air drag
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz)), pitch = (float) -Math.toDegrees(ang);
        look(b, yaw, pitch);
        hold(p, s -> s.getType() == Material.ENDER_PEARL);
        p.launchProjectile(EnderPearl.class);
        take(p.getInventory(), Material.ENDER_PEARL);
        p.setCooldown(Material.ENDER_PEARL, 20);
        p.swingMainHand();
    }

    void webUnder(Bot b, LivingEntity t, long now) {
        if (!has(b.p.getInventory(), Material.COBWEB)) return;
        Block f = t.getLocation().getBlock();
        if (!f.getType().isAir()) return;
        f.setType(Material.COBWEB); take(b.p.getInventory(), Material.COBWEB); b.p.swingMainHand(); b.lastCobweb = now;
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
                look(b, p.getLocation().getYaw(), 90);
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
        // pick up dropped items within drop-distance
        double pr = st.d("drop-distance");
        for (Entity e : p.getNearbyEntities(pr, 2, pr)) if (e instanceof Item it && it.getPickupDelay() <= 0 && calm) it.teleport(p.getLocation());
        // eat
        if (st.b("auto-eat") && b.eatTicks == 0 && calm && (p.getFoodLevel() < 17 || (p.getHealth() < maxHp(p) * 0.6 && p.getFoodLevel() < 20))) {
            int slot = bestFood(inv, p.getHealth() < maxHp(p) * 0.5);
            if (slot >= 0) { toHand(p, slot); Nms.startUsing(b.nms, false); b.eatTicks = 40; }
        }
        // potions
        if (st.b("auto-potion") && now - b.lastPotion > 30) potions(b, now);
        // mend armor with xp bottles
        if (st.b("auto-mend") && calm && now - b.lastMend > 5 && damagedMending(p) && has(inv, Material.EXPERIENCE_BOTTLE)) {
            look(b, p.getLocation().getYaw(), 90);
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
                look(b, p.getLocation().getYaw(), 90);
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
        if (roll(st.d("mistake-chance") * (1.6 - 1.4 * skill(b)))) l.add(rnd.nextGaussian() * 1.2, rnd.nextGaussian() * 0.4, rnd.nextGaussian() * 1.2);
        face(b, l, aimStep(b));
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
        look(b, yaw, Math.max(-90, Math.min(90, pitch)));
    }
    void look(Bot b, float yaw, float pitch) { Nms.rotate(b.nms, yaw, pitch); }
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
    Predicate<ItemStack> swordFirst() { return s -> s.getType().name().endsWith("_SWORD") || (s.getType() == Material.MACE); }
    static int slotOf(PlayerInventory inv, Predicate<ItemStack> want) { for (int i = 0; i < 36; i++) { ItemStack s = inv.getItem(i); if (s != null && !s.getType().isAir() && want.test(s)) return i; } return -1; }
    static boolean has(PlayerInventory inv, Predicate<ItemStack> want) { return slotOf(inv, want) >= 0; }
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
        if (slot < 0) return;
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
