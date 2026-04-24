package com.protogemcouch.health;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public class HealthState {

    private final AtomicBoolean live = new AtomicBoolean(false);
    private final AtomicBoolean ready = new AtomicBoolean(false);
    private final AtomicReference<String> status = new AtomicReference<>("starting");

    public boolean isLive() {
        return live.get();
    }

    public boolean isReady() {
        return ready.get();
    }

    public String getStatus() {
        return status.get();
    }

    public void markStarting() {
        live.set(true);
        ready.set(false);
        status.set("starting");
    }

    public void markConfigValidated() {
        live.set(true);
        ready.set(false);
        status.set("config_validated");
    }

    public void markRepositoryConnected() {
        live.set(true);
        ready.set(false);
        status.set("repository_connected");
    }

    public void markServerBound() {
        live.set(true);
        ready.set(true);
        status.set("ready");
    }

    public void markStopping() {
        live.set(true);
        ready.set(false);
        status.set("stopping");
    }

    public void markStopped() {
        live.set(false);
        ready.set(false);
        status.set("stopped");
    }

    public void markStartupFailed(String reason) {
        live.set(false);
        ready.set(false);
        status.set(reason == null || reason.isBlank() ? "startup_failed" : "startup_failed:" + reason);
    }
}