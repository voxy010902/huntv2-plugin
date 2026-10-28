package fr.deadsmp.aventurier;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** Menu du PNJ : la tête de chaque joueur connecté (paginé au-delà de 45 joueurs). */
public final class AventurierMenu implements InventoryHolder {

    static final int SIZE = 54;
    static final int PAGE_SIZE = 45;
    private static final int SLOT_PREV = 45;
    private static final int SLOT_CANCEL = 49;
    private static final int SLOT_NEXT = 53;

    private final AventurierPlugin plugin;
    private final Player viewer;
    private final Inventory inventory;
    private final UUID[] slotTargets = new UUID[PAGE_SIZE];
    private int page = 0;
    private boolean hasNext = false;

    public AventurierMenu(AventurierPlugin plugin, Player viewer) {
        this.plugin = plugin;
        this.viewer = viewer;
        Component title = plugin.mm(plugin.getConfig().getString("menu.title", "<dark_gray>Choisis ta cible"));
        this.inventory = Bukkit.createInventory(this, SIZE, title);
        render();
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    public void open() {
        viewer.openInventory(inventory);
    }

    // ------------------------------------------------------------------ rendu

    private void render() {
        inventory.clear();
        Arrays.fill(slotTargets, null);

        List<Player> candidates = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getUniqueId().equals(viewer.getUniqueId())) continue;
            if (!viewer.canSee(p)) continue; // respecte le vanish
            candidates.add(p);
        }
        candidates.sort(Comparator.comparing(Player::getName, String.CASE_INSENSITIVE_ORDER));

        int maxPage = Math.max(0, (candidates.size() - 1) / PAGE_SIZE);
        if (page > maxPage) page = maxPage;
        int from = page * PAGE_SIZE;
        int to = Math.min(candidates.size(), from + PAGE_SIZE);
        hasNext = to < candidates.size();

        for (int i = from; i < to; i++) {
            int slot = i - from;
            Player target = candidates.get(i);
            slotTargets[slot] = target.getUniqueId();
            inventory.setItem(slot, head(target));
        }

        if (candidates.isEmpty()) {
            inventory.setItem(22, named(Material.PAPER,
                    plugin.getConfig().getString("menu.empty", "<red>Aucun autre joueur n'est connecté")));
        }

        ItemStack filler = named(Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int slot = PAGE_SIZE; slot < SIZE; slot++) inventory.setItem(slot, filler);
        if (page > 0) inventory.setItem(SLOT_PREV, named(Material.ARROW, "<yellow>« Page précédente"));
        if (hasNext) inventory.setItem(SLOT_NEXT, named(Material.ARROW, "<yellow>Page suivante »"));
        if (plugin.missions().getTarget(viewer.getUniqueId()) != null) {
            inventory.setItem(SLOT_CANCEL, named(Material.BARRIER, "<red>Annuler ma mission en cours"));
        }
    }

    private ItemStack head(Player target) {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) item.getItemMeta();
        meta.setOwningPlayer(target);

        double reward = plugin.missions().prime(target.getUniqueId());
        TagResolver resolver = TagResolver.resolver(
                Placeholder.unparsed("player", target.getName()),
                Placeholder.unparsed("reward", plugin.formatMoney(reward)),
                Placeholder.component("rank", plugin.rankDisplay(target)));

        meta.displayName(noItalic(plugin.mm(plugin.getConfig().getString("menu.head-name", "<yellow><player>"), resolver)));
        List<Component> lore = new ArrayList<>();
        for (String line : plugin.getConfig().getStringList("menu.head-lore")) {
            lore.add(noItalic(plugin.mm(line, resolver)));
        }
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack named(Material material, String miniMessage) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(noItalic(plugin.mm(miniMessage)));
        item.setItemMeta(meta);
        return item;
    }

    private static Component noItalic(Component component) {
        return component.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    // ------------------------------------------------------------------ clics

    void click(int slot) {
        if (slot == SLOT_PREV && page > 0) {
            page--;
            render();
            return;
        }
        if (slot == SLOT_NEXT && hasNext) {
            page++;
            render();
            return;
        }
        if (slot == SLOT_CANCEL) {
            if (plugin.missions().getTarget(viewer.getUniqueId()) != null) {
                plugin.missions().clearTarget(viewer);
                Bukkit.getScheduler().runTask(plugin, () -> viewer.closeInventory());
            }
            return;
        }
        if (slot >= 0 && slot < PAGE_SIZE) {
            UUID id = slotTargets[slot];
            if (id == null) return;
            Player target = Bukkit.getPlayer(id);
            if (target == null) {
                plugin.send(viewer, "target-offline-menu");
                render();
                return;
            }
            plugin.missions().setTarget(viewer, target);
            viewer.playSound(viewer.getLocation(), Sound.UI_BUTTON_CLICK, 1.0f, 1.0f);
            Bukkit.getScheduler().runTask(plugin, () -> viewer.closeInventory());
        }
    }
}
