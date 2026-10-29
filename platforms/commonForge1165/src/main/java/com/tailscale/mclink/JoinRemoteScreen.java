package com.tailscale.mclink;
import com.mojang.blaze3d.matrix.MatrixStack;
import net.minecraft.client.gui.widget.button.Button;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.StringTextComponent;
import net.minecraft.util.text.TranslationTextComponent;
/** Reuses the upstream invitation input and loopback connect flow. */
public final class JoinRemoteScreen extends Screen {
    private final Screen parent;
    private TextFieldWidget invite;
    private Button connect;
    private ITextComponent status = new StringTextComponent("");
    private boolean pending;
    public JoinRemoteScreen(Screen parent) { super(new TranslationTextComponent("mclink.join")); this.parent = parent; }
    @Override protected void init() {
        String previous = invite == null ? "" : invite.getValue();
        invite = new TextFieldWidget(font, width / 2 - 150, height / 2 - 22, 300, 20, new TranslationTextComponent("mclink.invite"));
        invite.setMaxLength(32768);
        addButton(invite);
        connect = addButton(new Button(width / 2 - 102, height / 2 + 12, 100, 20, new TranslationTextComponent("mclink.connect"), b -> begin()));
        invite.setResponder(value -> connect.active = !pending && value.trim().startsWith("mcl1_"));
        invite.setValue(previous);
        invite.setEditable(!pending);
        connect.active = !pending && previous.trim().startsWith("mcl1_");
        addButton(new Button(width / 2 + 2, height / 2 + 12, 100, 20, new TranslationTextComponent("gui.cancel"), b -> onClose()));
        setFocused(invite);
        invite.setFocus(true);
    }
    private void begin() {
        pending = true; connect.active = false; invite.setEditable(false);
        status = new TranslationTextComponent("mclink.starting");
        TailcatClient.state().join(parent, invite.getValue()).whenComplete((ignored, error) -> minecraft.execute(() -> {
            if (error != null && minecraft.screen == this) {
                status = new StringTextComponent("Could not connect: " + ShareScreen.rootMessage(error));
                pending = false; invite.setEditable(true); connect.active = true;
            }
        }));
    }
    @Override public void onClose() { TailcatClient.state().cancelJoin(); minecraft.setScreen(parent); }
    @Override public void render(MatrixStack graphics, int mouseX, int mouseY, float delta) {
        fill(graphics, 0, 0, width, height, 0xe0202020);
        super.render(graphics, mouseX, mouseY, delta);
        drawCenteredString(graphics, font, title, width / 2, height / 2 - 58, 0xffffff);
        drawCenteredString(graphics, font, status, width / 2, height / 2 + 46, 0xffaaaa);
    }
}
