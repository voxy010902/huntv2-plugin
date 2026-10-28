package fr.deadsmp.aventurier;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** /aventurier spawn [modèle] | remove | reload | resetdeaths [joueur] */
public final class AventurierCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBCOMMANDS = List.of("spawn", "remove", "reload", "skin", "resetdeaths");

    private final AventurierPlugin plugin;

    public AventurierCommand(AventurierPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("deadsmp.admin")) {
            plugin.send(sender, "no-permission");
            return true;
        }
        if (args.length == 0) {
            plugin.send(sender, "aventurier-usage");
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "spawn" -> {
                if (!(sender instanceof Player player)) {
                    plugin.send(sender, "players-only");
                    return true;
                }
                String model = args.length > 1 ? args[1] : plugin.getConfig().getString("npc.model-id", "");
                plugin.npcs().spawn(player.getLocation(), model);
                plugin.send(sender, "npc-spawned");
            }
            case "remove" -> {
                if (!(sender instanceof Player player)) {
                    plugin.send(sender, "players-only");
                    return true;
                }
                Entity npc = plugin.npcs().findNearest(player, 5.0);
                if (npc == null) {
                    plugin.send(sender, "npc-none-nearby");
                } else {
                    npc.remove();
                    plugin.send(sender, "npc-removed");
                }
            }
            case "reload" -> {
                plugin.reloadAll();
                plugin.send(sender, "reloaded");
            }
            case "skin" -> {
                if (args.length < 2) {
                    plugin.send(sender, "aventurier-usage");
                    return true;
                }
                if (args[1].equalsIgnoreCase("reset")) {
                    plugin.data().clearSkin();
                    plugin.data().save();
                    plugin.npcs().refreshAll();
                    plugin.send(sender, "skin-reset");
                    return true;
                }
                fetchSkin(sender, args[1]);
            }
            case "resetdeaths" -> {
                if (args.length < 2) {
                    plugin.send(sender, "aventurier-usage");
                    return true;
                }
                OfflinePlayer target = Bukkit.getOfflinePlayerIfCached(args[1]);
                if (target == null) {
                    plugin.send(sender, "unknown-player", Placeholder.unparsed("target", args[1]));
                    return true;
                }
                plugin.data().setPvpDeaths(target.getUniqueId(), 0);
                plugin.data().save();
                plugin.send(sender, "deaths-reset", Placeholder.unparsed("target", args[1]));
            }
            default -> plugin.send(sender, "aventurier-usage");
        }
        return true;
    }

    /** Copie le skin signé actuel d'un compte Mojang : il reste sur le PNJ même si le compte change de skin. */
    private void fetchSkin(CommandSender sender, String name) {
        plugin.send(sender, "skin-fetching", Placeholder.unparsed("target", name));
        PlayerProfile profile = Bukkit.createProfile(name);
        profile.update().whenComplete((updated, error) ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    ProfileProperty textures = (error == null && updated != null) ? findTextures(updated) : null;
                    if (textures == null) {
                        plugin.send(sender, "skin-failed", Placeholder.unparsed("target", name));
                        return;
                    }
                    plugin.data().setSkin(textures.getValue(), textures.getSignature());
                    plugin.data().save();
                    plugin.npcs().refreshAll();
                    plugin.send(sender, "skin-saved", Placeholder.unparsed("target", name));
                }));
    }

    private static ProfileProperty findTextures(PlayerProfile profile) {
        for (ProfileProperty property : profile.getProperties()) {
            if ("textures".equals(property.getName())) return property;
        }
        return null;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            for (String sub : SUBCOMMANDS) if (sub.startsWith(prefix)) out.add(sub);
        } else if (args.length == 2 && (args[0].equalsIgnoreCase("resetdeaths") || args[0].equalsIgnoreCase("skin"))) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            if (args[0].equalsIgnoreCase("skin") && "reset".startsWith(prefix)) out.add("reset");
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getName().toLowerCase(Locale.ROOT).startsWith(prefix)) out.add(p.getName());
            }
        }
        return out;
    }
}
