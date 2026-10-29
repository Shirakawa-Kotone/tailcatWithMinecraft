package com.tailscale.mclink;
import com.mojang.blaze3d.matrix.MatrixStack;
import net.minecraft.client.gui.widget.button.Button;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.StringTextComponent;
import net.minecraft.util.text.TranslationTextComponent;
/** Reuses the upstream copy-invitation / stop-sharing flow. */
public final class ShareScreen extends Screen {
    private final Screen parent;
    private String invite;
    private boolean started;
    private boolean authenticationEnabled = TailcatClient.state().authenticationEnabled();
    private long shareRequest;
    private Button copy;
    private ITextComponent status = new TranslationTextComponent("mclink.starting");
    public ShareScreen(Screen parent) { super(new TranslationTextComponent("mclink.share")); this.parent = parent; }
    @Override protected void init() {
        copy = addButton(LegacyUi.button(new TranslationTextComponent("mclink.copy"), b -> {
            if (invite != null) minecraft.keyboardHandler.setClipboard(invite);
        }).bounds(width / 2 - 102, height / 2 + 42, 100, 20).build());
        copy.active = invite != null;
        addButton(LegacyUi.button(new TranslationTextComponent("mclink.stop"), b -> {
            shareRequest++;
            TailcatClient.state().stop(); onClose();
        }).bounds(width / 2 + 2, height / 2 + 42, 100, 20).build());
        addButton(LegacyUi.button(authenticationLabel(), b -> {
            authenticationEnabled = !authenticationEnabled;
            b.setMessage(authenticationLabel());
            startSharing();
        }).bounds(width / 2 - 130, height / 2 - 20, 260, 20).build());
        if (!started) {
            started = true;
            startSharing();
        }
    }
    private ITextComponent authenticationLabel() {
        return authenticationEnabled ? new TranslationTextComponent("mclink.authentication_on") : new TranslationTextComponent("mclink.authentication_off");
    }
    private void startSharing() {
        long request = ++shareRequest;
        invite = null;
        status = new TranslationTextComponent("mclink.starting");
        copy.active = false;
        TailcatClient.state().share(authenticationEnabled).whenComplete((value, error) -> minecraft.execute(() -> {
            if (request != shareRequest || minecraft.screen != this) return;
            if (error != null) status = new StringTextComponent("Could not share: " + rootMessage(error));
            else { invite = value; status = new TranslationTextComponent("mclink.sharing"); copy.active = true; }
        }));
    }
    @Override public void onClose() { shareRequest++; minecraft.setScreen(parent); }
    @Override public void render(MatrixStack graphics, int mouseX, int mouseY, float delta) {
        fill(graphics, 0, 0, width, height, 0xe0202020);
        super.render(graphics, mouseX, mouseY, delta);
        drawCenteredString(graphics, font, title, width / 2, height / 2 - 88, 0xffffff);
        drawCenteredString(graphics, font, status, width / 2, height / 2 - 64, 0xdddddd);
        if (invite != null) drawCenteredString(graphics, font, shorten(invite), width / 2, height / 2 - 42, 0xaaaaaa);
        drawCenteredString(graphics, font, new TranslationTextComponent("mclink.authentication_rebuild"), width / 2, height / 2 + 8, 0xaaaaaa);
        drawCenteredString(graphics, font, new TranslationTextComponent("mclink.authentication_disconnect"), width / 2, height / 2 + 20, 0xaaaaaa);
    }
    private static String shorten(String value) { return value.length() <= 48 ? value : value.substring(0, 22) + "…" + value.substring(value.length() - 22); }
    static String rootMessage(Throwable error) {
        while (error.getCause() != null) error = error.getCause();
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }
}
