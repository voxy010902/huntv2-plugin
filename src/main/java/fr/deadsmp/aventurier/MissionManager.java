package fr.deadsmp.aventurier;

import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Cibles choisies auprès du PNJ, primes, anti-triche, vengeance et paliers.
 *
 * La « prime » d'un joueur = ce que gagne celui qui le tue après l'avoir choisi :
 * (montant de base selon SON rank + bonus de vengeance) × pourcentage anti-triche.
 */
public final class MissionManager implements Listener {

    private final AventurierPlugin plugin;
    /** chasseur -> cible choisie (en mémoire : à rechoisir après un redémarrage). */
    private final Map<UUID, UUID> targets = new HashMap<>();
    /** seuil de morts PvP -> pourcentage de la récompense. */
    private final TreeMap<Integer, Integer> antiFarm = new TreeMap<>();

    public MissionManager(AventurierPlugin plugin) {
        this.plugin = plugin;
        reloadSettings();
    }

    public void reloadSettings() {
        antiFarm.clear();
        ConfigurationSection section = plugin.getConfig().getConfigurationSection("rewards.anti-farm");
        if (section == null) return;
        for (String key : section.getKeys(false)) {
            try {
                antiFarm.put(Integer.parseInt(key), section.getInt(key));
            } catch (NumberFormatException ex) {
                plugin.getLogger().warning("rewards.anti-farm : clé invalide '" + key + "' (un nombre est attendu).");
            }
        }
    }

    // ------------------------------------------------------------------ cibles

    public UUID getTarget(UUID hunter) {
        return targets.get(hunter);
    }

    public void setTarget(Player hunter, Player target) {
        targets.put(hunter.getUniqueId(), target.getUniqueId());
        plugin.send(hunter, "target-selected",
                Placeholder.unparsed("target", target.getName()),
                Placeholder.unparsed("reward", plugin.formatMoney(prime(target.getUniqueId()))));
        plugin.boards().update(hunter);
    }

    public void clearTarget(Player hunter) {
        if (targets.remove(hunter.getUniqueId()) != null) {
            plugin.send(hunter, "target-cleared");
            plugin.boards().update(hunter);
        }
    }

    // ------------------------------------------------------------------ rank

    /** Groupe LuckPerms retenu pour un joueur en ligne (premier trouvé dans rewards.rank-priority). */
    public String rankOf(Player player) {
        List<String> priority = plugin.getConfig().getStringList("rewards.rank-priority");
        for (String rank : priority) {
            if (player.hasPermission("group." + rank)) return rank.toLowerCase(Locale.ROOT);
        }
        return fallbackRank(priority);
    }

    /** Idem, mais fonctionne aussi hors ligne grâce au dernier rank mémorisé. */
    public String rankOf(UUID id) {
        Player online = Bukkit.getPlayer(id);
        if (online != null) {
            String rank = rankOf(online);
            plugin.data().setLastRank(id, rank);
            return rank;
        }
        String cached = plugin.data().getLastRank(id);
        if (cached != null) return cached;
        return fallbackRank(plugin.getConfig().getStringList("rewards.rank-priority"));
    }

    private static String fallbackRank(List<String> priority) {
        return priority.isEmpty() ? "default" : priority.get(priority.size() - 1).toLowerCase(Locale.ROOT);
    }

    // ------------------------------------------------------------------ prime

    public int antiFarmPercent(int pvpDeaths) {
        Map.Entry<Integer, Integer> entry = antiFarm.floorEntry(pvpDeaths);
        return entry == null ? 100 : Math.max(0, entry.getValue());
    }

    /** Montant de base pour un rank donné. */
    public double baseFor(String rank) {
        return plugin.getConfig().getDouble("rewards.amounts." + rank,
                plugin.getConfig().getDouble("rewards.amounts.default", 0.0));
    }

    /** Ce que gagne un joueur qui tue {@code playerId} après l'avoir choisi auprès du PNJ. */
    public double prime(UUID playerId) {
        double base = baseFor(rankOf(playerId));
        double bonus = plugin.data().getBounty(playerId);
        int percent = antiFarmPercent(plugin.data().getPvpDeaths(playerId));
        return Math.round((base + bonus) * percent) / 100.0;
    }

    // ------------------------------------------------------------------ morts

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        Player killer = victim.getKiller();
        if (killer == null || killer.getUniqueId().equals(victim.getUniqueId())) return;

