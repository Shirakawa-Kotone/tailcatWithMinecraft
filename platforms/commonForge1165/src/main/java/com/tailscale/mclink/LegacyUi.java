package com.tailscale.mclink;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.client.gui.widget.button.Button;
/** Constructor bridge for the pre-1.19.3 button API. */
final class LegacyUi {
 static Builder button(ITextComponent title, Button.IPressable action) { return new Builder(title, action); }
 static final class Builder {
  private final ITextComponent title; private final Button.IPressable action; private int x,y,w,h;
  Builder(ITextComponent title, Button.IPressable action) { this.title=title; this.action=action; }
  Builder bounds(int x,int y,int w,int h) { this.x=x;this.y=y;this.w=w;this.h=h;return this; }
  Button build() { return new Button(x,y,w,h,title,action); }
 }
}
