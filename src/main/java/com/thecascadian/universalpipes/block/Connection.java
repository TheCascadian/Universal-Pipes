package com.thecascadian.universalpipes.block;

import net.minecraft.util.StringRepresentable;

/**
 * Per-face state. Three values keep the blockstate a pure function of the
 * neighbourhood: a fourth "disabled by wrench" value would be indistinguishable
 * from NONE for rendering, so it is stored in the endpoint data instead.
 */
public enum Connection implements StringRepresentable {
    NONE("none"),
    CONNECTED("connected"),
    ENDPOINT("endpoint");

    private final String name;

    Connection(String name) {
        this.name = name;
    }

    public boolean isOpen() {
        return this != NONE;
    }

    @Override
    public String getSerializedName() {
        return name;
    }
}
