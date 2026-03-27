package net.getspawners;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;

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

        for (Item item : BuiltInRegistries.ITEM) {
            Identifier itemId = BuiltInRegistries.ITEM.getKey(item);
            if (itemId == null) {
                continue;
            }

            String path = itemId.getPath();
            if (!path.endsWith("_spawn_egg")) {
                continue;
            }

            String entityPath = path.substring(0, path.length() - "_spawn_egg".length());
            Identifier entityId = Identifier.fromNamespaceAndPath(itemId.getNamespace(), entityPath);

            if (!BuiltInRegistries.ENTITY_TYPE.containsKey(entityId)) {
                continue;
            }

            EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getValue(entityId);
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
            Identifier entityId = BuiltInRegistries.ENTITY_TYPE.getKey(entry.getValue());
            if (entityId != null && "minecraft".equals(entityId.getNamespace()) && entityId.getPath().equals(entry.getKey())) {
                keys.add(entry.getKey());
            }
        }

        keys.sort(Comparator.naturalOrder());
        return keys;
    }
}
