package com.tailscale.mclink;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
/** Reuses the upstream invitation input and loopback connect flow. */
public final class JoinRemoteScreen extends Screen {
    private final Screen parent;
    private EditBox invite;
    private Button connect;
    private Component status = Component.empty();
    private boolean pending;
    public JoinRemoteScreen(Screen parent) { super(Component.translatable("mclink.join")); this.parent = parent; }
    @Override protected void init() {
        String previous = invite == null ? "" : invite.getValue();
        invite = new EditBox(font, width / 2 - 150, height / 2 - 22, 300, 20, Component.translatable("mclink.invite"));
        invite.setMaxLength(32768);
        invite.setHint(Component.translatable("mclink.invite_hint"));
        addRenderableWidget(invite);
        connect = addRenderableWidget(Button.builder(Component.translatable("mclink.connect"), b -> begin())
                .bounds(width / 2 - 102, height / 2 + 12, 100, 20).build());
        invite.setResponder(value -> connect.active = !pending && value.trim().startsWith("mcl1_"));
        invite.setValue(previous);
        invite.setEditable(!pending);
        connect.active = !pending && previous.trim().startsWith("mcl1_");
        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> onClose())
                .bounds(width / 2 + 2, height / 2 + 12, 100, 20).build());
        setInitialFocus(invite);
    }
    private void begin() {
        pending = true; connect.active = false; invite.setEditable(false);
        status = Component.translatable("mclink.starting");
        TailcatClient.state().join(parent, invite.getValue()).whenComplete((ignored, error) -> minecraft.execute(() -> {
            if (error != null && minecraft.screen == this) {
                status = Component.literal("Could not connect: " + ShareScreen.rootMessage(error));
                pending = false; invite.setEditable(true); connect.active = true;
            }
        }));
    }
    @Override public void onClose() { TailcatClient.state().cancelJoin(); minecraft.setScreenAndShow(parent); }
    @Override public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        graphics.fill(0, 0, width, height, 0xe0202020);
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        graphics.centeredText(font, title, width / 2, height / 2 - 58, 0xffffff);
        graphics.centeredText(font, status, width / 2, height / 2 + 46, 0xffaaaa);
    }
}
