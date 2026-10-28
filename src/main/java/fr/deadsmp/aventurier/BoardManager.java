package fr.deadsmp.aventurier;

import io.papermc.paper.scoreboard.numbers.NumberFormat;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.Statistic;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Score;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.ScoreboardManager;
import org.bukkit.scoreboard.Team;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Scoreboard « DEAD SMP » individuel. Chaque joueur a son propre scoreboard, qui contient aussi
 * une équipe rouge pour la cible de la chasse (c'est ce qui colore le contour lumineux).
 */
public final class BoardManager implements Listener {

    private static final String OBJECTIVE = "dsmp";
    private static final String HUNT_TEAM = "dsmp_hunt";
    private static final String LINE_PREFIX = "line";
    private static final int MAX_LINES = 15;

    private final AventurierPlugin plugin;
    private final Map<UUID, Scoreboard> boards = new HashMap<>();
    private final Map<UUID, List<Component>> lastLines = new HashMap<>();
    private BukkitTask task;

    public BoardManager(AventurierPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        for (Player p : Bukkit.getOnlinePlayers()) create(p);
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::refreshAll, 20L, 20L);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        ScoreboardManager manager = Bukkit.getScoreboardManager();
        if (manager != null) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (boards.containsKey(p.getUniqueId())) p.setScoreboard(manager.getMainScoreboard());
            }
        }
        boards.clear();
        lastLines.clear();
    }

    public void refreshAll() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!boards.containsKey(p.getUniqueId())) create(p);
            else update(p);
        }
    }

    // ------------------------------------------------------------------ création

    private void create(Player player) {
        ScoreboardManager manager = Bukkit.getScoreboardManager();
        if (manager == null) return;

        Scoreboard board = manager.getNewScoreboard();
        Objective objective = board.registerNewObjective(OBJECTIVE, Criteria.DUMMY, Component.text("DEAD SMP"));
        objective.setDisplaySlot(DisplaySlot.SIDEBAR);

        boards.put(player.getUniqueId(), board);
        lastLines.remove(player.getUniqueId());
        player.setScoreboard(board);
        update(player);
    }

    // ------------------------------------------------------------------ mise à jour

    public void update(Player player) {
        Scoreboard board = boards.get(player.getUniqueId());
        if (board == null) return;
        Objective objective = board.getObjective(OBJECTIVE);
        if (objective == null) return;

        objective.displayName(plugin.mm(plugin.getConfig().getString("scoreboard.title", "<red><bold>DEAD SMP")));
        syncHuntTeam(board);

        List<Component> lines = buildLines(player);
        List<Component> previous = lastLines.get(player.getUniqueId());
        int previousSize = previous == null ? 0 : previous.size();

        for (int i = lines.size(); i < previousSize; i++) {
            board.resetScores(LINE_PREFIX + i);
        }
        for (int i = 0; i < lines.size(); i++) {
            boolean unchanged = previous != null && previousSize == lines.size() && previous.get(i).equals(lines.get(i));
            if (unchanged) continue;
            Score score = objective.getScore(LINE_PREFIX + i);
            score.setScore(lines.size() - i);
            score.customName(lines.get(i));
            score.numberFormat(NumberFormat.blank()); // cache les nombres rouges à droite
        }
        lastLines.put(player.getUniqueId(), lines);
    }

    /** Met la cible de la chasse dans une équipe rouge : contour lumineux rouge pour tous. */
    private void syncHuntTeam(Scoreboard board) {
        Team team = board.getTeam(HUNT_TEAM);
        if (team == null) {
            team = board.registerNewTeam(HUNT_TEAM);
            team.color(NamedTextColor.RED);
        }
        String wanted = plugin.hunt().isActive() ? plugin.hunt().targetName() : null;
        for (String entry : new ArrayList<>(team.getEntries())) {
            if (!entry.equals(wanted)) team.removeEntry(entry);
        }
        if (wanted != null && !team.hasEntry(wanted)) team.addEntry(wanted);
    }

    private List<Component> buildLines(Player player) {
        UUID targetId = plugin.missions().getTarget(player.getUniqueId());
        Component targetComponent;
        String reward;
        if (targetId == null) {
            targetComponent = plugin.mm(plugin.getConfig().getString("scoreboard.no-target", "<gray>Aucune"));
            reward = "-";
        } else {
            Player online = Bukkit.getPlayer(targetId);
            String name = online != null ? online.getName() : Bukkit.getOfflinePlayer(targetId).getName();
            if (name == null) name = "???";
            targetComponent = Component.text(name);
            if (online == null) {
                targetComponent = targetComponent.append(plugin.mm(
                        plugin.getConfig().getString("scoreboard.offline-suffix", " <dark_gray>(hors ligne)")));
            }
            reward = plugin.formatMoney(plugin.missions().prime(targetId));
        }

        Economy economy = plugin.economy();
        double balance = economy != null ? economy.getBalance(player) : 0.0;

        TagResolver resolver = TagResolver.resolver(
                Placeholder.component("target", targetComponent),
                Placeholder.unparsed("reward", reward),
                Placeholder.unparsed("playtime", plugin.formatPlaytime(player.getStatistic(Statistic.PLAY_ONE_MINUTE))),
                Placeholder.unparsed("deaths", String.valueOf(player.getStatistic(Statistic.DEATHS))),
                Placeholder.unparsed("money", plugin.formatMoney(balance)),
                Placeholder.unparsed("prime", plugin.formatMoney(plugin.missions().prime(player.getUniqueId()))),
                Placeholder.component("rank", plugin.rankDisplay(player)),
                Placeholder.unparsed("player", player.getName()));

        List<Component> lines = new ArrayList<>();
        for (String raw : plugin.getConfig().getStringList("scoreboard.lines")) {
            if (lines.size() >= MAX_LINES) break;
            lines.add(raw.isBlank() ? Component.text(" ") : plugin.mm(raw, resolver));
        }
        return lines;
    }

    // ------------------------------------------------------------------ événements

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        create(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        boards.remove(event.getPlayer().getUniqueId());
        lastLines.remove(event.getPlayer().getUniqueId());
    }
}
