package fr.deadsmp.aventurier;

import com.destroystokyo.paper.profile.ProfileProperty;
import io.papermc.paper.datacomponent.item.ResolvableProfile;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.persistence.PersistentDataType;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Gère le PNJ Aventurier : création, skin (Mannequin), modèle ModelEngine optionnel,
 * clic et protections.
 */
public final class NpcManager implements Listener {

    private static final UUID SKIN_UUID = UUID.nameUUIDFromBytes("deadsmp-aventurier".getBytes(StandardCharsets.UTF_8));

    private final AventurierPlugin plugin;
    private final NamespacedKey npcKey;
    private final NamespacedKey modelKey;
    private final Map<UUID, Long> lastClick = new HashMap<>();

    public NpcManager(AventurierPlugin plugin) {
        this.plugin = plugin;
        this.npcKey = new NamespacedKey(plugin, "aventurier_npc");
        this.modelKey = new NamespacedKey(plugin, "aventurier_model");
    }

    /** À appeler une fois les managers prêts. */
    public void start() {
        ModelEngineHook me = plugin.modelEngine();
        if (me != null) {
            me.registerInteract(this, this::handleModelClick);
        }
        // ModelEngine charge ses modèles de façon asynchrone au démarrage : on réessaie plus tard.
        Bukkit.getScheduler().runTaskLater(plugin, this::refreshAll, 100L);
        Bukkit.getScheduler().runTaskLater(plugin, this::refreshAll, 600L);
    }

    public boolean isNpc(Entity entity) {
        return entity.getPersistentDataContainer().has(npcKey, PersistentDataType.BYTE);
    }

    // ------------------------------------------------------------------ création / suppression

    public Entity spawn(Location where, String modelId) {
        Location loc = where.clone();
        loc.setYaw(loc.getYaw() + 180f); // le PNJ regarde le joueur
        loc.setPitch(0f);
        World world = loc.getWorld();

        Entity entity;
        try {
            entity = world.spawnEntity(loc, baseType());
        } catch (IllegalArgumentException ex) {
            plugin.getLogger().warning("npc.base-entity invalide, villageois utilisé à la place.");
            entity = world.spawnEntity(loc, EntityType.VILLAGER);
        }

        entity.setPersistent(true);
        entity.setInvulnerable(true);
        entity.setSilent(true);
        entity.customName(plugin.mm(plugin.getConfig().getString("npc.name", "<gold><bold>Aventurier")));
        entity.setCustomNameVisible(plugin.getConfig().getBoolean("npc.show-name", true));
        if (entity instanceof LivingEntity living) {
            living.setAI(false);
            living.setRemoveWhenFarAway(false);
            living.setCollidable(false);
        }
        entity.getPersistentDataContainer().set(npcKey, PersistentDataType.BYTE, (byte) 1);
        if (modelId != null && !modelId.isBlank()) {
            entity.getPersistentDataContainer().set(modelKey, PersistentDataType.STRING, modelId);
        }

        refresh(entity);
        return entity;
    }

    public Entity findNearest(Player player, double radius) {
        Entity best = null;
        double bestDist = Double.MAX_VALUE;
        for (Entity e : player.getNearbyEntities(radius, radius, radius)) {
            if (!isNpc(e)) continue;
            double d = e.getLocation().distanceSquared(player.getLocation());
            if (d < bestDist) {
                bestDist = d;
                best = e;
            }
        }
        return best;
    }

    private EntityType baseType() {
        String name = plugin.getConfig().getString("npc.base-entity", "mannequin");
        try {
            EntityType type = Registry.ENTITY_TYPE.get(NamespacedKey.minecraft(name.toLowerCase(Locale.ROOT)));
            if (type != null) return type;
        } catch (IllegalArgumentException ignored) {
            // nom invalide : on retombe sur le villageois
        }
        return EntityType.VILLAGER;
    }

    // ------------------------------------------------------------------ apparence

    /** Applique (ou réapplique) le skin et le modèle ModelEngine éventuel. */
    public void refresh(Entity entity) {
        applySkin(entity);
        ensureModel(entity);
    }

    public void refreshAll() {
        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (isNpc(entity)) refresh(entity);
            }
        }
    }

    /** Skin signé (MineSkin) sur un Mannequin. Sans valeur dans le config : skin par défaut. */
    private void applySkin(Entity entity) {
        if (!(entity instanceof Mannequin mannequin)) return;

        mannequin.setImmovable(true);
        String description = plugin.getConfig().getString("npc.description", "");
        mannequin.setDescription(description.isBlank() ? null : plugin.mm(description));

        // 1) copie enregistrée avec /aventurier skin <pseudo>, 2) sinon valeurs du config.yml
        String value = plugin.data().getSkinValue();
        String signature = plugin.data().getSkinSignature();
        if (value == null || value.isBlank()) {
            value = plugin.getConfig().getString("npc.skin.value", "");
            signature = plugin.getConfig().getString("npc.skin.signature", "");
        }
        if (value == null || value.isBlank()) return;
        if (signature == null) signature = "";

        ResolvableProfile profile = ResolvableProfile.resolvableProfile()
                .uuid(SKIN_UUID)
                .name("Aventurier")
                .addProperty(new ProfileProperty("textures", value.trim(),
                        signature.isBlank() ? null : signature.trim()))
                .build();
        mannequin.setProfile(profile);
    }

    private void ensureModel(Entity entity) {
        ModelEngineHook me = plugin.modelEngine();
        if (me == null) return;
        String model = entity.getPersistentDataContainer().get(modelKey, PersistentDataType.STRING);
        if (model == null || model.isBlank()) model = plugin.getConfig().getString("npc.model-id", "");
        if (model == null || model.isBlank()) return; // pas de modèle ModelEngine : on garde le skin
        me.apply(entity, model, plugin.getConfig().getBoolean("npc.hide-base-entity", true));
    }

    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        for (Entity entity : event.getEntities()) {
            if (isNpc(entity)) {
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    if (entity.isValid()) refresh(entity);
                }, 20L);
            }
        }
    }

    // ------------------------------------------------------------------ clics

    /** Clic « classique » sur l'entité de base. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEntityEvent event) {
        Entity clicked = event.getRightClicked();
        if (!isNpc(clicked)) return;
        event.setCancelled(true); // pas de fenêtre de commerce du villageois
        if (event.getHand() != EquipmentSlot.HAND) return;
        handleClick(event.getPlayer());
    }

    /** Clic sur la hitbox du modèle ModelEngine. */
    private void handleModelClick(Player player, Entity entity) {
        if (!isNpc(entity)) return;
        handleClick(player);
    }

    private void handleClick(Player player) {
        long now = System.currentTimeMillis();
        Long last = lastClick.get(player.getUniqueId());
        if (last != null && now - last < 400L) return; // les deux sources de clic peuvent se doubler
        lastClick.put(player.getUniqueId(), now);

        // Peut venir d'un autre thread (paquets) : on repasse sur le thread principal
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) return;
            if (!player.hasPermission("deadsmp.use")) {
                plugin.send(player, "no-permission");
                return;
            }
            new AventurierMenu(plugin, player).open();
        });
    }

    // ------------------------------------------------------------------ protections

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDamage(EntityDamageEvent event) {
        if (isNpc(event.getEntity())) event.setCancelled(true); // même les joueurs en créatif
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        lastClick.remove(event.getPlayer().getUniqueId());
    }
}