        // Tout est calculé AVANT d'ajouter cette mort au compteur de la victime
        UUID chosen = targets.get(killer.getUniqueId());
        if (victim.getUniqueId().equals(chosen)) {
            completeMission(killer, victim);
        }
        applyRevenge(killer, victim);

        if (plugin.hunt().isActive()) {
            plugin.hunt().onKill(killer, victim);
        }

        plugin.data().addPvpDeath(victim.getUniqueId());
        plugin.data().save();
    }

    private void completeMission(Player killer, Player victim) {
        UUID victimId = victim.getUniqueId();
        double reward = prime(victimId);
        targets.remove(killer.getUniqueId());

        if (reward > 0) {
            Economy economy = plugin.economy();
            if (economy != null) {
                economy.depositPlayer(killer, reward);
            } else {
                plugin.getLogger().warning("Récompense de " + reward + " non versée : pas d'économie Vault.");
            }
            // La prime est encaissée : le bonus de vengeance de la victime repart à zéro
            if (plugin.getConfig().getBoolean("rewards.bounty.reset-on-claim", true)) {
                plugin.data().setBounty(victimId, 0.0);
            }
            plugin.send(killer, "mission-complete",
                    Placeholder.unparsed("target", victim.getName()),
                    Placeholder.unparsed("reward", plugin.formatMoney(reward)));
            killer.playSound(killer.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.0f);
        } else {
            plugin.send(killer, "mission-no-reward", Placeholder.unparsed("target", victim.getName()));
        }

        boolean counts = reward > 0 || plugin.getConfig().getBoolean("missions.count-missions-without-reward", false);
        if (counts) {
            int total = plugin.data().addMission(killer.getUniqueId());
            checkMilestone(killer, total);
        }
        plugin.data().save();
        plugin.boards().update(killer);
    }

    /**
     * Vengeance : si la victime t'avait choisi comme cible auprès du PNJ, TA prime augmente.
     * Le bonus est réduit par l'anti-triche de la victime et plafonné.
     */
    private void applyRevenge(Player killer, Player victim) {
        UUID killerId = killer.getUniqueId();
        UUID victimId = victim.getUniqueId();
        if (!killerId.equals(targets.get(victimId))) return; // la victime ne te chassait pas
        targets.remove(victimId); // sa mission est consommée : il devra te rechoisir

        double step = plugin.getConfig().getDouble("rewards.bounty.revenge-bonus", 0.0);
        if (step <= 0) return;
        int percent = antiFarmPercent(plugin.data().getPvpDeaths(victimId));
        double add = Math.round(step * percent) / 100.0;
        if (add <= 0) return;

        double max = plugin.getConfig().getDouble("rewards.bounty.max-bonus", 0.0);
        double current = plugin.data().getBounty(killerId);
        double updated = current + add;
        if (max > 0) updated = Math.min(updated, max);
        double gained = updated - current;

        if (gained <= 0.0001) {
            plugin.send(killer, "revenge-max", Placeholder.unparsed("target", victim.getName()));
            return;
        }
        plugin.data().setBounty(killerId, updated);
        plugin.send(killer, "revenge-bonus",
                Placeholder.unparsed("target", victim.getName()),
                Placeholder.unparsed("added", plugin.formatMoney(gained)),
                Placeholder.unparsed("prime", plugin.formatMoney(prime(killerId))));
        plugin.boards().update(killer);
    }

    // ------------------------------------------------------------------ paliers

    /** Tous les X missions : débloque la permission suivante de la liste. */
    private void checkMilestone(Player player, int totalMissions) {
        int interval = plugin.getConfig().getInt("missions.milestone-interval", 10);
        if (interval <= 0 || totalMissions % interval != 0) return;

        List<String> permissions = plugin.getConfig().getStringList("missions.milestone-permissions");
        int index = totalMissions / interval - 1;
        if (index < 0 || index >= permissions.size()) return;

        String permission = permissions.get(index);
        LuckPermsHook luckPerms = plugin.luckPerms();
        if (luckPerms == null) {
            plugin.getLogger().warning(player.getName() + " a atteint " + totalMissions
                    + " missions mais LuckPerms est absent : permission '" + permission + "' non donnée.");
            return;
        }
        luckPerms.grantPermission(player.getUniqueId(), permission);
        plugin.getLogger().info(player.getName() + " débloque la permission '" + permission
                + "' (" + totalMissions + " missions).");
        plugin.send(player, "milestone-unlocked",
                Placeholder.unparsed("count", String.valueOf(totalMissions)),
                Placeholder.unparsed("permission", permission));
        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 0.6f);
    }
}
