package com.tailscale.mclink;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.network.chat.TranslatableComponent;
/** Live sharing controls; navigating back never closes the tunnel. */
public final class ShareScreen extends Screen {
    private final Screen parent;
    private boolean authenticationEnabled = TailcatClient.state().authenticationEnabled();
    private int page;
    private long copiedUntil;
    private Button startStop, copyShort, copyFull, authentication, previous, next;
    public ShareScreen(Screen parent) { super(new TranslatableComponent("mclink.share")); this.parent = parent; }
    private int top() { return Math.max(5, (height - 228) / 2); }
    private int panelWidth() { return Math.min(340, width - 24); }
    @Override protected void init() {
        int left = (width - panelWidth()) / 2, half = (panelWidth() - 4) / 2, y = top();
        previous = addRenderableWidget(LegacyUi.button(new TextComponent("<"), b -> { if (page > 0) page--; })
                .bounds(left, y + 120, 30, 16).build());
        next = addRenderableWidget(LegacyUi.button(new TextComponent(">"), b -> page++)
                .bounds(left + panelWidth() - 30, y + 120, 30, 16).build());
        authentication = addRenderableWidget(LegacyUi.button(authenticationLabel(), b -> {
            authenticationEnabled = !authenticationEnabled;
            b.setMessage(authenticationLabel());
        }).bounds(left, y + 142, panelWidth(), 20).build());
        startStop = addRenderableWidget(LegacyUi.button(new TranslatableComponent("mclink.start_share"), b -> {
            ShareView.Phase phase = TailcatClient.state().shareView().phase();
            if (phase == ShareView.Phase.ON || phase == ShareView.Phase.STARTING) TailcatClient.state().stop();
            else if (phase != ShareView.Phase.STOPPING) TailcatClient.state().share(authenticationEnabled).exceptionally(error -> null);
            updateControls();
        }).bounds(left, y + 166, half, 20).build());
        copyShort = addRenderableWidget(LegacyUi.button(new TranslatableComponent("mclink.copy_short"), b -> copyInvitation(true))
                .bounds(left + half + 4, y + 166, half, 20).build());
        copyFull = addRenderableWidget(LegacyUi.button(new TranslatableComponent("mclink.copy_full"), b -> copyInvitation(false))
                .bounds(left, y + 190, half, 20).build());
        addRenderableWidget(LegacyUi.button(new TranslatableComponent("gui.back"), b -> onClose())
                .bounds(left + half + 4, y + 190, half, 20).build());
        updateControls();
    }
    private Component authenticationLabel() {
        return authenticationEnabled ? new TranslatableComponent("mclink.authentication_on") : new TranslatableComponent("mclink.authentication_off");
    }
    private void copyInvitation(boolean shortened) {
        String invitation = TailcatClient.state().shareView().invitation();
        if (invitation == null) return;
        minecraft.keyboardHandler.setClipboard(shortened ? InviteCode.toShort(invitation) : InviteCode.toFull(invitation));
        copiedUntil = System.currentTimeMillis() + 2000;
    }
    private void updateControls() {
        ShareView view = TailcatClient.state().shareView();
        boolean running = view.phase() == ShareView.Phase.ON, starting = view.phase() == ShareView.Phase.STARTING;
        boolean stopping = view.phase() == ShareView.Phase.STOPPING;
        startStop.setMessage(new TranslatableComponent(starting ? "mclink.cancel_start" : running ? "mclink.stop" : stopping ? "mclink.share_stopping" : "mclink.start_share"));
        startStop.active = !stopping;
        copyShort.active = copyFull.active = running && view.invitation() != null;
        authentication.active = !running && !starting && !stopping;
        if (!authentication.active) {
            authenticationEnabled = TailcatClient.state().authenticationEnabled();
            authentication.setMessage(authenticationLabel());
        }
        int pages = Math.max(1, (view.players().size() + 3) / 4);
        page = Math.min(page, pages - 1);
        previous.visible = next.visible = pages > 1;
        previous.active = page > 0;
        next.active = page + 1 < pages;
    }
    @Override public void tick() { super.tick(); updateControls(); }
    @Override public void onClose() { minecraft.setScreen(parent); }
    @Override public void render(PoseStack graphics, int mouseX, int mouseY, float delta) {
        int y = top(), left = (width - panelWidth()) / 2;
        fill(graphics, 0, 0, width, height, 0xe0121720);
        fill(graphics, left - 6, y - 5, left + panelWidth() + 6, y + 226, 0xe923303e);
        super.render(graphics, mouseX, mouseY, delta);
        ShareView view = TailcatClient.state().shareView();
        drawCenteredString(graphics, font, title, width / 2, y + 2, 0xfff5f8ff);
        int color = view.phase() == ShareView.Phase.ON ? 0xff83e8ad : view.phase() == ShareView.Phase.FAILED ? 0xffff9090 : 0xffe4ce86;
        drawCenteredString(graphics, font, new TranslatableComponent("mclink.share_" + view.phase().name().toLowerCase(java.util.Locale.ROOT)), width / 2, y + 23, color);
        NetworkStatus network = view.network();
        String key = network.isStale() ? "stale" : view.phase() != ShareView.Phase.ON ? "idle" : network.path();
        if (key == null) key = "unknown";
        Component path = new TranslatableComponent("mclink.network_" + key, network.relay() == null ? "?" : network.relay());
        drawCenteredString(graphics, font, new TextComponent(font.plainSubstrByWidth(path.getString(), panelWidth())), width / 2, y + 40, 0xffa8d5eb);
        drawCenteredString(graphics, font, new TranslatableComponent("mclink.players", view.playersKnown() ? Integer.toString(view.players().size()) : "?"), width / 2, y + 60, 0xffe4e9f0);
        if (!view.playersKnown() || view.players().isEmpty()) {
            drawCenteredString(graphics, font, new TranslatableComponent(view.playersKnown() ? "mclink.players_empty" : "mclink.players_loading"), width / 2, y + 83, 0xffaab4c2);
        } else {
            for (int i = page * 4; i < Math.min(view.players().size(), page * 4 + 4); i++) {
                drawCenteredString(graphics, font, new TextComponent(font.plainSubstrByWidth(view.players().get(i), panelWidth())), width / 2, y + 77 + (i % 4) * 10, 0xffd2e0ef);
            }
        }
        if (view.players().size() > 4) {
            drawCenteredString(graphics, font, new TranslatableComponent("mclink.players_page", page + 1, (view.players().size() + 3) / 4), width / 2, y + 124, 0xffaab4c2);
        }
        String footer = System.currentTimeMillis() < copiedUntil ? "mclink.copied" : view.phase() == ShareView.Phase.FAILED ? "mclink.share_failed_help" : "mclink.back_keeps_sharing";
        drawCenteredString(graphics, font, new TranslatableComponent(footer), width / 2, y + 216, 0xffaab4c2);
    }
    static String rootMessage(Throwable error) {
        while (error.getCause() != null) error = error.getCause();
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }
}
