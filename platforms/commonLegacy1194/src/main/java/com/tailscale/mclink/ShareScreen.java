package com.tailscale.mclink;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
/** Reuses the upstream copy-invitation / stop-sharing flow. */
public final class ShareScreen extends Screen {
    private final Screen parent;
    private String invite;
    private boolean started;
    private boolean authenticationEnabled = TailcatClient.state().authenticationEnabled();
    private long shareRequest;
    private Button copy;
    private Component status = Component.translatable("mclink.starting");
    public ShareScreen(Screen parent) { super(Component.translatable("mclink.share")); this.parent = parent; }
    @Override protected void init() {
        copy = addRenderableWidget(Button.builder(Component.translatable("mclink.copy"), b -> {
            if (invite != null) minecraft.keyboardHandler.setClipboard(invite);
        }).bounds(width / 2 - 102, height / 2 + 42, 100, 20).build());
        copy.active = invite != null;
        addRenderableWidget(Button.builder(Component.translatable("mclink.stop"), b -> {
            shareRequest++;
            TailcatClient.state().stop(); onClose();
        }).bounds(width / 2 + 2, height / 2 + 42, 100, 20).build());
        addRenderableWidget(Button.builder(authenticationLabel(), b -> {
            authenticationEnabled = !authenticationEnabled;
            b.setMessage(authenticationLabel());
            startSharing();
        }).bounds(width / 2 - 130, height / 2 - 20, 260, 20).build());
        if (!started) {
            started = true;
            startSharing();
        }
    }
    private Component authenticationLabel() {
        return authenticationEnabled ? Component.translatable("mclink.authentication_on") : Component.translatable("mclink.authentication_off");
    }
    private void startSharing() {
        long request = ++shareRequest;
        invite = null;
        status = Component.translatable("mclink.starting");
        copy.active = false;
        TailcatClient.state().share(authenticationEnabled).whenComplete((value, error) -> minecraft.execute(() -> {
            if (request != shareRequest || minecraft.screen != this) return;
            if (error != null) status = Component.literal("Could not share: " + rootMessage(error));
            else { invite = value; status = Component.translatable("mclink.sharing"); copy.active = true; }
        }));
    }
    @Override public void onClose() { shareRequest++; minecraft.setScreen(parent); }
    @Override public void render(PoseStack graphics, int mouseX, int mouseY, float delta) {
        fill(graphics, 0, 0, width, height, 0xe0202020);
        super.render(graphics, mouseX, mouseY, delta);
        drawCenteredString(graphics, font, title, width / 2, height / 2 - 88, 0xffffff);
        drawCenteredString(graphics, font, status, width / 2, height / 2 - 64, 0xdddddd);
        if (invite != null) drawCenteredString(graphics, font, shorten(invite), width / 2, height / 2 - 42, 0xaaaaaa);
        drawCenteredString(graphics, font, Component.translatable("mclink.authentication_rebuild"), width / 2, height / 2 + 8, 0xaaaaaa);
        drawCenteredString(graphics, font, Component.translatable("mclink.authentication_disconnect"), width / 2, height / 2 + 20, 0xaaaaaa);
    }
    private static String shorten(String value) { return value.length() <= 48 ? value : value.substring(0, 22) + "…" + value.substring(value.length() - 22); }
    static String rootMessage(Throwable error) {
        while (error.getCause() != null) error = error.getCause();
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }
}
