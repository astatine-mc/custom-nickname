package kr.seremc.nickname.paper;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.*;

final class TicketItems {
    private final NamespacedKey key;
    TicketItems(PaperNicknameBridge plugin) { key = new NamespacedKey(plugin, "ticket_id"); }

    ItemStack create(UUID id) {
        ItemStack item = new ItemStack(Material.NAME_TAG);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("닉네임 변경권"));
        meta.lore(List.of(Component.text("/닉네임 변경 <새이름>"), Component.text("고유 ID: " + id)));
        meta.getPersistentDataContainer().set(key, PersistentDataType.STRING, id.toString());
        item.setItemMeta(meta);
        return item;
    }

    Optional<UUID> id(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) return Optional.empty();
        String value = item.getItemMeta().getPersistentDataContainer().get(key, PersistentDataType.STRING);
        if (value == null) return Optional.empty();
        try { return Optional.of(UUID.fromString(value)); } catch (IllegalArgumentException ignored) { return Optional.empty(); }
    }

    Set<UUID> ids(Player player) {
        Set<UUID> result = new LinkedHashSet<>();
        for (ItemStack item : player.getInventory().getContents()) id(item).ifPresent(result::add);
        id(player.getItemOnCursor()).ifPresent(result::add);
        return result;
    }

    int removeAll(Player player, UUID target) {
        int removed = 0;
        ItemStack[] contents = player.getInventory().getContents();
        for (int i = 0; i < contents.length; i++) {
            ItemStack item = contents[i];
            if (id(item).filter(target::equals).isPresent()) { removed += item.getAmount(); contents[i] = null; }
        }
        player.getInventory().setContents(contents);
        ItemStack cursor = player.getItemOnCursor();
        if (id(cursor).filter(target::equals).isPresent()) { removed += cursor.getAmount(); player.setItemOnCursor(null); }
        return removed;
    }
}
