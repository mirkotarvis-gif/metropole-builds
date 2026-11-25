package me.cosmic.fake;

import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** one fake player and everything its brain remembers */
final class Bot {
    final String name; Object nms; Player p;
    Bot(String name, Object nms, Player p) { this.name = name; this.nms = nms; this.p = p; this.home = p.getLocation(); }

    // ---- manual (/player) actions
    static final class Act { final boolean once; final int interval; int left; Act(boolean once, int interval) { this.once = once; this.interval = Math.max(1, interval); } }
    final Map<String, Act> acts = new HashMap<>();
    float manualForward, manualStrafe; Block mining; float progress;

    // ---- movement output for this tick
    float forward, strafe; boolean jump;

    // ---- brain memory
    Location home;
    UUID forced;                 // /pvpbot attack target
    UUID revenge; long revengeAt;
    long lastAttack, lastCobweb, lastCrystal, lastAnchor, lastPotion, lastMend, lastWind, nextIdle, shieldUntil, strafeSwitch;
    int critWait = -1, drawTicks, eatTicks, stuck, macePhase;
    float strafeDir = 1;
    Location idleGoal, lastPos;
    String path; int pathIdx; int pathDir = 1;
    boolean blocking;

    // ---- pvp skill (1-100, -1 = use the global 'skill' setting) and chosen targets
    int skill = -1;
    final java.util.LinkedHashSet<String> targets = new java.util.LinkedHashSet<>();
    int tapTicks, webTicks; long stunAt = -1, lastPearl, lastGap, maceSeen, retotemAt, spawnedAt;
    boolean critPlan, antiMace, removing; long lastKickLog = -1000;
    org.bukkit.util.Vector tVel = new org.bukkit.util.Vector(); Location tLast; java.util.UUID tId; int webBreak;
}
