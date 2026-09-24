package com.pglol.aotrpg;

import net.minecraft.network.packet.s2c.play.SubtitleS2CPacket;
import net.minecraft.network.packet.s2c.play.TitleFadeS2CPacket;
import net.minecraft.network.packet.s2c.play.TitleS2CPacket;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

/** Big on-screen titles. */
public final class Titles {
    private Titles() {}

    public static void show(ServerPlayerEntity p, Text title, Text subtitle, int in, int stay, int out) {
        p.networkHandler.sendPacket(new TitleFadeS2CPacket(in, stay, out));
        p.networkHandler.sendPacket(new SubtitleS2CPacket(subtitle));
        p.networkHandler.sendPacket(new TitleS2CPacket(title));
    }
}
