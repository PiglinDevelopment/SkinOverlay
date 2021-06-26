package dev.piglin.skinoverlay;

import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import com.mojang.authlib.properties.Property;
import net.minecraft.network.protocol.game.PacketPlayOutHeldItemSlot;
import net.minecraft.network.protocol.game.PacketPlayOutPlayerInfo;
import net.minecraft.network.protocol.game.PacketPlayOutPosition;
import net.minecraft.network.protocol.game.PacketPlayOutRespawn;
import net.minecraft.server.level.EntityPlayer;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.MemorySection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.craftbukkit.v1_17_R1.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

import javax.imageio.ImageIO;
import javax.net.ssl.HttpsURLConnection;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.*;
import java.util.stream.Collectors;

public final class SkinOverlay extends JavaPlugin implements Listener {

    private final HashMap<UUID, Property> skins = new HashMap<>();
    private final File saveFile = new File(getDataFolder(), "save.yml");

    public static SkinOverlay getInstance() {
        return getPlugin(SkinOverlay.class);
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        for (String resource : new String[]{"none", "policeman", "mustache"}) {
            if (!new File(getDataFolder(), resource + ".png").exists())
                saveResource(resource + ".png", false);
        }
        getServer().getPluginManager().registerEvents(this, this);
        try {
            if (!saveFile.exists()) saveFile.createNewFile();
            var save = YamlConfiguration.loadConfiguration(saveFile);
            save.getValues(false).forEach((uuid, property) -> {
                var prop = (MemorySection) property;
                skins.put(UUID.fromString(uuid), new Property("textures", prop.getString("value"), prop.getString("signature")));
            });
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void onDisable() {
        try {
            if (!saveFile.exists()) saveFile.createNewFile();
            var save = YamlConfiguration.loadConfiguration(saveFile);
            skins.forEach((uuid, property) -> {
                var map = new HashMap<>();
                map.put("value", property.getValue());
                map.put("signature", property.getSignature());
                save.set(uuid.toString(), map);
            });
            save.save(saveFile);
            skins.clear();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        var isPlayer = sender instanceof Player;
        var requiredArguments = isPlayer ? 1 : 2;
        if (args.length < requiredArguments || args.length > 2) return false;
        var others = args.length == 2;
        if (others && !sender.hasPermission("skinoverlay.wear.others")) return false;
        var target = others ? getServer().getPlayer(args[0]) : (Player) sender;
        if (target == null) return false;
        var overlayName = others ? args[1] : args[0];
        try {
            var overlay = switch (getOverlayList().contains(overlayName) ? 1 : 0) {
                case 1 -> ImageIO.read(new File(getDataFolder(), overlayName + ".png"));
                case 0 -> {
                    if(sender.hasPermission("skinoverlay.wear.url")) {
                        yield ImageIO.read(new ByteArrayInputStream(request(overlayName, "GET", null)));
                    } else {
                        yield null;
                    }
                }
                default -> throw new IllegalStateException("Unexpected value: " + (getOverlayList().contains(overlayName) ? 1 : 0));
            };
            if(overlay == null) return false; 
            getServer().getScheduler().runTaskAsynchronously(this, () -> {
                try {
                    var profileBytes = request(String.format("https://sessionserver.mojang.com/session/minecraft/profile/%s", target.getUniqueId().toString().replaceAll("-", "")), "GET", null);
                    var json = new JsonParser().parse(new String(profileBytes));
                    JsonArray properties = json.getAsJsonObject().get("properties").getAsJsonArray();
                    for (var object : properties) {
                        if (object.getAsJsonObject().get("name").getAsString().equals("textures")) {
                            var base64 = object.getAsJsonObject().get("value").getAsString();
                            var value = new String(Base64.getDecoder().decode(base64));
                            var textureJson = new JsonParser().parse(value);
                            var skinUrl = textureJson.getAsJsonObject().getAsJsonObject("textures").getAsJsonObject("SKIN").get("url").getAsString();
                            var skin = ImageIO.read(new URL(skinUrl));
                            var image = new BufferedImage(skin.getWidth(), skin.getHeight(), BufferedImage.TYPE_INT_ARGB);
                            var canvas = image.createGraphics();
                            canvas.drawImage(skin, 0, 0, null);
                            canvas.drawImage(overlay, 0, 0, null);
                            var stream = new ByteArrayOutputStream();
                            ImageIO.write(image, "PNG", stream);
                            canvas.dispose();
                            var boundary = "*****";
                            var crlf = "\r\n";
                            var twoHyphens = "--";
                            var con = (HttpsURLConnection) new URL("https://api.mineskin.org/generate/upload?visibility=1").openConnection();
                            con.setRequestMethod("POST");
                            con.setRequestProperty("Connection", "Keep-Alive");
                            con.setRequestProperty("Cache-Control", "no-cache");
                            con.setRequestProperty("User-Agent", "SkinOverlay");
                            con.setDoOutput(true);
                            con.setRequestProperty("Content-Type", "multipart/form-data;boundary=" + boundary);
                            con.getOutputStream().write((twoHyphens + boundary + crlf).getBytes());
                            con.getOutputStream().write(("Content-Disposition: form-data; name=\"" +
                                    "file" + "\";filename=\"" +
                                    "file.png" + "\"" + crlf).getBytes());
                            con.getOutputStream().write((crlf).getBytes());
                            con.getOutputStream().write(stream.toByteArray());
                            con.getOutputStream().write(crlf.getBytes());
                            con.getOutputStream().write((twoHyphens + boundary + twoHyphens + crlf).getBytes());
                            con.getOutputStream().close();
                            var status = con.getResponseCode();
                            switch (status) {
                                case 429 -> sender.sendMessage(message("too many requests"));
                                case 200 -> {
                                    var response = new JsonParser().parse(new String(con.getInputStream().readAllBytes()));
                                    var texture = response.getAsJsonObject().getAsJsonObject("data").getAsJsonObject("texture");
                                    var texturesValue = texture.get("value").getAsString();
                                    var texturesSignature = texture.get("signature").getAsString();
                                    skins.put(target.getUniqueId(), new Property("textures", texturesValue, texturesSignature));
                                    updateSkin(target);
                                    sender.sendMessage(message("done").replaceAll("\\{minecrafttextures}", texture.get("url").getAsString()));
                                }
                                default -> sender.sendMessage(message("unknown error"));
                            }
                        }
                    }
                } catch (Exception exception) {
                    throw new IllegalStateException("Unexpected error", exception);
                }
            });
        } catch (IOException e) {
            sender.sendMessage(message("unknown error"));
        }
        return true;
    }

    public List<String> getOverlayList() {
        return Arrays.stream(getDataFolder().listFiles())
                .map(File::getName)
                .filter(file -> file.endsWith(".png"))
                .map(file -> file.substring(0, file.length() - 4))
                .collect(Collectors.toList());
    }

    public void updateSkin(Player player) {
        if (!skins.containsKey(player.getUniqueId())) return;
        var cp = (CraftPlayer) player;
        var profile = cp.getProfile();
        profile.getProperties().removeAll("textures");
        profile.getProperties().put("textures", skins.get(player.getUniqueId()));
        var ep = cp.getHandle();

        var removeInfo = new PacketPlayOutPlayerInfo(
                PacketPlayOutPlayerInfo.EnumPlayerInfoAction.e, ep);
        var addInfo = new PacketPlayOutPlayerInfo(
                PacketPlayOutPlayerInfo.EnumPlayerInfoAction.a, ep);

        var worldServer = ep.getWorldServer();

        var respawn = new PacketPlayOutRespawn(worldServer.getDimensionManager(), worldServer.getDimensionKey(),
                worldServer.getSeed(), ep.d.getGameMode(), ep.d.c(),
                worldServer.isDebugWorld(), worldServer.isFlatWorld(), true);

        var position = new PacketPlayOutPosition(
                player.getLocation().getX(),
                player.getLocation().getY(),
                player.getLocation().getZ(),
                player.getLocation().getYaw(),
                player.getLocation().getPitch(),
                Collections.emptySet(),
                0,
                false
        );
        var slot = new PacketPlayOutHeldItemSlot(player.getInventory().getHeldItemSlot());

        getServer().getScheduler().runTask(this, () -> sendUpdate(ep, removeInfo, addInfo, respawn, position, slot));
    }

    private void sendUpdate(EntityPlayer ep,
                            PacketPlayOutPlayerInfo removeInfo,
                            PacketPlayOutPlayerInfo addInfo,
                            PacketPlayOutRespawn respawn,
                            PacketPlayOutPosition position,
                            PacketPlayOutHeldItemSlot slot) {

        var player = ep.getBukkitEntity();

        for (Player p : getServer().getOnlinePlayers()) {
            p.hidePlayer(this, player);
            p.showPlayer(this, player);
        }

        ep.b.sendPacket(removeInfo);
        ep.b.sendPacket(addInfo);
        ep.b.sendPacket(respawn);
        ep.b.sendPacket(position);
        ep.b.sendPacket(slot);

        player.updateScaledHealth();
        player.recalculatePermissions();
        ep.updateAbilities();
        if(player.isOp()) {
            player.setOp(false);
            player.setOp(true);
        }
        player.updateInventory();
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length > 2) return new ArrayList<>();
        if (args.length == 2 && !sender.hasPermission("skinoverlay.wear.others")) return new ArrayList<>();
        if (args.length == 2 && getServer().getPlayer(args[0]) == null)
            return Collections.singletonList("Error: player not found");
        var overlays = getOverlayList()
                .stream()
                .filter(overlay -> overlay.toLowerCase().startsWith(args[args.length - 1]))
                .collect(Collectors.toList());
        var players = getServer().getOnlinePlayers()
                .stream()
                .map(Player::getName)
                .filter(name -> name.toLowerCase().startsWith(args[0]))
                .collect(Collectors.toList());
        return (overlays.isEmpty() && args.length == 1 && sender.hasPermission("skinoverlay.wear.others"))
                ? players
                : overlays;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        getServer().getScheduler().runTask(this, () -> updateSkin(event.getPlayer()));
    }

    private byte[] request(String url, String method, byte[] data) {
        try {
            var con = (HttpsURLConnection) new URL(url).openConnection();
            con.setRequestMethod(method);
            con.setRequestProperty("User-Agent", "SkinOverlay");
            if (data != null) {
                con.setDoOutput(true);
                con.getOutputStream().write(data);
                con.getOutputStream().close();
            }
            var status = con.getResponseCode();
            assert status == 200;
            return con.getInputStream().readAllBytes();
        } catch (Exception exception) {
            throw new IllegalStateException("Unexpected error", exception);
        }
    }

    private String message(String path) {
        return ChatColor.translateAlternateColorCodes('&', getConfig().getString("messages." + path));
    }
}
