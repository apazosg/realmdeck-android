package com.wowforever.runtimeprobe;

import java.util.concurrent.atomic.AtomicBoolean;

/** One-shot terminal gate shared by process callbacks, timeout, and destruction. */
public final class ProbeTerminalState {
    private final AtomicBoolean finished = new AtomicBoolean(false);

    public boolean compareAndSet(boolean expected, boolean update) {
        return finished.compareAndSet(expected, update);
    }
}
