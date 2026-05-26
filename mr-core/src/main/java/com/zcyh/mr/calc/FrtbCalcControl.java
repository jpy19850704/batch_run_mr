package com.zcyh.mr.calc;

/**
 * FRTB 计量开关上下文。
 * 仅由顶层 frtb_disable 控制。
 */
public final class FrtbCalcControl {
    private static final ThreadLocal<State> STATE_HOLDER = ThreadLocal.withInitial(State::new);

    private FrtbCalcControl() {
    }

    public static void init(boolean frtbDisabled) {
        State state = new State();
        state.frtbDisabled = frtbDisabled;
        STATE_HOLDER.set(state);
    }

    public static boolean isFrtbEnabled() {
        return !STATE_HOLDER.get().frtbDisabled;
    }

    public static boolean isSensitivityEnabled() {
        return isFrtbEnabled();
    }

    public static boolean isDrcEnabled() {
        return isFrtbEnabled();
    }

    public static void clear() {
        STATE_HOLDER.remove();
    }

    private static final class State {
        private boolean frtbDisabled = false;
    }
}
