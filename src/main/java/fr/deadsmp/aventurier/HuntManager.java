package fr.deadsmp.aventurier;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.scheduler.BukkitTask;

import java.util.UUID;

/**
 * Chasse à l'homme commune : une cible, un glow rouge, une bossbar avec les coordonnées
 * et le temps restant. Le temps est suspendu quand la cible est déconnectée.
 */
public final class HuntManager implements Listener {

    /** Fréquence de mise à jour de la bossbar (ticks). */
    private static final long PERIOD = 10L;

    private final AventurierPlugin plugin;
    private BossBar bar;
    private BukkitTask task;
    private UUID targetId;
    private String targetName;
    private long remaining;
    private long total;
    private int saveCounter;

    public HuntManager(AventurierPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean isActive() {
        return targetId != null;
    }

    public UUID targetId() {
        return targetId;
    }

    public String targetName() {
        return targetName;
    }

    // ------------------------------------------------------------------ cycle de vie

    public boolean start(CommandSender admin, Player target) {
        if (isActive()) {
            plugin.send(admin, "hunt-already");
            return false;
        }
        long minutes = plugin.getConfig().getLong("hunt.duration-minutes", 30L);
        launch(target.getUniqueId(), target.getName(), Long.MAX_VALUE);
        Bukkit.broadcast(plugin.msg("hunt-start",
                Placeholder.unparsed("target", target.getName()),
                Placeholder.unparsed("minutes", String.valueOf(minutes))));
        plugin.send(target, "hunt-you-are-target");
        return true;
    }

    /** Reprend une chasse sauvegardée avant l'arrêt du serveur. */
    public void resume() {
        UUID id = plugin.data().getHuntTarget();
        if (id == null) return;
        String name = Bukkit.getOfflinePlayer(id).getName();
        launch(id, name != null ? name : id.toString(), plugin.data().getHuntRemaining());
        plugin.getLogger().info("Chasse à l'homme reprise sur " + (name != null ? name : id) + ".");
    }

    public void stop(CommandSender admin) {
        if (!isActive()) return;
        String name = targetName != null ? targetName : "?";
        end();
        Bukkit.broadcast(plugin.msg("hunt-stopped",
                Placeholder.unparsed("target", name),
                Placeholder.unparsed("admin", admin.getName())));
    }

    /** Arrêt du serveur : on garde la chasse en sauvegarde pour la reprendre au prochain démarrage. */
    public void shutdown() {
        if (isActive()) saveState();
        if (task != null) {
            task.cancel();
            task = null;
        }
        if (bar != null) {
            for (Player p : Bukkit.getOnlinePlayers()) p.hideBossBar(bar);
            bar = null;
        }
    }

    private void launch(UUID id, String name, long remainingTicks) {
        this.targetId = id;
        this.targetName = name;
        this.total = Math.max(1L, plugin.getConfig().getLong("hunt.duration-minutes", 30L) * 60L * 20L);
        this.remaining = remainingTicks <= 0 ? total : Math.min(remainingTicks, total);
        this.saveCounter = 0;

        this.bar = BossBar.bossBar(Component.empty(), 1.0f, BossBar.Color.RED, BossBar.Overlay.PROGRESS);
        for (Player p : Bukkit.getOnlinePlayers()) p.showBossBar(bar);

        Player target = Bukkit.getPlayer(id);
        if (target != null) target.setGlowing(true);

        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 0L, PERIOD);
        saveState();
        plugin.boards().refreshAll();
    }

