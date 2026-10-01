package com.thecascadian.universalpipes.client;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.thecascadian.universalpipes.block.PipeEntity;
import com.thecascadian.universalpipes.core.Appearance;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.model.BakedModelWrapper;
import net.neoforged.neoforge.client.model.data.ModelData;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Wraps the multipart pipe model and, when the block entity reports a
 * material, moves every quad onto that block's sprite. Rewriting quads at mesh
 * time keeps the blockstate and the number of baked models unchanged. The
 * alternative of baking one model per material was rejected because the count
 * would follow the number of materials in use times 3645 states.
 */
public class PipeModel extends BakedModelWrapper<BakedModel> {

    private static final int STRIDE = DefaultVertexFormat.BLOCK.getIntegerSize();
    private static final int U_OFFSET = 4;
    private static final int V_OFFSET = 5;
    private static final Map<ResourceLocation, TextureAtlasSprite> SPRITES = new ConcurrentHashMap<>();

    public PipeModel(BakedModel original) {
        super(original);
    }

    /** Called after every model bake, as the atlas and its sprites are rebuilt. */
    public static void clearSprites() {
        SPRITES.clear();
    }

    @Override
    public List<BakedQuad> getQuads(BlockState state, Direction side, RandomSource random, ModelData data,
            RenderType renderType) {
        List<BakedQuad> quads = super.getQuads(state, side, random, data, renderType);
        TextureAtlasSprite sprite = sprite(data);
        if (sprite == null)
            return quads;
        return quads.stream().map(quad -> remap(quad, sprite)).toList();
    }

    @Override
    public TextureAtlasSprite getParticleIcon(ModelData data) {
        TextureAtlasSprite sprite = sprite(data);
        return sprite != null ? sprite : super.getParticleIcon(data);
    }

    private static TextureAtlasSprite sprite(ModelData data) {
        Appearance appearance = data.get(PipeEntity.APPEARANCE_PROPERTY);
        if (appearance == null || appearance.material().isEmpty())
            return null;
        ResourceLocation id = appearance.material().get();
        TextureAtlasSprite cached = SPRITES.get(id);
        if (cached != null)
            return cached;
        Block block = BuiltInRegistries.BLOCK.getOptional(id).orElse(null);
        if (block == null)
            return null;
        TextureAtlasSprite found = Minecraft.getInstance().getBlockRenderer().getBlockModelShaper()
                .getParticleIcon(block.defaultBlockState());
        SPRITES.put(id, found);
        return found;
    }

    /** Keeps each vertex at the same relative position inside the sprite, so sub-regions survive. */
    private static BakedQuad remap(BakedQuad quad, TextureAtlasSprite target) {
        TextureAtlasSprite source = quad.getSprite();
        int[] vertices = quad.getVertices().clone();
        for (int vertex = 0; vertex < vertices.length / STRIDE; vertex++) {
            int u = vertex * STRIDE + U_OFFSET;
            int v = vertex * STRIDE + V_OFFSET;
            vertices[u] = Float.floatToRawIntBits(
                    move(Float.intBitsToFloat(vertices[u]), source.getU0(), source.getU1(), target.getU0(), target.getU1()));
            vertices[v] = Float.floatToRawIntBits(
                    move(Float.intBitsToFloat(vertices[v]), source.getV0(), source.getV1(), target.getV0(), target.getV1()));
        }
        return new BakedQuad(vertices, quad.getTintIndex(), quad.getDirection(), target, quad.isShade());
    }

    private static float move(float value, float from0, float from1, float to0, float to1) {
        float span = from1 - from0;
        float relative = span == 0.0F ? 0.0F : (value - from0) / span;
        return to0 + relative * (to1 - to0);
    }
}
