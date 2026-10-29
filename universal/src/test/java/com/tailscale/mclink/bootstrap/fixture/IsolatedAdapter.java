package com.tailscale.mclink.bootstrap.fixture;

import com.tailscale.mclink.Platform;

public final class IsolatedAdapter {
    public IsolatedAdapter() {
        System.setProperty("tailcat.fixture.constructor", Platform.parse("Linux", "amd64").resourceDirectory());
        int count = Integer.parseInt(System.getProperty("tailcat.fixture.instances", "0"));
        System.setProperty("tailcat.fixture.instances", Integer.toString(count + 1));
    }
    public void onInitializeClient() { System.setProperty("tailcat.fixture.fabric", "initialized"); }
}
