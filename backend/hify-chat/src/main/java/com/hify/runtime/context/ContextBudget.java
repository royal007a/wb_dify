package com.hify.runtime.context;

/** A calculable context-window budget. All values are tokens. */
public record ContextBudget(int window, int output, int reserve, int safety) {
    public ContextBudget {
        if (window < 1 || output < 0 || reserve < 0 || safety < 0) {
            throw new IllegalArgumentException("Context budgets cannot be negative");
        }
        if (output + reserve + safety >= window) {
            throw new IllegalArgumentException("Context reserves must leave a positive input budget");
        }
    }

    public int inputLimit() {
        return window - output - reserve - safety;
    }

    public static ContextBudget fromWindow(int window) {
        if (window < 4) return new ContextBudget(window, 0, 0, 0);
        int output = Math.max(1, window / 8);
        int reserve = Math.max(1, window / 10);
        int safety = Math.max(1, window / 20);
        return new ContextBudget(window, output, reserve, safety);
    }
}
