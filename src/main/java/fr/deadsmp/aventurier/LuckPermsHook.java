package fr.deadsmp.aventurier;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.cacheddata.CachedMetaData;
import net.luckperms.api.node.Node;
import org.bukkit.entity.Player;

import java.util.UUID;

/** Isolé dans sa propre classe : n'est chargée que si LuckPerms est présent. */
public final class LuckPermsHook {

    private final LuckPerms api = LuckPermsProvider.get();

    /** Préfixe tel que stocké dans LuckPerms (peut contenir des codes &), ou null. */
    public String getPrefix(Player player) {
        CachedMetaData meta = api.getPlayerAdapter(Player.class).getMetaData(player);
        return meta.getPrefix();
    }

    /** Ajoute une permission (true) à l'utilisateur, de façon permanente. */
    public void grantPermission(UUID uuid, String permission) {
        api.getUserManager().modifyUser(uuid, user ->
                user.data().add(Node.builder(permission).value(true).build()));
    }
}
