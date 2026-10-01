package com.thecascadian.universalpipes.registry;

import com.mojang.serialization.Codec;
import com.thecascadian.universalpipes.UniversalPipes;
import com.thecascadian.universalpipes.block.PipeBlock;
import com.thecascadian.universalpipes.item.PipeUpgrade;
import com.thecascadian.universalpipes.block.PipeEntity;
import com.thecascadian.universalpipes.menu.PipeMenu;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.function.Supplier;

/** The mod registers exactly three objects: Pipe (block and item), Pipe Wrench, Pipe Upgrade. */
public final class RegistryHandler {

    private static final float PIPE_HARDNESS = 1.5F;
    private static final float PIPE_RESISTANCE = 6.0F;

    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(UniversalPipes.MODID);
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(UniversalPipes.MODID);
    private static final DeferredRegister<DataComponentType<?>> COMPONENTS = DeferredRegister
            .create(Registries.DATA_COMPONENT_TYPE, UniversalPipes.MODID);
    private static final DeferredRegister<BlockEntityType<?>> ENTITIES = DeferredRegister
            .create(Registries.BLOCK_ENTITY_TYPE, UniversalPipes.MODID);
    private static final DeferredRegister<MenuType<?>> MENUS = DeferredRegister.create(Registries.MENU,
            UniversalPipes.MODID);
    private static final DeferredRegister<CreativeModeTab> TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB,
            UniversalPipes.MODID);

    public static final DeferredBlock<PipeBlock> PIPE = BLOCKS.register("pipe",
            () -> new PipeBlock(BlockBehaviour.Properties.of().mapColor(MapColor.METAL).strength(PIPE_HARDNESS,
                    PIPE_RESISTANCE).sound(SoundType.COPPER).noOcclusion().requiresCorrectToolForDrops()));
    public static final DeferredItem<BlockItem> PIPE_ITEM = ITEMS.register("pipe",
            () -> new BlockItem(PIPE.get(), new Item.Properties()) {
                @Override
                public Component getName(ItemStack stack) {
                    int tier = stack.getOrDefault(TIER_COMPONENT.get(), 1);
                    return tier <= 1 ? super.getName(stack)
                            : Component.translatable("block.universal_pipes.pipe.tiered", tier);
                }
            });
    public static final DeferredItem<Item> PIPE_WRENCH = ITEMS.register("pipe_wrench",
            () -> new Item(new Item.Properties().stacksTo(1)));
    public static final DeferredItem<PipeUpgrade> PIPE_UPGRADE = ITEMS.register("pipe_upgrade",
            () -> new PipeUpgrade(new Item.Properties()));

    public static final Supplier<DataComponentType<Integer>> TIER_COMPONENT = COMPONENTS.register("tier",
            () -> DataComponentType.<Integer>builder().persistent(Codec.intRange(1, PipeBlock.MAX_TIER))
                    .networkSynchronized(ByteBufCodecs.VAR_INT).build());

    public static final Supplier<DataComponentType<PipeEntity.Settings>> SETTINGS = COMPONENTS.register("settings",
            () -> DataComponentType.<PipeEntity.Settings>builder().persistent(PipeEntity.Settings.CODEC)
                    .networkSynchronized(ByteBufCodecs.fromCodecWithRegistries(PipeEntity.Settings.CODEC)).build());

    public static final Supplier<BlockEntityType<PipeEntity>> PIPE_ENTITY = ENTITIES.register("pipe",
            () -> BlockEntityType.Builder.of(PipeEntity::new, PIPE.get()).build(null));
    public static final Supplier<MenuType<PipeMenu>> PIPE_MENU = MENUS.register("pipe",
            () -> IMenuTypeExtension.create(PipeMenu::new));

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> TAB = TABS.register("main",
            () -> CreativeModeTab.builder().title(Component.translatable("itemGroup.universal_pipes"))
                    .icon(() -> PIPE_ITEM.get().getDefaultInstance())
                    .displayItems((parameters, output) -> {
                        output.accept(PIPE_ITEM.get());
                        output.accept(PIPE_WRENCH.get());
                        for (int tier = 2; tier <= PipeBlock.MAX_TIER; tier++)
                            output.accept(PipeUpgrade.create(tier));
                    }).build());

    private RegistryHandler() {
    }

    public static void register(IEventBus modBus) {
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        COMPONENTS.register(modBus);
        ENTITIES.register(modBus);
        MENUS.register(modBus);
        TABS.register(modBus);
    }
}
