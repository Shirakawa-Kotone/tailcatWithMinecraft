package com.tailscale.mclink;

import java.util.Objects;

/** Client-thread gate for the reentrant old-world teardown inside startConnecting. */
public final class JoinLifecycle {
    private int transitionDepth;
    private boolean connectionAttempted;

    public void beginConnect(Runnable transition) {
        Objects.requireNonNull(transition);
        transitionDepth++;
        try { transition.run(); }
        finally {
            transitionDepth--;
            connectionAttempted = true;
        }
    }

    /** True for a subsequent disconnect, including login failure before a world loads. */
    public boolean shouldDisconnect() { return connectionAttempted && transitionDepth == 0; }
}
