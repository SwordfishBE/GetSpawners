package net.getspawners;

import net.minecraft.entity.EntityType;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

public final class SpawnerTypeRegistry {
    private final Map<String, EntityType<?>> byKey;

    private SpawnerTypeRegistry(Map<String, EntityType<?>> byKey) {
        this.byKey = byKey;
    }

    public static SpawnerTypeRegistry create() {
        Map<String, EntityType<?>> map = new LinkedHashMap<>();

        for (Item item : Registries.ITEM) {
            Identifier itemId = Registries.ITEM.getId(item);
            if (itemId == null) {
                continue;
            }

            String path = itemId.getPath();
            if (!path.endsWith("_spawn_egg")) {
                continue;
            }

            String entityPath = path.substring(0, path.length() - "_spawn_egg".length());
            Identifier entityId = Identifier.of(itemId.getNamespace(), entityPath);

            if (!Registries.ENTITY_TYPE.containsId(entityId)) {
                continue;
            }

            EntityType<?> type = Registries.ENTITY_TYPE.get(entityId);
            map.put(entityId.toString().toLowerCase(Locale.ROOT), type);

            if ("minecraft".equals(entityId.getNamespace())) {
                map.put(entityId.getPath().toLowerCase(Locale.ROOT), type);
            }
        }

        return new SpawnerTypeRegistry(map);
    }

    public Optional<EntityType<?>> resolve(String input) {
        if (input == null || input.isBlank()) {
            return Optional.empty();
        }

        return Optional.ofNullable(byKey.get(input.toLowerCase(Locale.ROOT)));
    }

    public List<String> keys() {
        List<String> keys = new ArrayList<>();

        for (Map.Entry<String, EntityType<?>> entry : byKey.entrySet()) {
            Identifier entityId = Registries.ENTITY_TYPE.getId(entry.getValue());
            if (entityId != null && "minecraft".equals(entityId.getNamespace()) && entityId.getPath().equals(entry.getKey())) {
                keys.add(entry.getKey());
            }
        }

        keys.sort(Comparator.naturalOrder());
        return keys;
    }
}
