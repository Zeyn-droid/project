package net.blizzard.smp.skinbridge;

import net.skinsrestorer.api.PropertyUtils;
import net.skinsrestorer.api.SkinsRestorer;
import net.skinsrestorer.api.SkinsRestorerProvider;
import net.skinsrestorer.api.property.SkinProperty;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

public final class BlizzardSkinBridge extends JavaPlugin implements Listener {
    private static final Pattern TEXTURE_HASH = Pattern.compile("^[a-fA-F0-9]{32,64}$");
    private SkinsRestorer skinsRestorer;
    private HttpClient httpClient;
    private String workerUrl;
    private String apiSecret;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        workerUrl = getConfig().getString("worker-url", "").replaceAll("/+$", "");
        apiSecret = getConfig().getString("api-secret", "").trim();

        if (workerUrl.isBlank() || apiSecret.isBlank()) {
            getLogger().severe("Set worker-url and api-secret in plugins/BlizzardSkinBridge/config.yml");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        try {
            skinsRestorer = SkinsRestorerProvider.get();
        } catch (IllegalStateException exception) {
            getLogger().severe("SkinRestorer API is unavailable. Check that SkinsRestorer is enabled.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(8))
                .build();

        getServer().getPluginManager().registerEvents(this, this);

        long refreshSeconds = Math.max(60L, getConfig().getLong("refresh-seconds", 300L));
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            for (Player player : Bukkit.getOnlinePlayers()) {
                queueSync(player.getUniqueId(), player.getName());
            }
        }, 20L * 30L, 20L * refreshSeconds);

        getLogger().info("BlizzardSkinBridge enabled. Skin sync runs on join and periodically.");
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        String username = event.getPlayer().getName();

        // Wait briefly for SkinRestorer's join-time skin resolution.
        Bukkit.getScheduler().runTaskLater(this, () -> queueSync(uuid, username), 40L);
    }

    private void queueSync(UUID uuid, String username) {
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> syncSkin(uuid, username));
    }

    private void syncSkin(UUID uuid, String username) {
        try {
            Optional<SkinProperty> property = skinsRestorer.getPlayerStorage()
                    .getSkinForPlayer(uuid, username);

            if (property.isEmpty()) {
                getLogger().fine("No skin property found for " + username);
                return;
            }

            String textureUrl = PropertyUtils.getSkinTextureUrl(property.get());
            if (textureUrl == null || !textureUrl.matches(
                    "^https://textures\\.minecraft\\.net/texture/[a-fA-F0-9]{32,64}$")) {
                getLogger().warning("SkinRestorer returned an unexpected texture URL for " + username);
                return;
            }

            String json = "{"
                    + "\"uuid\":\"" + uuid + "\","
                    + "\"username\":\"" + escapeJson(username) + "\","
                    + "\"skin_url\":\"" + textureUrl + "\""
                    + "}";

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(workerUrl + "/skin"))
                    .timeout(Duration.ofSeconds(12))
                    .header("Authorization", "Bearer " + apiSecret)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                getLogger().fine("Synced skin for " + username);
            } else {
                getLogger().warning("Worker rejected skin sync for " + username
                        + " (HTTP " + response.statusCode() + "). Check the Worker /skin endpoint.");
            }
        } catch (Exception exception) {
            getLogger().warning("Could not sync skin for " + username + ": " + exception.getMessage());
        }
    }

    private String escapeJson(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}