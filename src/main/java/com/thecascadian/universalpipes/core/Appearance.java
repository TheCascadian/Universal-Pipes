package com.thecascadian.universalpipes.core;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.thecascadian.universalpipes.UniversalPipes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;

import java.util.Optional;

/**
 * How one pipe looks. It is carried by the settings component and the block
 * entity, never by the block state, so no value here multiplies the state
 * count. The material is stored as a block id and only ever read for its
 * sprite, so a removed block degrades to the default texture. The allow list
 * is a datapack tag rather than a rule such as "any full cube", because a rule
 * cannot exclude animated, translucent or connected-texture blocks.
 */
public record Appearance(int tint, int accent, Optional<ResourceLocation> material, boolean glow) {

    public static final int UNSET = -1;
    public static final int RGB_MASK = 0xFFFFFF;
    public static final Appearance NONE = new Appearance(UNSET, UNSET, Optional.empty(), false);

    public static final TagKey<Block> MATERIALS = TagKey.create(Registries.BLOCK,
            ResourceLocation.fromNamespaceAndPath(UniversalPipes.MODID, "materials"));

    public static final Codec<Appearance> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.INT.optionalFieldOf("tint", UNSET).forGetter(Appearance::tint),
            Codec.INT.optionalFieldOf("accent", UNSET).forGetter(Appearance::accent),
            ResourceLocation.CODEC.optionalFieldOf("material").forGetter(Appearance::material),
            Codec.BOOL.optionalFieldOf("glow", false).forGetter(Appearance::glow))
            .apply(i, Appearance::new));

    public static final StreamCodec<RegistryFriendlyByteBuf, Appearance> STREAM_CODEC = ByteBufCodecs
            .fromCodecWithRegistries(CODEC);

    public Appearance withTint(int value) {
        return new Appearance(value, accent, material, glow);
    }

    public Appearance withAccent(int value) {
        return new Appearance(tint, value, material, glow);
    }

    public Appearance withMaterial(Optional<ResourceLocation> value) {
        return new Appearance(tint, accent, value, glow);
    }

    public Appearance withGlow(boolean value) {
        return new Appearance(tint, accent, material, value);
    }

    /** Whether a block may serve as a material under the current datapack tag. */
    public static boolean allowed(ResourceLocation id) {
        return BuiltInRegistries.BLOCK.getOptional(id).map(block -> block.defaultBlockState().is(MATERIALS))
                .orElse(false);
    }

    /** Server side: colours are masked to RGB and a disallowed material is dropped. */
    public Appearance sanitize(boolean glowAllowed) {
        return new Appearance(mask(tint), mask(accent), material.filter(Appearance::allowed), glow && glowAllowed);
    }

    private static int mask(int colour) {
        return colour == UNSET ? UNSET : colour & RGB_MASK;
    }
}
