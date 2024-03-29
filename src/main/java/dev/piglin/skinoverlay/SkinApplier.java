/*
 * SkinsRestorer
 *
 * Copyright (C) 2022 SkinsRestorer
 *
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
 */
package dev.piglin.skinoverlay;

import com.google.common.collect.ImmutableList;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.network.protocol.game.ClientboundSetCarriedItemPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.world.level.biome.BiomeManager;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.lang.reflect.*;
import java.util.*;
import java.util.function.Consumer;

public final class SkinApplier implements Consumer<Player> {
    private static void sendPacket(ServerPlayer player, Packet<?> packet) {
        player.connection.send(packet);
    }

    public void triggerHealthUpdate(Player player) {
        extractServerPlayer(player).resetSentInfo();
    }
    
    @Override
    public void accept(Player player) {
        try {
            final ServerPlayer entityPlayer = (ServerPlayer) player.getClass().getMethod("getHandle").invoke(player);

            ClientboundPlayerInfoPacket removePlayer = new ClientboundPlayerInfoPacket(ClientboundPlayerInfoPacket.Action.REMOVE_PLAYER, ImmutableList.of(entityPlayer));
            ClientboundPlayerInfoPacket addPlayer = new ClientboundPlayerInfoPacket(ClientboundPlayerInfoPacket.Action.ADD_PLAYER, ImmutableList.of(entityPlayer));

            // Slowly getting from object to object till we get what is needed for
            // the respawn packet
            ServerLevel world = entityPlayer.getLevel();
            ServerPlayerGameMode gamemode = entityPlayer.gameMode;

            ClientboundRespawnPacket respawn = new ClientboundRespawnPacket(
                    world.dimensionTypeId(),
                    world.dimension(),
                    BiomeManager.obfuscateSeed(world.getSeed()),
                    gamemode.getGameModeForPlayer(),
                    gamemode.getPreviousGameModeForPlayer(),
                    world.isDebug(),
                    world.isFlat(),
                    true,
                    entityPlayer.getLastDeathLocation()
            );

            Location l = player.getLocation();
            ClientboundPlayerPositionPacket pos = new ClientboundPlayerPositionPacket(l.getX(), l.getY(), l.getZ(), l.getYaw(), l.getPitch(), new HashSet<>(), 0, false);
            ClientboundSetCarriedItemPacket slot = new ClientboundSetCarriedItemPacket(player.getInventory().getHeldItemSlot());

            sendPacket(entityPlayer, removePlayer);
            sendPacket(entityPlayer, addPlayer);

            sendPacket(entityPlayer, respawn);

            entityPlayer.onUpdateAbilities();

            sendPacket(entityPlayer, pos);
            sendPacket(entityPlayer, slot);

            player.getClass().getMethod("updateScaledHealth").invoke(player);
            player.updateInventory();
            triggerHealthUpdate(player);

            if (player.isOp()) {
                Bukkit.getScheduler().runTask(SkinOverlay.getPlugin(SkinOverlay.class), () -> {
                    // Workaround..
                    player.setOp(false);
                    player.setOp(true);
                });
            }
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
            e.printStackTrace();
            throw new RuntimeException(e.getMessage());
        }
    }

    public static ServerPlayer extractServerPlayer(Player player) {
        try {
            return (ServerPlayer) player.getClass().getMethod("getHandle").invoke(player);
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
            e.printStackTrace();
            throw new RuntimeException(e.getMessage());
        }
    }
}