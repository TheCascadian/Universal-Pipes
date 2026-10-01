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

    private static final int SIGNAL_MOVING = 15;
    private static final int SIGNAL_FULL = 10;
    private static final int SIGNAL_FILTERED = 6;
    private static final int SIGNAL_STUCK = 3;

    /** Comparator strength: the better a face is doing, the stronger the signal. */
    public int signal() {
        return switch (this) {
            case TRANSFERRED -> SIGNAL_MOVING;
            case DESTINATION_FULL -> SIGNAL_FULL;
            case FILTERED_OUT -> SIGNAL_FILTERED;
            case NO_DESTINATION, UNLOADED_TARGET -> SIGNAL_STUCK;
            default -> 0;
        };
    }

    public String translationKey() {
        return "gui." + UniversalPipes.MODID + ".status." + name().toLowerCase(java.util.Locale.ROOT);
    }

    public static Status byOrdinal(int ordinal) {
        Status[] values = values();
        return ordinal >= 0 && ordinal < values.length ? values[ordinal] : IDLE;
    }
}
