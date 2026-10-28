package fr.deadsmp.aventurier;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

/**
 * DeadSMP - PNJ Aventurier, missions, chasse à l'homme et scoreboard.
 */
public final class AventurierPlugin extends JavaPlugin {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final LegacyComponentSerializer LEGACY =
            LegacyComponentSerializer.builder().character('&').hexColors().build();

    private Economy economy;
    private boolean economyWarned;
    private LuckPermsHook luckPerms;
    private ModelEngineHook modelEngine;
    private DataStore data;
    private MissionManager missions;
    private HuntManager hunt;
    private NpcManager npcs;
    private BoardManager boards;
    private DecimalFormat moneyFormat;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        buildMoneyFormat();
        data = new DataStore(this);

        // --- Hooks optionnels ---
        if (getServer().getPluginManager().getPlugin("LuckPerms") != null) {
            try {
                luckPerms = new LuckPermsHook();
            } catch (Throwable t) {
                getLogger().warning("Impossible de se connecter à LuckPerms : " + t);
            }
        } else {
            getLogger().warning("LuckPerms introuvable : pas de préfixe de rank ni de permissions de palier.");
        }

        if (getServer().getPluginManager().getPlugin("ModelEngine") != null) {
            try {
                modelEngine = new ModelEngineHook(this);
                getLogger().info("ModelEngine détecté : les modèles seront appliqués au PNJ.");
            } catch (Throwable t) {
                getLogger().warning("ModelEngine détecté mais l'API est incompatible : " + t);
            }
        } else {
            getLogger().warning("ModelEngine introuvable : le PNJ sera un simple villageois.");
        }

        // --- Managers ---
        missions = new MissionManager(this);
        boards = new BoardManager(this);
        hunt = new HuntManager(this);
        npcs = new NpcManager(this);

        PluginManager pm = getServer().getPluginManager();
        pm.registerEvents(missions, this);
        pm.registerEvents(boards, this);
        pm.registerEvents(hunt, this);
        pm.registerEvents(npcs, this);
        pm.registerEvents(new MenuListener(), this);

        // --- Commandes ---
        PluginCommand huntCmd = getCommand("hunt");
        if (huntCmd != null) {
            HuntCommand hc = new HuntCommand(this);
            huntCmd.setExecutor(hc);
            huntCmd.setTabCompleter(hc);
        }
        PluginCommand avCmd = getCommand("aventurier");
        if (avCmd != null) {
            AventurierCommand ac = new AventurierCommand(this);
            avCmd.setExecutor(ac);
            avCmd.setTabCompleter(ac);
        }

        PluginCommand primeCmd = getCommand("prime");
        if (primeCmd != null) primeCmd.setExecutor(new PrimeCommand(this));

        npcs.start();
        boards.start();
        hunt.resume();
    }

    @Override
    public void onDisable() {
        if (hunt != null) hunt.shutdown();
        if (boards != null) boards.stop();
        if (data != null) data.save();
    }

    public void reloadAll() {
        reloadConfig();
        buildMoneyFormat();
        missions.reloadSettings();
        npcs.refreshAll();
        boards.refreshAll();
    }

    // ------------------------------------------------------------------ accès

    public DataStore data() { return data; }
    public MissionManager missions() { return missions; }
    public HuntManager hunt() { return hunt; }
    public NpcManager npcs() { return npcs; }
    public BoardManager boards() { return boards; }
    public LuckPermsHook luckPerms() { return luckPerms; }
    public ModelEngineHook modelEngine() { return modelEngine; }

    /** Vault : résolu à la demande, car le plugin d'économie peut s'activer après nous. */
    public Economy economy() {
        if (economy == null) {
            RegisteredServiceProvider<Economy> rsp = getServer().getServicesManager().getRegistration(Economy.class);
            if (rsp != null) {
                economy = rsp.getProvider();
            } else if (!economyWarned) {
                economyWarned = true;
                getLogger().warning("Aucun plugin d'économie compatible Vault trouvé pour l'instant.");
            }
        }
        return economy;
    }

    // ------------------------------------------------------------------ textes

    public Component mm(String raw, TagResolver... resolvers) {
        return MM.deserialize(raw, resolvers);
    }

    public Component msg(String key, TagResolver... resolvers) {
        String raw = getConfig().getString("messages." + key);
        if (raw == null) raw = "<red>Message manquant dans config.yml : " + key;
        return MM.deserialize(raw, TagResolver.resolver(
                TagResolver.resolver(resolvers),
                Placeholder.parsed("prefix", getConfig().getString("messages.prefix", ""))));
    }

    public void send(CommandSender to, String key, TagResolver... resolvers) {
        to.sendMessage(msg(key, resolvers));
    }

    /** Convertit un texte legacy (&a, §a, &#rrggbb) en Component. */
    public Component legacy(String text) {
        return LEGACY.deserialize(text.replace('§', '&'));
    }

    /** Préfixe LuckPerms du joueur (ou texte par défaut). */
    public Component rankDisplay(Player player) {
        String prefix = luckPerms != null ? luckPerms.getPrefix(player) : null;
        if (prefix == null || prefix.isBlank()) {
            prefix = getConfig().getString("scoreboard.no-prefix", "&7Joueur");
        }
        return legacy(prefix.trim());
    }

    // ------------------------------------------------------------------ formats

    private void buildMoneyFormat() {
        DecimalFormatSymbols symbols = new DecimalFormatSymbols(Locale.FRANCE);
        symbols.setGroupingSeparator(' ');
        symbols.setDecimalSeparator(',');
        moneyFormat = new DecimalFormat("#,##0.##", symbols);
    }

    public String formatMoney(double amount) {
        return moneyFormat.format(amount) + getConfig().getString("currency-symbol", "$");
    }

    public String formatPlaytime(long ticks) {
        long totalMinutes = ticks / 20L / 60L;
        long hours = totalMinutes / 60L;
        long minutes = totalMinutes % 60L;
        return hours + "h " + String.format("%02d", minutes) + "m";
    }
}
