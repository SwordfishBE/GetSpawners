package net.getspawners;

import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.TypedEntityData;
import net.minecraft.world.level.block.entity.BlockEntityType;

import java.util.Optional;

public final class SpawnerItemUtil {
    private SpawnerItemUtil() {
    }

    public static ItemStack createSpawnerItem(EntityType<?> entityType, int amount) {
        ItemStack stack = new ItemStack(Items.SPAWNER, amount);

        CompoundTag blockEntityData = new CompoundTag();
        blockEntityData.put("SpawnData", createSpawnData(entityType));

        ListTag spawnPotentials = new ListTag();
        CompoundTag weightedEntry = new CompoundTag();
        weightedEntry.putInt("weight", 1);
        weightedEntry.put("data", createSpawnData(entityType));
        spawnPotentials.add(weightedEntry);
        blockEntityData.put("SpawnPotentials", spawnPotentials);

        stack.set(DataComponents.BLOCK_ENTITY_DATA, TypedEntityData.of(BlockEntityType.MOB_SPAWNER, blockEntityData));
        return stack;
    }

    public static Optional<EntityType<?>> readEntityTypeFromSpawnerItem(ItemStack stack) {
        TypedEntityData<?> blockEntityData = stack.get(DataComponents.BLOCK_ENTITY_DATA);
        if (blockEntityData == null) {
            return Optional.empty();
        }

        if (blockEntityData.type() != BlockEntityType.MOB_SPAWNER) {
            return Optional.empty();
        }

        return readEntityTypeFromBlockEntityNbt(blockEntityData.copyTagWithoutId());
    }

    public static Optional<EntityType<?>> readEntityTypeFromBlockEntityNbt(CompoundTag blockEntityData) {
        if (blockEntityData == null || !blockEntityData.contains("SpawnData")) {
            return Optional.empty();
        }

        CompoundTag spawnData = blockEntityData.getCompound("SpawnData").orElse(null);
        if (spawnData == null) {
            return Optional.empty();
        }

        CompoundTag entityData = spawnData.getCompound("entity").orElse(null);
        if (entityData == null) {
            return Optional.empty();
        }

        String rawId = entityData.getString("id").orElse("");
        if (rawId.isBlank()) {
            return Optional.empty();
        }

        Identifier id = Identifier.tryParse(rawId);
        if (id == null || !BuiltInRegistries.ENTITY_TYPE.containsKey(id)) {
            return Optional.empty();
        }

        return Optional.of(BuiltInRegistries.ENTITY_TYPE.getValue(id));
    }

    private static CompoundTag createSpawnData(EntityType<?> entityType) {
        CompoundTag entity = new CompoundTag();
        Identifier entityId = BuiltInRegistries.ENTITY_TYPE.getKey(entityType);
        entity.putString("id", entityId.toString());

        CompoundTag spawnData = new CompoundTag();
        spawnData.put("entity", entity);
        return spawnData;
    }
}


