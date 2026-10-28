package fr.deadsmp.aventurier;

import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Locale;
import java.util.UUID;

/** /prime : ce que tu rapportes à quelqu'un qui te tue après t'avoir choisi auprès du PNJ. */
public final class PrimeCommand implements CommandExecutor {

    private final AventurierPlugin plugin;

    public PrimeCommand(AventurierPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            plugin.send(sender, "players-only");
            return true;
        }
        if (!player.hasPermission("deadsmp.use")) {
            plugin.send(player, "no-permission");
            return true;
        }

        UUID id = player.getUniqueId();
        MissionManager missions = plugin.missions();
        String rank = missions.rankOf(player);
        String rankShown = rank.isEmpty() ? rank : Character.toUpperCase(rank.charAt(0)) + rank.substring(1).toLowerCase(Locale.ROOT);
        int deaths = plugin.data().getPvpDeaths(id);

        plugin.send(player, "prime-header",
                Placeholder.unparsed("prime", plugin.formatMoney(missions.prime(id))));
        plugin.send(player, "prime-base",
                Placeholder.unparsed("rank", rankShown),
                Placeholder.unparsed("base", plugin.formatMoney(missions.baseFor(rank))));
        plugin.send(player, "prime-bonus",
                Placeholder.unparsed("bonus", plugin.formatMoney(plugin.data().getBounty(id))));
        plugin.send(player, "prime-percent",
                Placeholder.unparsed("deaths", String.valueOf(deaths)),
                Placeholder.unparsed("percent", String.valueOf(missions.antiFarmPercent(deaths))));
        return true;
    }
}
