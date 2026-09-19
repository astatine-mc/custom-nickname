package kr.seremc.nickname.paper;

import java.util.*;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

/**
 * 변경권 외형과 PDC 고유 ID를 분리해 관리합니다. material, display name, lore는 신뢰하지 않으며 {@code
 * customnicknamebridge:ticket_id}를 프록시에 전달해 DB에서 최종 유효성을 판정합니다.
 */
final class TicketItems {
  private final NamespacedKey key;

  /** Bukkit 플러그인 이름에서 PDC namespace를 만듭니다. 현재 실제 키는 customnicknamebridge:ticket_id입니다. */
  TicketItems(PaperNicknameBridge plugin) {
    key = new NamespacedKey(plugin, "ticket_id");
  }

  /** 이름표에 안내 문구와 UUID 문자열 PDC를 부착합니다. 이름과 lore는 보안 판단에 쓰지 않습니다. */
  ItemStack create(UUID id) {
    ItemStack item = new ItemStack(Material.NAME_TAG);
    ItemMeta meta = item.getItemMeta();
    meta.displayName(Component.text("닉네임 변경권"));
    meta.lore(List.of(Component.text("/닉네임 변경 <새이름>"), Component.text("고유 ID: " + id)));
    meta.getPersistentDataContainer().set(key, PersistentDataType.STRING, id.toString());
    item.setItemMeta(meta);
    return item;
  }

  /** PDC에 저장된 UUID만 파싱합니다. 일반 아이템·PDC 없음·손상된 UUID는 empty로 반환합니다. */
  Optional<UUID> id(ItemStack item) {
    if (item == null || item.getType().isAir() || !item.hasItemMeta()) return Optional.empty();
    String value =
        item.getItemMeta().getPersistentDataContainer().get(key, PersistentDataType.STRING);
    if (value == null) return Optional.empty();
    try {
      return Optional.of(UUID.fromString(value));
    } catch (IllegalArgumentException ignored) {
      return Optional.empty();
    }
  }

  /** 서버 이동·재접속 정리 요청에 사용할 중복 제거된 변경권 ID 목록을 수집합니다. */
  Set<UUID> ids(Player player) {
    Set<UUID> result = new LinkedHashSet<>();
    for (ItemStack item : player.getInventory().getContents()) id(item).ifPresent(result::add);
    id(player.getItemOnCursor()).ifPresent(result::add);
    return result;
  }

  /** 같은 ID를 가진 모든 복제본을 일반 인벤토리, off-hand, cursor에서 제거하고 수량을 반환합니다. */
  int removeAll(Player player, UUID target) {
    int removed = 0;
    ItemStack[] contents = player.getInventory().getContents();
    for (int i = 0; i < contents.length; i++) {
      ItemStack item = contents[i];
      if (id(item).filter(target::equals).isPresent()) {
        removed += item.getAmount();
        contents[i] = null;
      }
    }
    player.getInventory().setContents(contents);
    ItemStack cursor = player.getItemOnCursor();
    if (id(cursor).filter(target::equals).isPresent()) {
      removed += cursor.getAmount();
      player.setItemOnCursor(null);
    }
    return removed;
  }
}
