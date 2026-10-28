package fr.deadsmp.aventurier;

import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** /hunt [joueur] pour lancer, /hunt stop pour arrêter. */
public final class HuntCommand implements CommandExecutor, TabCompleter {

    private final AventurierPlugin plugin;

    public HuntCommand(AventurierPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("deadsmp.hunt")) {
            plugin.send(sender, "no-permission");
            return true;
        }
        if (args.length == 0) {
            plugin.send(sender, "hunt-usage");
            return true;
        }

        if (args[0].equalsIgnoreCase("stop")) {
            if (!plugin.hunt().isActive()) {
                plugin.send(sender, "hunt-none");
                return true;
            }
            plugin.hunt().stop(sender);
            return true;
        }

        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null) {
            plugin.send(sender, "target-offline", Placeholder.unparsed("target", args[0]));
            return true;
        }
        plugin.hunt().start(sender, target);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length != 1) return List.of();
        String prefix = args[0].toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        if ("stop".startsWith(prefix)) out.add("stop");
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getName().toLowerCase(Locale.ROOT).startsWith(prefix)) out.add(p.getName());
        }
        return out;
    }
}
