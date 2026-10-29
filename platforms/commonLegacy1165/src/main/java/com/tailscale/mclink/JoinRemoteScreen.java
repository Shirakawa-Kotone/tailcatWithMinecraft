package com.tailscale.mclink;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.network.chat.TranslatableComponent;
/** Reuses the upstream invitation input and loopback connect flow. */
public final class JoinRemoteScreen extends Screen {
    private final Screen parent;
    private EditBox invite;
    private Button connect;
    private Component status = new TextComponent("");
    private boolean pending;
    public JoinRemoteScreen(Screen parent) { super(new TranslatableComponent("mclink.join")); this.parent = parent; }
    @Override protected void init() {
        String previous = invite == null ? "" : invite.getValue();
        invite = new EditBox(font, width / 2 - 150, height / 2 - 22, 300, 20, new TranslatableComponent("mclink.invite"));
        invite.setMaxLength(32768);
        addButton(invite);
        connect = addButton(new Button(width / 2 - 102, height / 2 + 12, 100, 20, new TranslatableComponent("mclink.connect"), b -> begin()));
        invite.setResponder(value -> connect.active = !pending && value.trim().startsWith("mcl1_"));
        invite.setValue(previous);
        invite.setEditable(!pending);
        connect.active = !pending && previous.trim().startsWith("mcl1_");
        addButton(new Button(width / 2 + 2, height / 2 + 12, 100, 20, new TranslatableComponent("gui.cancel"), b -> onClose()));
        setFocused(invite);
        invite.setFocus(true);
    }
    private void begin() {
        pending = true; connect.active = false; invite.setEditable(false);
        status = new TranslatableComponent("mclink.starting");
        TailcatClient.state().join(parent, invite.getValue()).whenComplete((ignored, error) -> minecraft.execute(() -> {
            if (error != null && minecraft.screen == this) {
                status = new TextComponent("Could not connect: " + ShareScreen.rootMessage(error));
                pending = false; invite.setEditable(true); connect.active = true;
            }
        }));
    }
    @Override public void onClose() { TailcatClient.state().cancelJoin(); minecraft.setScreen(parent); }
    @Override public void render(PoseStack graphics, int mouseX, int mouseY, float delta) {
        fill(graphics, 0, 0, width, height, 0xe0202020);
        super.render(graphics, mouseX, mouseY, delta);
        drawCenteredString(graphics, font, title, width / 2, height / 2 - 58, 0xffffff);
        drawCenteredString(graphics, font, status, width / 2, height / 2 + 46, 0xffaaaa);
    }
}
