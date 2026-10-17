package net.pcal.fastback.common.mod;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

final class MinecraftClientScreen {
    static Screen current(Minecraft client) {
        return client.screen;
    }
}
