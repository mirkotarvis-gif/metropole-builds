package me.cosmic.fake;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.lang.reflect.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;

/** Everything that touches Minecraft internals (Paper runs Mojang-mapped since 1.20.5), all by reflection. */
final class Nms {
    private Nms() {}
    static ClassLoader cl() { return Bukkit.getServer().getClass().getClassLoader(); }
    static Class<?> cls(String n) throws ClassNotFoundException { return Class.forName(n, true, cl()); }

    /** join a fake player with this profile (authlib GameProfile or null) and return [nmsPlayer, bukkitPlayer] */
    static Object[] join(String name, Object profile, Location where) throws Throwable {
        Class<?> GP = cls("com.mojang.authlib.GameProfile");
        if (profile == null || !GP.isInstance(profile)) {
            UUID id = UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
            profile = construct(GP, id, name);
        }
        Object server = invoke(Bukkit.getServer(), "getServer");
        Object level = invoke(where.getWorld(), "getHandle");
        Object info = staticCall(cls("net.minecraft.server.level.ClientInformation"), "createDefault");
        Object sp = construct(cls("net.minecraft.server.level.ServerPlayer"), server, level, profile, info);
        Class<?> PF = cls("net.minecraft.network.protocol.PacketFlow");
        @SuppressWarnings({"unchecked", "rawtypes"}) Object serverbound = Enum.valueOf((Class) PF, "SERVERBOUND");
        Object conn = construct(cls("net.minecraft.network.Connection"), serverbound);
        setFieldOfType(conn, cls("io.netty.channel.Channel"), fakeChannel());
        setFieldOfType(conn, java.net.SocketAddress.class, new InetSocketAddress("127.0.0.1", 25565));
        Object cookie = construct(cls("net.minecraft.server.network.CommonListenerCookie"), profile, 0, info, false);
        invoke(invoke(server, "getPlayerList"), "placeNewPlayer", conn, sp, cookie);
        Player p = (Player) invoke(sp, "getBukkitEntity");
        p.teleport(where);
        return new Object[]{sp, p};
    }

