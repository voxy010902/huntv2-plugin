package fr.deadsmp.aventurier;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Stockage simple dans plugins/DeadSMP/data.yml. */
public final class DataStore {

    private final AventurierPlugin plugin;
    private final File file;
    private final YamlConfiguration yml;

    public DataStore(AventurierPlugin plugin) {
        this.plugin = plugin;
        File folder = plugin.getDataFolder();
        if (!folder.exists() && !folder.mkdirs()) {
            plugin.getLogger().warning("Impossible de créer le dossier du plugin.");
        }
        this.file = new File(folder, "data.yml");
        this.yml = YamlConfiguration.loadConfiguration(file);
    }

    // ---------------------------------------------------------- morts PvP

    public int getPvpDeaths(UUID id) {
        return yml.getInt("pvp-deaths." + id, 0);
    }

    public void setPvpDeaths(UUID id, int value) {
        yml.set("pvp-deaths." + id, value <= 0 ? null : value);
    }

    public void addPvpDeath(UUID id) {
        yml.set("pvp-deaths." + id, getPvpDeaths(id) + 1);
    }

    // ---------------------------------------------------------- bonus de prime (vengeance)

    public double getBounty(UUID id) {
        return yml.getDouble("bounty." + id, 0.0);
    }

    public void setBounty(UUID id, double value) {
        yml.set("bounty." + id, value <= 0 ? null : value);
    }

    // ---------------------------------------------------------- dernier rank connu (pour les joueurs hors ligne)

    public String getLastRank(UUID id) {
        return yml.getString("last-rank." + id);
    }

    public void setLastRank(UUID id, String rank) {
        if (!rank.equals(yml.getString("last-rank." + id))) {
            yml.set("last-rank." + id, rank);
        }
    }

    // ---------------------------------------------------------- skin du PNJ (copie signée d'un compte Mojang)

    public String getSkinValue() {
        return yml.getString("npc-skin.value");
    }

    public String getSkinSignature() {
        return yml.getString("npc-skin.signature");
    }

    public void setSkin(String value, String signature) {
        yml.set("npc-skin.value", value);
        yml.set("npc-skin.signature", signature);
    }

    public void clearSkin() {
        yml.set("npc-skin", null);
    }

    // ---------------------------------------------------------- missions

    public int getMissions(UUID id) {
        return yml.getInt("missions." + id, 0);
    }

    /** @return le nouveau total de missions. */
    public int addMission(UUID id) {
        int value = getMissions(id) + 1;
        yml.set("missions." + id, value);
        return value;
    }

    // ---------------------------------------------------------- chasse

    public UUID getHuntTarget() {
        String raw = yml.getString("hunt.target");
        if (raw == null) return null;
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    public long getHuntRemaining() {
        return yml.getLong("hunt.remaining-ticks", 0L);
    }

    public void setHunt(UUID target, long remainingTicks) {
        if (target == null) {
            yml.set("hunt", null);
        } else {
            yml.set("hunt.target", target.toString());
            yml.set("hunt.remaining-ticks", remainingTicks);
        }
    }

    // ---------------------------------------------------------- glow à retirer à la reconnexion

    public void addPendingUnglow(UUID id) {
        List<String> list = new ArrayList<>(yml.getStringList("pending-unglow"));
        if (!list.contains(id.toString())) {
            list.add(id.toString());
            yml.set("pending-unglow", list);
            save();
        }
    }

    public boolean removePendingUnglow(UUID id) {
        List<String> list = new ArrayList<>(yml.getStringList("pending-unglow"));
        if (list.remove(id.toString())) {
            yml.set("pending-unglow", list.isEmpty() ? null : list);
            save();
            return true;
        }
        return false;
    }

    // ---------------------------------------------------------- IO

    public void save() {
        try {
            yml.save(file);
        } catch (IOException ex) {
            plugin.getLogger().severe("Impossible de sauvegarder data.yml : " + ex.getMessage());
        }
    }
}
