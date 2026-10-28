package fr.deadsmp.aventurier;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;

public final class MenuListener implements Listener {

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof AventurierMenu menu)) return;
        event.setCancelled(true); // rien ne sort du menu, shift-clic compris
        int raw = event.getRawSlot();
        if (raw < 0 || raw >= AventurierMenu.SIZE) return; // clic dans l'inventaire du joueur
        menu.click(raw);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof AventurierMenu) {
            event.setCancelled(true);
        }
    }
}