    /** skin lookup (blocking, call async): returns an authlib GameProfile or null */
    static Object profile(String name) {
        try {
            Object pp = Bukkit.class.getMethod("createProfile", String.class).invoke(null, name);
            invoke(pp, "complete", true);
            for (String m : new String[]{"buildGameProfile", "getGameProfile", "asAuthlibCopy"}) {
                try { Object g = invoke(pp, m); if (g != null) return g; } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
        return null;
    }

    static void remove(Object sp, String why) {
        try {
            Object text = staticCall(cls("net.minecraft.network.chat.Component"), "literal", why);
            Object listener = getField(sp, "connection");
            invoke(listener, "onDisconnect", construct(cls("net.minecraft.network.DisconnectionDetails"), text));
        } catch (Throwable t) {
            try { invoke(invoke(invoke(Bukkit.getServer(), "getServer"), "getPlayerList"), "remove", sp); } catch (Throwable ignored) {}
        }
    }

    /** physics + movement tick for a fake player (real players get this from their movement packets) */
    static void tick(Object sp, float forward, float strafe, boolean jump, long serverTick) throws Throwable {
        invoke(sp, "setJumping", jump);
        setFloat(sp, "zza", forward);
        setFloat(sp, "xxa", strafe);
        invoke(sp, "doTick");
        if (serverTick % 10 == 0) {
            try { invoke(getField(sp, "connection"), "resetPosition"); } catch (Throwable ignored) {}
            try { invoke(invoke(invoke(sp, "level"), "getChunkSource"), "move", sp); } catch (Throwable ignored) {}
        }
    }

    static Object hand(boolean off) throws Throwable { return cls("net.minecraft.world.InteractionHand").getEnumConstants()[off ? 1 : 0]; }
    static void startUsing(Object sp, boolean off) { try { invoke(sp, "startUsingItem", hand(off)); } catch (Throwable ignored) {} }
    static void releaseUsing(Object sp) { try { invoke(sp, "releaseUsingItem"); } catch (Throwable ignored) {} }
    static void stopUsing(Object sp) { try { invoke(sp, "stopUsingItem"); } catch (Throwable ignored) {} }
    static boolean isUsing(Object sp) { try { return Boolean.TRUE.equals(invoke(sp, "isUsingItem")); } catch (Throwable t) { return false; } }
    static boolean onGround(Object sp) { try { return Boolean.TRUE.equals(invoke(sp, "onGround")); } catch (Throwable t) { return true; } }
    static void jumpNow(Object sp) { try { invoke(sp, "jumpFromGround"); } catch (Throwable ignored) {} }

    /** right click like a player: block in front, else entity, else the item itself */
    static void use(Object sp, Player p) throws Throwable {
        Object main = hand(false);
        Object level = invoke(sp, "level");
        Object gameMode = getField(sp, "gameMode");
        Object stack = invoke(sp, "getItemInHand", main);
        Object hit = invoke(sp, "pick", 4.5d, 1.0f, false);
        if (String.valueOf(invoke(hit, "getType")).equals("BLOCK")) {
            Object res = invoke(gameMode, "useItemOn", sp, level, stack, main, hit);
            String r = String.valueOf(res);
            if (!r.contains("FAIL") && !r.contains("PASS")) { p.swingMainHand(); return; }
        }
        Location eye = p.getEyeLocation();
        var r = eye.getWorld().rayTraceEntities(eye, eye.getDirection(), 3.0, e -> !e.equals(p));
        if (r != null && r.getHitEntity() != null) { try { invoke(sp, "interactOn", invoke(r.getHitEntity(), "getHandle"), main); p.swingMainHand(); return; } catch (Throwable ignored) {} }
        invoke(gameMode, "useItem", sp, level, stack, main);
    }

    // ------------------------------------------------------------ fake netty channel
    static Object fakeChannel() throws Throwable {
        Class<?> H = cls("io.netty.channel.ChannelHandler"), OUT = cls("io.netty.channel.ChannelOutboundHandler"), RC = cls("io.netty.util.ReferenceCountUtil");
        Object handler = Proxy.newProxyInstance(cl(), new Class<?>[]{OUT}, (proxy, m, args) -> {
            switch (m.getName()) {
                case "write" -> { try { RC.getMethod("release", Object.class).invoke(null, args[1]); } catch (Throwable ignored) {}
                                  try { invoke(args[2], "trySuccess"); } catch (Throwable ignored) {} return null; }
                case "read" -> { invoke(args[0], "read"); return null; }
                case "bind", "connect", "disconnect", "close", "deregister" -> {
                    try { invoke(args[0], m.getName(), Arrays.copyOfRange(args, 1, args.length)); } catch (Throwable t) { try { invoke(args[args.length - 1], "trySuccess"); } catch (Throwable ignored) {} }
                    return null;
                }
                case "hashCode" -> { return System.identityHashCode(proxy); }
                case "equals" -> { return proxy == args[0]; }
                case "toString" -> { return "CosmicBotsSink"; }
                default -> { return null; }
            }
        });
        Object arr = Array.newInstance(H, 1); Array.set(arr, 0, handler);
        return cls("io.netty.channel.embedded.EmbeddedChannel").getConstructor(arr.getClass()).newInstance(arr);
    }

    // ------------------------------------------------------------ reflection helpers
    static Object invoke(Object o, String name, Object... args) throws Throwable {
        for (Class<?> c = o.getClass(); c != null; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) if (m.getName().equals(name) && fits(m.getParameterTypes(), args)) { m.setAccessible(true); return call(m, o, args); }
            for (Class<?> i : c.getInterfaces()) for (Method m : i.getMethods()) if (m.getName().equals(name) && fits(m.getParameterTypes(), args)) return call(m, o, args);
        }
        throw new NoSuchMethodException(o.getClass().getName() + "." + name + "/" + args.length);
    }
    static Object call(Method m, Object o, Object[] a) throws Throwable { try { return m.invoke(o, a); } catch (InvocationTargetException e) { throw e.getCause() != null ? e.getCause() : e; } }
    static Object staticCall(Class<?> c, String name, Object... args) throws Throwable {
        for (Method m : c.getMethods()) if (m.getName().equals(name) && Modifier.isStatic(m.getModifiers()) && fits(m.getParameterTypes(), args)) return m.invoke(null, args);
        throw new NoSuchMethodException(c.getName() + "." + name);
    }
    static boolean fits(Class<?>[] t, Object[] a) {
        if (t.length != a.length) return false;
        for (int i = 0; i < t.length; i++) { if (a[i] == null) { if (t[i].isPrimitive()) return false; continue; } if (!box(t[i]).isInstance(a[i])) return false; }
        return true;
    }
    static Class<?> box(Class<?> c) {
        if (!c.isPrimitive()) return c;
        if (c == int.class) return Integer.class; if (c == boolean.class) return Boolean.class; if (c == float.class) return Float.class;
        if (c == double.class) return Double.class; if (c == long.class) return Long.class; if (c == short.class) return Short.class;
        if (c == byte.class) return Byte.class; if (c == char.class) return Character.class; return Void.class;
    }
    static Object construct(Class<?> c, Object... vals) throws Throwable {
        Constructor<?> best = null; Object[] bestArgs = null; int bestScore = -1;
        for (Constructor<?> k : c.getDeclaredConstructors()) {
            Class<?>[] t = k.getParameterTypes(); Object[] args = new Object[t.length]; boolean[] used = new boolean[vals.length]; int score = 0;
            for (int i = 0; i < t.length; i++) {
                int found = -1;
                for (int j = 0; j < vals.length; j++) if (!used[j] && vals[j] != null && box(t[i]).isInstance(vals[j])) { found = j; break; }
                if (found >= 0) { used[found] = true; args[i] = vals[found]; score++; }
                else if (t[i].isPrimitive()) args[i] = t[i] == boolean.class ? false : t[i] == int.class ? 0 : t[i] == long.class ? 0L : t[i] == float.class ? 0f : t[i] == double.class ? 0d : (Object) 0;
                else if (t[i] == Optional.class) args[i] = Optional.empty();
            }
            boolean ok = true; for (boolean u : used) if (!u) ok = false;
            if (ok && (score > bestScore || (score == bestScore && best != null && t.length < best.getParameterCount()))) { best = k; bestArgs = args; bestScore = score; }
        }
        if (best == null) throw new NoSuchMethodException("no constructor for " + c.getName());
        best.setAccessible(true);
        try { return best.newInstance(bestArgs); } catch (InvocationTargetException e) { throw e.getCause() != null ? e.getCause() : e; }
    }
    static Object getField(Object o, String name) throws Throwable {
        for (Class<?> c = o.getClass(); c != null; c = c.getSuperclass()) try { Field f = c.getDeclaredField(name); f.setAccessible(true); return f.get(o); } catch (NoSuchFieldException ignored) {}
        throw new NoSuchFieldException(name);
    }
    static void setFloat(Object o, String name, float v) throws Throwable {
        for (Class<?> c = o.getClass(); c != null; c = c.getSuperclass()) try { Field f = c.getDeclaredField(name); f.setAccessible(true); f.setFloat(o, v); return; } catch (NoSuchFieldException ignored) {}
    }
    static void setFieldOfType(Object o, Class<?> type, Object v) throws Throwable {
        for (Class<?> c = o.getClass(); c != null; c = c.getSuperclass())
            for (Field f : c.getDeclaredFields()) if (!Modifier.isStatic(f.getModifiers()) && f.getType() == type) { f.setAccessible(true); f.set(o, v); return; }
        throw new NoSuchFieldException(type.getName());
    }
}