    private void end() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        if (bar != null) {
            for (Player p : Bukkit.getOnlinePlayers()) p.hideBossBar(bar);
            bar = null;
        }
        if (targetId != null) {
            Player target = Bukkit.getPlayer(targetId);
            if (target != null) {
                target.setGlowing(false);
            } else {
                // Cible hors ligne : on lui retirera le glow à sa reconnexion
                plugin.data().addPendingUnglow(targetId);
            }
        }
        targetId = null;
        targetName = null;
        plugin.data().setHunt(null, 0L);
        plugin.data().save();
        plugin.boards().refreshAll();
    }

    private void saveState() {
        if (targetId == null) return;
        plugin.data().setHunt(targetId, remaining);
        plugin.data().save();
    }

    // ------------------------------------------------------------------ tick

    private void tick() {
        if (!isActive() || bar == null) return;

        Player target = Bukkit.getPlayer(targetId);
        Component title;
        if (target != null) {
            targetName = target.getName();
            remaining -= PERIOD;
            if (remaining <= 0L) {
                timeout();
                return;
            }
            if (!target.isGlowing()) target.setGlowing(true);

            Location loc = target.getLocation();
            title = plugin.mm(plugin.getConfig().getString("hunt.bar-title",
                            "<red><bold>CHASSE</bold> <yellow><name> <white>X: <x> Y: <y> Z: <z> <time>"),
                    Placeholder.unparsed("name", targetName),
                    Placeholder.unparsed("x", String.valueOf(loc.getBlockX())),
                    Placeholder.unparsed("y", String.valueOf(loc.getBlockY())),
                    Placeholder.unparsed("z", String.valueOf(loc.getBlockZ())),
                    Placeholder.unparsed("world", worldLabel(loc.getWorld())),
                    Placeholder.unparsed("time", formatTime(remaining)));
        } else {
            title = plugin.mm(plugin.getConfig().getString("hunt.bar-title-paused",
                            "<red><bold>CHASSE EN PAUSE</bold> <yellow><name> <white><time>"),
                    Placeholder.unparsed("name", targetName != null ? targetName : "?"),
                    Placeholder.unparsed("time", formatTime(remaining)));
        }

        bar.name(title);
        bar.progress(Math.max(0f, Math.min(1f, (float) remaining / (float) total)));

        if (++saveCounter >= 20) { // toutes les 10 secondes
            saveCounter = 0;
            saveState();
        }
    }

    private void timeout() {
        String name = targetName != null ? targetName : "?";
        end();
        Bukkit.broadcast(plugin.msg("hunt-timeout", Placeholder.unparsed("target", name)));
    }

    private static String worldLabel(World world) {
        if (world == null) return "?";
        return switch (world.getEnvironment()) {
            case NETHER -> "Nether";
            case THE_END -> "End";
            default -> "Overworld";
        };
    }

    private static String formatTime(long ticks) {
        long seconds = Math.max(0L, ticks / 20L);
        return String.format("%02d:%02d", seconds / 60L, seconds % 60L);
    }

    // ------------------------------------------------------------------ mort de la cible

    /** Appelé par MissionManager quand un joueur en tue un autre pendant une chasse. */
    public void onKill(Player killer, Player victim) {
        if (!isActive() || !victim.getUniqueId().equals(targetId)) return;

        double reward = plugin.getConfig().getDouble("hunt.kill-reward", 0.0);
        if (reward > 0) {
            Economy economy = plugin.economy();
            if (economy != null) {
                economy.depositPlayer(killer, reward);
            } else {
                plugin.getLogger().warning("Récompense de chasse non versée : pas d'économie Vault.");
            }
        }
        String victimName = victim.getName();
        end();
        Bukkit.broadcast(plugin.msg("hunt-killed",
                Placeholder.unparsed("killer", killer.getName()),
                Placeholder.unparsed("target", victimName),
                Placeholder.unparsed("reward", plugin.formatMoney(reward))));
    }

    // ------------------------------------------------------------------ connexions

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();

        // Glow oublié d'une chasse terminée pendant qu'il était hors ligne
        if (plugin.data().removePendingUnglow(player.getUniqueId())
                && !(isActive() && player.getUniqueId().equals(targetId))) {
            player.setGlowing(false);
        }

        if (!isActive()) return;
        if (bar != null) player.showBossBar(bar);

        if (player.getUniqueId().equals(targetId)) {
            targetName = player.getName();
            player.setGlowing(true);
            plugin.send(player, "hunt-you-are-target");
            Bukkit.broadcast(plugin.msg("hunt-target-back", Placeholder.unparsed("target", player.getName())));
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        if (isActive() && player.getUniqueId().equals(targetId)) {
            Bukkit.broadcast(plugin.msg("hunt-target-left", Placeholder.unparsed("target", player.getName())));
            saveState();
        }
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        if (isActive() && player.getUniqueId().equals(targetId)) {
            // Le glow est perdu à la mort : on le remet juste après le respawn
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (isActive() && player.isOnline()) player.setGlowing(true);
            }, 2L);
        }
    }
}
