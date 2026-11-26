package me.cosmic.fake;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Playstyles modelled on how high-tier PvPers actually fight. Every bot gets one, so a crowd of bots
 * doesn't move like clones. Numbers are tweaks on top of the bot's difficulty.
 */
enum Style {
    //            range  flipMin flipMax strafe  crit   stap   hop    retreatHp jumpReset shield  desc
    RUSHER   (-0.35,  14, 40,  0.35, -25,   +10,  0.35, 0.15,     0,      -10,  "Sprints in, never backs off, sprint-hit pressure"),
    SPACER   (+0.30,   8, 22,  0.75, -15,   +35,  0.10, 0.35,    -5,       +5,  "Keeps perfect distance, s-taps every hit, punishes whiffs"),
    CRITTER  (-0.20,  10, 30,  0.45, +40,   -20,  0.20, 0.25,     0,        0,  "Lives in the air, crit spam on every hit"),
    CIRCLER  ( 0.00,  30, 70,  0.85,  +5,    +5,  0.15, 0.25,     0,        0,  "Circles you one way for ages, then switches"),
    ZIGZAG   ( 0.00,   4, 12,  0.70,  +0,   +15,  0.30, 0.25,    +5,        0,  "Constant left-right switches, hard to track"),
    TANK     (-0.10,  18, 45,  0.40, -10,    -5,  0.05, 0.10,   +20,      +35,  "Jump resets everything, shields a lot, stuns with axe"),
    TRADER   (+0.10,  12, 35,  0.55, +15,   +20,  0.15, 0.20,   +10,      -15,  "Calm and patient, trades hits only when it wins them");

    final double range, strafe, hop, retreatHp; final int flipMin, flipMax, crit, stap, jumpReset, shield; final String desc;
    Style(double range, int flipMin, int flipMax, double strafe, int crit, int stap, double hop, double retreatHp, int jumpReset, int shield, String desc) {
        this.range = range; this.flipMin = flipMin; this.flipMax = flipMax; this.strafe = strafe; this.crit = crit; this.stap = stap;
        this.hop = hop; this.retreatHp = retreatHp; this.jumpReset = jumpReset; this.shield = shield; this.desc = desc;
    }
    static Style random() { Style[] v = values(); return v[ThreadLocalRandom.current().nextInt(v.length)]; }
    static Style of(String s) { for (Style v : values()) if (v.name().equalsIgnoreCase(s)) return v; return null; }
}
