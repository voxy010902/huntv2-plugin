package fr.deadsmp.aventurier;

import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiConsumer;

/**
 * Pont vers ModelEngine R4, entièrement par réflexion :
 * aucun jar ModelEngine n'est nécessaire pour compiler, et une petite différence
 * d'API ne fait pas planter le plugin.
 */
public final class ModelEngineHook {

    private static final String API = "com.ticxo.modelengine.api.ModelEngineAPI";
    private static final String MODELED_ENTITY = "com.ticxo.modelengine.api.model.ModeledEntity";
    private static final String ACTIVE_MODEL = "com.ticxo.modelengine.api.model.ActiveModel";
    private static final String INTERACT_EVENT = "com.ticxo.modelengine.api.events.BaseEntityInteractEvent";

    private final AventurierPlugin plugin;
    private final ClassLoader loader;
    private final Method getOrCreateModeledEntity;
    private final Method createActiveModel;
    private final Method addModel;
    private final Method getModel;            // optionnel
    private final Method setBaseEntityVisible; // optionnel

    public ModelEngineHook(AventurierPlugin plugin) throws ReflectiveOperationException {
        this.plugin = plugin;
        Plugin me = Bukkit.getPluginManager().getPlugin("ModelEngine");
        if (me == null) throw new ClassNotFoundException("ModelEngine absent");
        this.loader = me.getClass().getClassLoader();

        Class<?> api = Class.forName(API, true, loader);
        Class<?> modeledEntity = Class.forName(MODELED_ENTITY, true, loader);
        Class<?> activeModel = Class.forName(ACTIVE_MODEL, true, loader);

        this.getOrCreateModeledEntity = api.getMethod("getOrCreateModeledEntity", Entity.class);
        this.createActiveModel = api.getMethod("createActiveModel", String.class);
        this.addModel = modeledEntity.getMethod("addModel", activeModel, boolean.class);
        this.getModel = optional(modeledEntity, "getModel", String.class);
        this.setBaseEntityVisible = optional(modeledEntity, "setBaseEntityVisible", boolean.class);
    }

    /**
     * Applique le modèle à l'entité (idempotent : ne fait rien s'il est déjà là).
     *
     * @return true si le modèle est présent à la fin de l'appel
     */
    public boolean apply(Entity entity, String modelId, boolean hideBaseEntity) {
        try {
            Object modeled = getOrCreateModeledEntity.invoke(null, entity);
            if (modeled == null) return false;
            if (hasModel(modeled, modelId)) return true;

            Object active = createActiveModel.invoke(null, modelId);
            addModel.invoke(modeled, active, true);
            if (hideBaseEntity && setBaseEntityVisible != null) {
                setBaseEntityVisible.invoke(modeled, false);
            }
            return true;
        } catch (InvocationTargetException ex) {
            Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
            plugin.getLogger().warning("ModelEngine : impossible d'appliquer le modèle '" + modelId
                    + "' (le nom correspond-il bien à ton fichier .bbmodel ?) : " + cause);
            return false;
        } catch (ReflectiveOperationException | RuntimeException ex) {
            plugin.getLogger().warning("ModelEngine : erreur inattendue : " + ex);
            return false;
        }
    }

    private boolean hasModel(Object modeled, String modelId) throws ReflectiveOperationException {
        if (getModel == null) return false;
        Object result = getModel.invoke(modeled, modelId);
        if (result instanceof Optional<?> opt) return opt.isPresent();
        return result != null;
    }

    /**
     * Écoute BaseEntityInteractEvent (clic sur la hitbox du modèle) sans dépendance de compilation.
     * Le handler reçoit le joueur et l'entité de base (Bukkit) touchée.
     */
    public void registerInteract(Listener holder, BiConsumer<Player, Entity> handler) {
        try {
            Class<? extends Event> eventClass = Class.forName(INTERACT_EVENT, true, loader).asSubclass(Event.class);
            Method getPlayer = eventClass.getMethod("getPlayer");
            Method getBase = eventClass.getMethod("getBaseEntity");
            Method getAction = optional(eventClass, "getAction");
            Class<?> baseClass = getBase.getReturnType();
            Method getOriginal = optional(baseClass, "getOriginal");
            Method getUuid = optional(baseClass, "getUUID");

            plugin.getServer().getPluginManager().registerEvent(eventClass, holder, EventPriority.NORMAL,
                    (listener, event) -> {
                        if (!eventClass.isInstance(event)) return;
                        try {
                            if (getAction != null) {
                                Object action = getAction.invoke(event);
                                // On ignore les coups (clic gauche) : seul le clic droit ouvre le menu
                                if (action != null && action.toString().toUpperCase(Locale.ROOT).contains("ATTACK")) return;
                            }
                            Object player = getPlayer.invoke(event);
                            Object base = getBase.invoke(event);
                            Entity entity = resolveEntity(base, getOriginal, getUuid);
                            if (player instanceof Player p && entity != null) {
                                handler.accept(p, entity);
                            }
                        } catch (ReflectiveOperationException | RuntimeException ex) {
                            plugin.getLogger().warning("ModelEngine : lecture du clic impossible : " + ex);
                        }
                    }, plugin);
        } catch (ReflectiveOperationException | RuntimeException ex) {
            plugin.getLogger().warning("ModelEngine : écoute des clics sur la hitbox impossible ("
                    + ex + "). Le clic classique sur le PNJ reste actif.");
        }
    }

    private static Entity resolveEntity(Object base, Method getOriginal, Method getUuid)
            throws ReflectiveOperationException {
        if (base == null) return null;
        if (getOriginal != null) {
            Object original = getOriginal.invoke(base);
            if (original instanceof Entity entity) return entity;
        }
        if (getUuid != null) {
            Object uuid = getUuid.invoke(base);
            if (uuid instanceof UUID id) return Bukkit.getEntity(id);
        }
        return null;
    }

    private static Method optional(Class<?> type, String name, Class<?>... params) {
        try {
            return type.getMethod(name, params);
        } catch (NoSuchMethodException ex) {
            return null;
        }
    }
}
