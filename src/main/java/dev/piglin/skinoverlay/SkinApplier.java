/*
 * #%L
 * SkinsRestorer
 * %%
 * Copyright (C) 2021 SkinsRestorer
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/gpl-3.0.html>.
 * #L%
 */

package dev.piglin.skinoverlay;

import com.google.common.hash.Hashing;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.PropertyMap;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Consumer;

public class SkinApplier implements Consumer<Player> {
    private final SkinOverlay plugin;
    private Class<?> playOutRespawn;
    private Class<?> playOutPlayerInfo;
    private Class<?> playOutPosition;
    private Class<?> packet;
    private Class<?> playOutHeldItemSlot;
    private Enum<?> peaceful;
    private Enum<?> removePlayerEnum;
    private Enum<?> addPlayerEnum;

    public SkinApplier(SkinOverlay plugin) {
        this.plugin = plugin;

        try {
            packet = ReflectionUtil.getNMSClass("Packet", "net.minecraft.network.protocol.Packet");
            playOutHeldItemSlot = ReflectionUtil.getNMSClass("PacketPlayOutHeldItemSlot", "net.minecraft.network.protocol.game.PacketPlayOutHeldItemSlot");
            playOutPosition = ReflectionUtil.getNMSClass("PacketPlayOutPosition", "net.minecraft.network.protocol.game.PacketPlayOutPosition");
            playOutPlayerInfo = ReflectionUtil.getNMSClass("PacketPlayOutPlayerInfo", "net.minecraft.network.protocol.game.PacketPlayOutPlayerInfo");
            playOutRespawn = ReflectionUtil.getNMSClass("PacketPlayOutRespawn", "net.minecraft.network.protocol.game.PacketPlayOutRespawn");

            peaceful = ReflectionUtil.getEnum(ReflectionUtil.getNMSClass("EnumDifficulty", "net.minecraft.world.EnumDifficulty"), "PEACEFUL");
            try {
                removePlayerEnum = ReflectionUtil.getEnum(playOutPlayerInfo, "EnumPlayerInfoAction", "REMOVE_PLAYER");
                addPlayerEnum = ReflectionUtil.getEnum(playOutPlayerInfo, "EnumPlayerInfoAction", "ADD_PLAYER");
            } catch (Exception e) {
                try {
                    Class<?> enumPlayerInfoAction = ReflectionUtil.getNMSClass("EnumPlayerInfoAction", null);

                    // 1.8 or below
                    removePlayerEnum = ReflectionUtil.getEnum(enumPlayerInfoAction, "REMOVE_PLAYER");
                    addPlayerEnum = ReflectionUtil.getEnum(enumPlayerInfoAction, "ADD_PLAYER");
                } catch (Exception e1) {
                    // Forge
                    removePlayerEnum = ReflectionUtil.getEnum(playOutPlayerInfo, "Action", "REMOVE_PLAYER");
                    addPlayerEnum = ReflectionUtil.getEnum(playOutPlayerInfo, "Action", "ADD_PLAYER");
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void sendPacket(Object playerConnection, Object packet) {
        ReflectionUtil.invokeMethod(playerConnection.getClass(), playerConnection, "sendPacket", new Class<?>[]{this.packet}, packet);
    }

    public void accept(Player player) {
        if (!plugin.skins.containsKey(player.getUniqueId())) return;

        try {
            final Object craftHandle = ReflectionUtil.invokeMethod(player, "getHandle");

            GameProfile profile = (GameProfile) ReflectionUtil.invokeMethod(craftHandle, "getProfile");
            PropertyMap properties = profile.getProperties();
            properties.removeAll("textures");
            properties.put("textures", plugin.skins.get(player.getUniqueId()));

            Location l = player.getLocation();

            List<Object> set = new ArrayList<>();
            set.add(craftHandle);

            Object removePlayer;
            Object addPlayer;
            try {
                //1.17+
                removePlayer = ReflectionUtil.invokeConstructor(playOutPlayerInfo, new Class<?>[]{this.removePlayerEnum.getClass().getSuperclass(), Collection.class}, this.removePlayerEnum, set);
                addPlayer = ReflectionUtil.invokeConstructor(playOutPlayerInfo, new Class<?>[]{this.addPlayerEnum.getClass().getSuperclass(), Collection.class}, this.addPlayerEnum, set);
            } catch (Exception ignored) {
                removePlayer = ReflectionUtil.invokeConstructor(playOutPlayerInfo, new Class<?>[]{this.removePlayerEnum.getClass(), Iterable.class}, this.removePlayerEnum, set);
                addPlayer = ReflectionUtil.invokeConstructor(playOutPlayerInfo, new Class<?>[]{this.addPlayerEnum.getClass(), Iterable.class}, this.addPlayerEnum, set);
            }

            // Slowly getting from object to object till i get what I need for
            // the respawn packet
            Object world = ReflectionUtil.invokeMethod(craftHandle, "getWorld");
            Object difficulty = ReflectionUtil.invokeMethod(world, "getDifficulty");

            Object worldData;
            try {
                worldData = ReflectionUtil.invokeMethod(world, "getWorldData");
            } catch (Exception ignored) {
                worldData = ReflectionUtil.getObject(world, "worldData");
            }

            Object worldType;
            try {
                worldType = ReflectionUtil.invokeMethod(worldData, "getType");
            } catch (Exception ignored) {
                worldType = ReflectionUtil.invokeMethod(worldData, "getGameType");
            }

            World.Environment environment = player.getWorld().getEnvironment();

            int dimension = 0;

            Object playerIntManager = ReflectionUtil.getFieldByClassName(craftHandle, "PlayerInteractManager");

            Enum<?> enumGamemode = (Enum<?>) ReflectionUtil.invokeMethod(playerIntManager, "getGameMode");
            // SkinOverlay start
            Enum<?> enumGamemodePrevious;
            try {
                // 1.16+
                enumGamemodePrevious = (Enum<?>) ReflectionUtil.invokeMethod(playerIntManager, "c");
            } catch (Exception ignored) {
                enumGamemodePrevious = null;
            }
            // SkinOverlay end

            Object respawn;
            try {
                dimension = environment.getId();
                respawn = ReflectionUtil.invokeConstructor(playOutRespawn,
                        new Class<?>[]{
                                int.class, peaceful.getClass(), worldType.getClass(), enumGamemode.getClass()
                        },
                        dimension, difficulty, worldType, enumGamemode);
            } catch (Exception ignored) {
                if (environment.equals(World.Environment.NETHER))
                    dimension = -1;
                else if (environment.equals(World.Environment.THE_END))
                    dimension = 1;

                // 1.13.x needs the dimensionManager instead of dimension id
                Class<?> dimensionManagerClass = ReflectionUtil.getNMSClass("DimensionManager", "net.minecraft.world.level.dimension.DimensionManager");
                Method m = dimensionManagerClass.getDeclaredMethod("a", Integer.TYPE);

                Object dimensionManger = null;
                try {
                    dimensionManger = m.invoke(null, dimension);
                } catch (Exception ignored2) {
                }

                try {
                    respawn = ReflectionUtil.invokeConstructor(playOutRespawn,
                            new Class<?>[]{
                                    dimensionManagerClass, peaceful.getClass(), worldType.getClass(), enumGamemode.getClass()
                            },
                            dimensionManger, difficulty, worldType, enumGamemode);
                } catch (Exception ignored2) {
                    // 1.14.x removed the difficulty from PlayOutRespawn
                    // https://wiki.vg/Pre-release_protocol#Respawn
                    try {
                        respawn = ReflectionUtil.invokeConstructor(playOutRespawn,
                                new Class<?>[]{
                                        dimensionManagerClass, worldType.getClass(), enumGamemode.getClass()
                                },
                                dimensionManger, worldType, enumGamemode);
                    } catch (Exception ignored3) {
                        // Minecraft 1.15 changes
                        // PacketPlayOutRespawn now needs the world seed

                        Object seed;
                        try {
                            seed = ReflectionUtil.invokeMethod(worldData, "getSeed");
                        } catch (Exception ignored4) {
                            // 1.16
                            seed = ReflectionUtil.invokeMethod(world, "getSeed");
                        }

                        //noinspection UnstableApiUsage
                        long seedEncrypted = Hashing.sha256().hashString(seed.toString(), StandardCharsets.UTF_8).asLong();
                        try {
                            respawn = ReflectionUtil.invokeConstructor(playOutRespawn,
                                    new Class<?>[]{
                                            dimensionManagerClass, long.class, worldType.getClass(), enumGamemode.getClass()
                                    },
                                    dimensionManger, seedEncrypted, worldType, enumGamemode);
                        } catch (Exception ignored5) {
                            // Minecraft 1.16.1 changes
                            try {
                                Object worldServer = ReflectionUtil.invokeMethod(craftHandle, "getWorldServer");

                                Object typeKey = ReflectionUtil.invokeMethod(worldServer, "getTypeKey");
                                Object dimensionKey = ReflectionUtil.invokeMethod(worldServer, "getDimensionKey");

                                respawn = ReflectionUtil.invokeConstructor(playOutRespawn,
                                        new Class<?>[]{
                                                typeKey.getClass(), dimensionKey.getClass(), long.class, enumGamemode.getClass(), enumGamemode.getClass(), boolean.class, boolean.class, boolean.class
                                        },
                                        typeKey,
                                        dimensionKey,
                                        seedEncrypted,
                                        enumGamemode,
                                        enumGamemodePrevious, // SkinOverlay
                                        ReflectionUtil.invokeMethod(worldServer, "isDebugWorld"),
                                        ReflectionUtil.invokeMethod(worldServer, "isFlatWorld"),
                                        true
                                );
                            } catch (Exception ignored6) {
                                // Minecraft 1.16.2 changes
                                Object worldServer = ReflectionUtil.invokeMethod(craftHandle, "getWorldServer");

                                Object dimensionManager = ReflectionUtil.invokeMethod(worldServer, "getDimensionManager");
                                Object dimensionKey = ReflectionUtil.invokeMethod(worldServer, "getDimensionKey");

                                respawn = ReflectionUtil.invokeConstructor(playOutRespawn,
                                        new Class<?>[]{
                                                dimensionManager.getClass(), dimensionKey.getClass(), long.class, enumGamemode.getClass(), enumGamemode.getClass(), boolean.class, boolean.class, boolean.class
                                        },
                                        dimensionManager,
                                        dimensionKey,
                                        seedEncrypted,
                                        enumGamemode,
                                        enumGamemodePrevious, // SkinOverlay
                                        ReflectionUtil.invokeMethod(worldServer, "isDebugWorld"),
                                        ReflectionUtil.invokeMethod(worldServer, "isFlatWorld"),
                                        true
                                );
                            }
                        }
                    }
                }
            }

            Object pos;
            try {
                // 1.17+
                pos = ReflectionUtil.invokeConstructor(playOutPosition,
                        new Class<?>[]{double.class, double.class, double.class, float.class, float.class, Set.class,
                                int.class, boolean.class},
                        l.getX(), l.getY(), l.getZ(), l.getYaw(), l.getPitch(), new HashSet<Enum<?>>(), 0, false);
            } catch (Exception e1) {
                try {
                    // 1.9-1.16.5
                    pos = ReflectionUtil.invokeConstructor(playOutPosition,
                            new Class<?>[]{double.class, double.class, double.class, float.class, float.class, Set.class,
                                    int.class},
                            l.getX(), l.getY(), l.getZ(), l.getYaw(), l.getPitch(), new HashSet<Enum<?>>(), 0);
                } catch (Exception e2) {
                    // 1.8 -
                    pos = ReflectionUtil.invokeConstructor(playOutPosition,
                            new Class<?>[]{double.class, double.class, double.class, float.class, float.class,
                                    Set.class},
                            l.getX(), l.getY(), l.getZ(), l.getYaw(), l.getPitch(), new HashSet<Enum<?>>());
                }
            }

            Object slot = ReflectionUtil.invokeConstructor(playOutHeldItemSlot, new Class<?>[]{int.class}, player.getInventory().getHeldItemSlot());

            Object playerCon = ReflectionUtil.getFieldByClassName(craftHandle, "PlayerConnection");

            sendPacket(playerCon, removePlayer);
            sendPacket(playerCon, addPlayer);

            sendPacket(playerCon, respawn);

            ReflectionUtil.invokeMethod(craftHandle, "updateAbilities");

            sendPacket(playerCon, pos);
            sendPacket(playerCon, slot);

            ReflectionUtil.invokeMethod(player, "updateScaledHealth");
            player.updateInventory();
            ReflectionUtil.invokeMethod(craftHandle, "triggerHealthUpdate");

            if (player.isOp()) {
                // TODO entityStatus
                Bukkit.getScheduler().runTask(plugin, () -> {
                    player.setOp(false);
                    player.setOp(true);
                });
            }
        } catch (NoSuchMethodException e) {
            e.printStackTrace();
        }
    }

    public static class ReflectionUtil {
        public static String serverVersion = null;

        static {
            try {
                Class.forName("org.bukkit.Bukkit");
                serverVersion = Bukkit.getServer().getClass().getPackage().getName().substring(Bukkit.getServer().getClass().getPackage().getName().lastIndexOf('.') + 1);
            } catch (Exception ignored) {
            }
        }

        private ReflectionUtil() {
        }

        private static Constructor<?> getConstructor(Class<?> clazz, Class<?>... args) throws Exception {
            Constructor<?> c = clazz.getConstructor(args);
            c.setAccessible(true);

            return c;
        }

        public static Enum<?> getEnum(Class<?> clazz, String constant) throws Exception {
            Class<?> c = Class.forName(clazz.getName());
            Enum<?>[] econstants = (Enum<?>[]) c.getEnumConstants();
            for (Enum<?> e : econstants)
                if (e.name().equalsIgnoreCase(constant))
                    return e;
            throw new Exception("Enum constant not found " + constant);
        }

        public static Enum<?> getEnum(Class<?> clazz, String enumname, String constant) throws Exception {
            Class<?> c = Class.forName(clazz.getName() + "$" + enumname);
            Enum<?>[] econstants = (Enum<?>[]) c.getEnumConstants();
            for (Enum<?> e : econstants)
                if (e.name().equalsIgnoreCase(constant))
                    return e;
            throw new Exception("Enum constant not found " + constant);
        }

        public static Field getField(Class<?> clazz, String fname) throws Exception {
            Field f;

            try {
                f = clazz.getDeclaredField(fname);
            } catch (Exception e) {
                f = clazz.getField(fname);
            }

            setFieldAccessible(f);

            return f;
        }

        private static Method getMethod(Class<?> clazz, String mname) {
            Method m;
            try {
                m = clazz.getDeclaredMethod(mname);
            } catch (Exception e) {
                try {
                    m = clazz.getMethod(mname);
                } catch (Exception ex) {
                    return null;
                }
            }

            m.setAccessible(true);
            return m;
        }

        private static Method getMethod(Class<?> clazz, String mname, Class<?>... args) {
            Method m;
            try {
                m = clazz.getDeclaredMethod(mname, args);
            } catch (Exception e) {
                try {
                    m = clazz.getMethod(mname, args);
                } catch (Exception ex) {
                    return null;
                }
            }

            m.setAccessible(true);
            return m;
        }

        public static Class<?> getNMSClass(String clazz, String fullClassName) {
            try {
                return forNameWithFallback(clazz, fullClassName);
            } catch (ClassNotFoundException e) {
                return exception(e).getClass();
            }
        }

        private static Class<?> forNameWithFallback(String clazz, String fullClassName) throws ClassNotFoundException {
            try {
                return Class.forName("net.minecraft.server." + serverVersion + "." + clazz);
            } catch (ClassNotFoundException ignored) {
                return Class.forName(fullClassName);
            }
        }

        public static Object getObject(Object obj, String fname) {
            try {
                return getField(obj.getClass(), fname).get(obj);
            } catch (Exception e) {
                return exception(e);
            }
        }

        public static Object getFieldByClassName(Object obj, String className) {
            try {
                for (Field f : obj.getClass().getDeclaredFields()) {
                    if (f.getType().getSimpleName().equalsIgnoreCase(className)) {
                        setFieldAccessible(f);

                        return f.get(obj);
                    }
                }

                for (Field f : obj.getClass().getFields()) {
                    if (f.getType().getSimpleName().equalsIgnoreCase(className)) {
                        setFieldAccessible(f);

                        return f.get(obj);
                    }
                }

                System.err.println("Could not find field of type " + className + " in " + obj.getClass().getSimpleName());
                return exception(null);
            } catch (Exception e) {
                return exception(e);
            }
        }

        public static Object invokeConstructor(Class<?> clazz, Class<?>[] args, Object... initargs) {
            try {
                return getConstructor(clazz, args).newInstance(initargs);
            } catch (Exception e) {
                return exception(e);
            }
        }

        public static Object invokeMethod(Class<?> clazz, Object obj, String method, Class<?>[] args, Object... initargs) {
            try {
                return Objects.requireNonNull(getMethod(clazz, method, args)).invoke(obj, initargs);
            } catch (Exception e) {
                return exception(e);
            }
        }

        public static Object invokeMethod(Object obj, String method) {
            try {
                return Objects.requireNonNull(getMethod(obj.getClass(), method)).invoke(obj);
            } catch (Exception e) {
                return exception(e);
            }
        }

        private static void setFieldAccessible(Field f) {
            // SkinOverlay: Modified to not use DuckBypass
            int mod = f.getModifiers();
            if (Modifier.isFinal(mod) && Modifier.isStatic(mod)) {
                try {
                    Field modifiersField = Field.class.getDeclaredField("modifiers");
                    modifiersField.setAccessible(true);
                    modifiersField.setInt(f, f.getModifiers() & ~Modifier.FINAL);
                    modifiersField.setAccessible(false);
                } catch (Exception e) {
                    exception(e);
                }
            }
        }

        private static Object exception(Exception e) {
            throw new IllegalStateException("Something went wrong. Please create an issue on https://github.com/PiglinDevelopment/SkinOverlay/issues/new and paste the full error:", e);
        }
    }
}