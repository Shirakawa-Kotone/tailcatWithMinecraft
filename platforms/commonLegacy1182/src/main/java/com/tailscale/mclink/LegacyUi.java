package com.tailscale.mclink;
import net.minecraft.network.chat.Component;
import net.minecraft.client.gui.components.Button;
/** Constructor bridge for the pre-1.19.3 button API. */
final class LegacyUi {
 static Builder button(Component title, Button.OnPress action) { return new Builder(title, action); }
 static final class Builder {
  private final Component title; private final Button.OnPress action; private int x,y,w,h;
  Builder(Component title, Button.OnPress action) { this.title=title; this.action=action; }
  Builder bounds(int x,int y,int w,int h) { this.x=x;this.y=y;this.w=w;this.h=h;return this; }
  Button build() { return new Button(x,y,w,h,title,action); }
 }
}
