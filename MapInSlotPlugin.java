package dev.mapinslot.paper;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapView;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class MapInSlotPlugin extends JavaPlugin implements Listener {

    private static final String NAMESPACE = "mapinslot";
    private static final UUID PACK_ID = UUID.fromString("5d0c6f7e-3b7a-4c53-9f0e-0a1b2c3d4e5f");
    private static final Pattern TEX_NAME = Pattern.compile("map_(\\d+)\\.png");

    private File texDir;
    private PackServer server;
    private BukkitTask scanTask;
    private BukkitTask rebuildTask;

    private final Map<Integer, Long> lastCheck = new HashMap<>();

    private volatile byte[] packBytes;
    private volatile String packSha1;
    private volatile Set<Integer> packedIds = Collections.emptySet();

    private Color background;
    private long refreshMs;
    private boolean building;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadSettings();

        texDir = new File(getDataFolder(), "textures");
        if (!texDir.exists() && !texDir.mkdirs()) {
            getLogger().severe("Konnte Ordner 'textures' nicht erstellen.");
        }

        server = new PackServer(this);
        try {
            server.start(getConfig().getString("http.bind", "0.0.0.0"), getConfig().getInt("http.port", 8765));
            getLogger().info("Pack-Server laeuft auf Port " + getConfig().getInt("http.port", 8765));
        } catch (IOException e) {
            getLogger().severe("HTTP-Server konnte nicht gestartet werden: " + e.getMessage());
        }

        getServer().getPluginManager().registerEvents(this, this);
        buildPackAsync(false);

        long interval = Math.max(20L, getConfig().getLong("scan-interval-ticks", 100L));
        scanTask = getServer().getScheduler().runTaskTimer(this, this::scanAll, 100L, interval);
    }

    @Override
    public void onDisable() {
        if (scanTask != null) scanTask.cancel();
        if (rebuildTask != null) rebuildTask.cancel();
        if (server != null) server.stop();
    }

    private void reloadSettings() {
        reloadConfig();
        try {
            background = Color.decode(getConfig().getString("background-color", "#D6C79A"));
        } catch (NumberFormatException e) {
            background = new Color(0xD6C79A);
        }
        refreshMs = Math.max(1, getConfig().getLong("min-refresh-seconds", 120L)) * 1000L;
    }

    byte[] getPackBytes() {
        return packBytes;
    }

    // ------------------------------------------------------------------ Events

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        getServer().getScheduler().runTaskLater(this, () -> {
            if (!p.isOnline()) return;
            sendPack(p);
            scanPlayer(p);
        }, 60L);
    }

    @EventHandler
    public void onPickup(EntityPickupItemEvent e) {
        if (e.getEntity() instanceof Player p && e.getItem().getItemStack().getType() == Material.FILLED_MAP) {
            getServer().getScheduler().runTask(this, () -> scanPlayer(p));
        }
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (e.getWhoClicked() instanceof Player p) {
            getServer().getScheduler().runTask(this, () -> scanPlayer(p));
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (e.getPlayer() instanceof Player p) {
            getServer().getScheduler().runTask(this, () -> scanPlayer(p));
        }
    }

    // ---------------------------------------------------------------- Scanning

    private void scanAll() {
        for (Player p : getServer().getOnlinePlayers()) {
            scanPlayer(p);
        }
    }

    private void scanPlayer(Player p) {
        PlayerInventory inv = p.getInventory();
        ItemStack[] contents = inv.getContents();
        boolean newTexture = false;

        for (int i = 0; i < contents.length; i++) {
            ItemStack item = contents[i];
            if (item == null || item.getType() != Material.FILLED_MAP) continue;
            if (!(item.getItemMeta() instanceof MapMeta meta)) continue;
            MapView view = meta.getMapView();
            if (view == null) continue;

            int id = view.getId();
            if (ensureTexture(view, p)) newTexture = true;

            // Modell erst setzen, wenn die Textur im ausgelieferten Pack enthalten ist
            if (!packedIds.contains(id)) continue;

            NamespacedKey key = new NamespacedKey(NAMESPACE, "map_" + id);
            if (!(meta.hasItemModel() && key.equals(meta.getItemModel()))) {
                meta.setItemModel(key);
                item.setItemMeta(meta);
                inv.setItem(i, item);
            }
        }

        if (newTexture) {
            scheduleRebuild();
        }
    }

    /** @return true, wenn eine Textur neu geschrieben oder geaendert wurde. */
    private boolean ensureTexture(MapView view, Player p) {
        int id = view.getId();
        File file = new File(texDir, "map_" + id + ".png");
        long now = System.currentTimeMillis();
        Long last = lastCheck.get(id);
        if (last != null && file.exists() && now - last < refreshMs) {
            return false;
        }
        lastCheck.put(id, now);

        try {
            BufferedImage img = MapCapture.render(view, p, background);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(img, "png", out);
            byte[] png = out.toByteArray();
            if (file.exists() && Arrays.equals(Files.readAllBytes(file.toPath()), png)) {
                return false;
            }
            Files.write(file.toPath(), png);
            return true;
        } catch (IOException | RuntimeException ex) {
            getLogger().warning("Karte " + id + " konnte nicht gerendert werden: " + ex.getMessage());
            return false;
        }
    }

    // -------------------------------------------------------------- Pack bauen

    private void scheduleRebuild() {
        if (rebuildTask != null) return; // bereits geplant (Entprellung)
        rebuildTask = getServer().getScheduler().runTaskLater(this, () -> {
            rebuildTask = null;
            buildPackAsync(true);
        }, 100L);
    }

    private void buildPackAsync(boolean resend) {
        if (building) {
            scheduleRebuild();
            return;
        }
        building = true;
        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            try {
                Set<Integer> ids = new HashSet<>();
                byte[] zip = buildZip(ids);
                String sha1 = sha1Hex(zip);
                getServer().getScheduler().runTask(this, () -> {
                    boolean changed = !sha1.equals(packSha1);
                    packBytes = zip;
                    packSha1 = sha1;
                    packedIds = ids;
                    building = false;
                    if (resend && changed) {
                        for (Player p : getServer().getOnlinePlayers()) sendPack(p);
                        // Etwas warten, damit die Clients das Pack laden koennen
                        getServer().getScheduler().runTaskLater(this, this::scanAll, 60L);
                    } else {
                        scanAll();
                    }
                });
            } catch (Exception ex) {
                building = false;
                getLogger().severe("Pack konnte nicht gebaut werden: " + ex.getMessage());
            }
        });
    }

    private byte[] buildZip(Set<Integer> ids) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        int format = getConfig().getInt("pack.format", 46);

        try (ZipOutputStream zos = new ZipOutputStream(bo)) {
            String mcmeta = "{\"pack\":{\"pack_format\":" + format
                    + ",\"supported_formats\":{\"min_inclusive\":34,\"max_inclusive\":" + (format + 1000) + "}"
                    + ",\"description\":\"Map in Slot\"}}";
            put(zos, "pack.mcmeta", mcmeta.getBytes(StandardCharsets.UTF_8));

            File[] files = texDir.listFiles();
            if (files != null) {
                Arrays.sort(files);
                for (File f : files) {
                    Matcher m = TEX_NAME.matcher(f.getName());
                    if (!m.matches()) continue;
                    int id = Integer.parseInt(m.group(1));
                    String name = "map_" + id;
                    ids.add(id);

                    put(zos, "assets/" + NAMESPACE + "/textures/item/" + name + ".png", Files.readAllBytes(f.toPath()));
                    put(zos, "assets/" + NAMESPACE + "/models/item/" + name + ".json",
                            ("{\"parent\":\"minecraft:item/generated\",\"textures\":{\"layer0\":\""
                                    + NAMESPACE + ":item/" + name + "\"}}").getBytes(StandardCharsets.UTF_8));
                    put(zos, "assets/" + NAMESPACE + "/items/" + name + ".json",
                            ("{\"model\":{\"type\":\"minecraft:model\",\"model\":\""
                                    + NAMESPACE + ":item/" + name + "\"}}").getBytes(StandardCharsets.UTF_8));
                }
            }
        }
        return bo.toByteArray();
    }

    private static void put(ZipOutputStream zos, String name, byte[] data) throws IOException {
        ZipEntry e = new ZipEntry(name);
        e.setTime(1700000000000L); // fester Zeitstempel -> reproduzierbarer Hash
        zos.putNextEntry(e);
        zos.write(data);
        zos.closeEntry();
    }

    private static String sha1Hex(byte[] data) throws Exception {
        byte[] d = MessageDigest.getInstance("SHA-1").digest(data);
        StringBuilder sb = new StringBuilder();
        for (byte b : d) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private static byte[] hexToBytes(String hex) {
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    private void sendPack(Player p) {
        String sha1 = packSha1;
        if (sha1 == null) return;
        String url = getConfig().getString("http.public-url", "");
        if (url.isEmpty() || url.contains("DEINE-IP")) {
            getLogger().warning("Bitte 'http.public-url' in der config.yml setzen!");
            return;
        }
        url += (url.contains("?") ? "&" : "?") + "v=" + sha1;
        p.setResourcePack(PACK_ID, url, hexToBytes(sha1),
                Component.text(getConfig().getString("pack.prompt", "Map in Slot")),
                getConfig().getBoolean("pack.force", false));
    }

    // ----------------------------------------------------------------- Command

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage("/mapinslot <resend|rebuild>");
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "resend" -> {
                for (Player p : getServer().getOnlinePlayers()) sendPack(p);
                sender.sendMessage("Pack erneut an alle Spieler gesendet.");
            }
            case "rebuild" -> {
                reloadSettings();
                lastCheck.clear();
                buildPackAsync(true);
                sender.sendMessage("Pack wird neu gebaut.");
            }
            default -> sender.sendMessage("/mapinslot <resend|rebuild>");
        }
        return true;
    }
}
