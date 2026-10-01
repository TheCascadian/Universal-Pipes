package com.thecascadian.universalpipes.filter;

import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.resources.ResourceLocation;

import java.util.Set;

/**
 * A stand-in for a stack that needs no registry bootstrap. components() and
 * patch() return null, so tests must not use ?has or ?eq against it. The
 * result cache is keyed by typeKey, which here is the whole record, so two
 * subjects with the same id but different flags never share a cached result.
 */
record TestSubject(ResourceLocation id, Set<ResourceLocation> tags, String name, boolean enchanted, boolean damaged,
        int durabilityLeft) implements Expr.Subject {

    static TestSubject of(String id) {
        return new TestSubject(ResourceLocation.parse(id), Set.of(), "", false, false, -1);
    }

    TestSubject withTag(String tag) {
        return new TestSubject(id, Set.of(ResourceLocation.parse(tag)), name, enchanted, damaged, durabilityLeft);
    }

    TestSubject withName(String value) {
        return new TestSubject(id, tags, value, enchanted, damaged, durabilityLeft);
    }

    TestSubject withEnchanted(boolean value) {
        return new TestSubject(id, tags, name, value, damaged, durabilityLeft);
    }

    TestSubject withDamaged(boolean value) {
        return new TestSubject(id, tags, name, enchanted, value, durabilityLeft);
    }

    TestSubject withDurability(int value) {
        return new TestSubject(id, tags, name, enchanted, damaged, value);
    }

    @Override
    public boolean inTag(ResourceLocation tag) {
        return tags.contains(tag);
    }

    @Override
    public DataComponentMap components() {
        return null;
    }

    @Override
    public Object typeKey() {
        return this;
    }

    @Override
    public DataComponentPatch patch() {
        return null;
    }
}
