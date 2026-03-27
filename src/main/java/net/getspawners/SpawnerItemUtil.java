package net.getspawners;

import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.TypedEntityData;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

import java.util.Optional;

public final class SpawnerItemUtil {
    private SpawnerItemUtil() {
    }

    public static ItemStack createSpawnerItem(EntityType<?> entityType, int amount) {
        ItemStack stack = new ItemStack(Items.SPAWNER, amount);

        NbtCompound blockEntityData = new NbtCompound();
        blockEntityData.put("SpawnData", createSpawnData(entityType));

        NbtList spawnPotentials = new NbtList();
        NbtCompound weightedEntry = new NbtCompound();
        weightedEntry.putInt("weight", 1);
        weightedEntry.put("data", createSpawnData(entityType));
        spawnPotentials.add(weightedEntry);
        blockEntityData.put("SpawnPotentials", spawnPotentials);

        stack.set(DataComponentTypes.BLOCK_ENTITY_DATA, TypedEntityData.create(BlockEntityType.MOB_SPAWNER, blockEntityData));
        return stack;
    }

    public static Optional<EntityType<?>> readEntityTypeFromSpawnerItem(ItemStack stack) {
        TypedEntityData<?> blockEntityData = stack.get(DataComponentTypes.BLOCK_ENTITY_DATA);
        if (blockEntityData == null) {
            return Optional.empty();
        }

        if (blockEntityData.getType() != BlockEntityType.MOB_SPAWNER) {
            return Optional.empty();
        }

        return readEntityTypeFromBlockEntityNbt(blockEntityData.copyNbtWithoutId());
    }

    public static Optional<EntityType<?>> readEntityTypeFromBlockEntityNbt(NbtCompound blockEntityData) {
        if (blockEntityData == null || !blockEntityData.contains("SpawnData")) {
            return Optional.empty();
        }

        NbtCompound spawnData = blockEntityData.getCompoundOrEmpty("SpawnData");
        NbtCompound entityData = spawnData.getCompoundOrEmpty("entity");
        String rawId = entityData.getString("id", "");

        Identifier id = Identifier.tryParse(rawId);
        if (id == null || !Registries.ENTITY_TYPE.containsId(id)) {
            return Optional.empty();
        }

        return Optional.of(Registries.ENTITY_TYPE.get(id));
    }

    private static NbtCompound createSpawnData(EntityType<?> entityType) {
        NbtCompound entity = new NbtCompound();
        Identifier entityId = Registries.ENTITY_TYPE.getId(entityType);
        entity.putString("id", entityId.toString());

        NbtCompound spawnData = new NbtCompound();
        spawnData.put("entity", entity);
        return spawnData;
    }
}
