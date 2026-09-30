package com.thecascadian.universalpipes.core;

import com.thecascadian.universalpipes.UniversalPipes;

/** Outcome of the last attempt of one extraction face, shown on the status line. */
public enum Status {
    IDLE,
    TRANSFERRED,
    NO_DESTINATION,
    FILTERED_OUT,
    DESTINATION_FULL,
    REDSTONE_OFF,
    UNLOADED_TARGET,
    BUDGET_DEFERRED;

    public String translationKey() {
        return "gui." + UniversalPipes.MODID + ".status." + name().toLowerCase(java.util.Locale.ROOT);
    }

    public static Status byOrdinal(int ordinal) {
        Status[] values = values();
        return ordinal >= 0 && ordinal < values.length ? values[ordinal] : IDLE;
    }
}
