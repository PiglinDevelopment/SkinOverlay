package dev.piglin.skinoverlay;

import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.MemorySection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

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

    public final HashMap<UUID, Property> skins = new HashMap<>();
    private final File saveFile = new File(getDataFolder(), "save.yml");
    boolean save;
    boolean allowHttp;

    @Override
    public void onEnable() {
        if (!getServer().getOnlineMode()) {
            getLogger().severe("\033[31;1mThis plugin doesn't work properly on offline-mode servers.\033[0m");
        }
        saveDefaultConfig();
        save = getConfig().getBoolean("save");
        allowHttp = getConfig().getBoolean("allow http");
        for (String resource : new String[]{"none", "policeman", "mustache"}) {
            if (!new File(getDataFolder(), resource + ".png").exists())
                saveResource(resource + ".png", false);
        }
        getServer().getPluginManager().registerEvents(this, this);
        if (save) {
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
    }

    @Override
    public void onDisable() {
        if (save) {
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
    }

    public void updateSkin(Player player, boolean forOthers) {
        if (!skins.containsKey(player.getUniqueId())) return;
        GameProfile gameProfile = SkinApplier.extractServerPlayer(player).gameProfile;
        PropertyMap propertyMap = gameProfile.getProperties();
        propertyMap.removeAll("textures");
        propertyMap.put("textures", skins.get(player.getUniqueId()));
        getServer().getScheduler().runTask(this, () -> {
            player.hidePlayer(this, player);
            player.showPlayer(this, player);
            new SkinApplier().accept(player);
            if (forOthers) {
                getServer().getOnlinePlayers()
                        .stream()
                        .filter(p -> p != player)
                        .forEach(p -> {
                            p.hidePlayer(this, player);
                            p.showPlayer(this, player);
                        });
            }
        });
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String[] args) {
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
                case 1 -> {
                    if (sender.hasPermission("skinoverlay.overlay." + overlayName)) {
                        yield ImageIO.read(new File(getDataFolder(), overlayName + ".png"));
                    } else {
                        yield null;
                    }
                }
                case 0 -> {
                    if (sender.hasPermission("skinoverlay.wear.url")) {
                        try {
                            yield ImageIO.read(new ByteArrayInputStream(request(overlayName)));
                        } catch (Exception exception) {
                            yield null;
                        }
                    } else {
                        yield null;
                    }
                }
                default ->
                        throw new IllegalStateException("Unexpected value: " + (getOverlayList().contains(overlayName) ? 1 : 0));
            };
            if (overlay == null) {
                sender.sendMessage(message("no permission"));
                return true;
            }
            getServer().getScheduler().runTaskAsynchronously(this, () -> {
                try {
                    var profileBytes = request(String.format("https://sessionserver.mojang.com/session/minecraft/profile/%s", target.getUniqueId().toString().replaceAll("-", "")));
                    var json = JsonParser.parseString(new String(profileBytes));
                    JsonArray properties = json.getAsJsonObject().get("properties").getAsJsonArray();
                    for (var object : properties) {
                        if (object.getAsJsonObject().get("name").getAsString().equals("textures")) {
                            var base64 = object.getAsJsonObject().get("value").getAsString();
                            var value = new String(Base64.getDecoder().decode(base64));
                            var textureJson = JsonParser.parseString(value);
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
                                    var response = JsonParser.parseString(new String(con.getInputStream().readAllBytes()));
                                    var texture = response.getAsJsonObject().getAsJsonObject("data").getAsJsonObject("texture");
                                    var texturesValue = texture.get("value").getAsString();
                                    var texturesSignature = texture.get("signature").getAsString();
                                    skins.put(target.getUniqueId(), new Property("textures", texturesValue, texturesSignature));
                                    updateSkin(target, true);
                                    if (!save) skins.remove(target.getUniqueId());
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
        return Arrays.stream(Objects.requireNonNull(getDataFolder().listFiles()))
                .map(File::getName)
                .filter(file -> file.endsWith(".png"))
                .map(file -> file.substring(0, file.length() - 4))
                .collect(Collectors.toList());
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
        getServer().getScheduler().runTask(this, () -> updateSkin(event.getPlayer(), true));
    }

    private byte[] request(String address) {
        try {
            var url = new URL(address);
            if (!url.getProtocol().startsWith("https") && !(url.getProtocol().startsWith("http") && allowHttp)) {
                throw new IllegalArgumentException("Tried to use non-https protocol");
            }
            var con = (HttpsURLConnection) url.openConnection();
            con.setRequestMethod("GET");
            con.setRequestProperty("User-Agent", "SkinOverlay");
            var status = con.getResponseCode();
            assert status == 200;
            return con.getInputStream().readAllBytes();
        } catch (Exception exception) {
            throw new IllegalStateException("Unexpected error", exception);
        }
    }

    private String message(String path) {
        return ChatColor.translateAlternateColorCodes('&', Objects.requireNonNull(getConfig().getString("messages." + path)));
    }
}
