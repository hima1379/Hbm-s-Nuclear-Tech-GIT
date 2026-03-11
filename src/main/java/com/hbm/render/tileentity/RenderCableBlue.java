package com.hbm.render.tileentity;

import org.lwjgl.opengl.GL11;

import com.hbm.main.ResourceManager;
import com.hbm.lib.Library;
import com.hbm.blocks.ModBlocks;
import com.hbm.main.tileentity.network.data.TileEntityCableBlue;

import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.tileentity.TileEntitySpecialRenderer;

/**
 * Renderer for Blue Cable (data transmission)
 * Similar to RenderCable but uses DataNet connections
 */
public class RenderCableBlue extends TileEntitySpecialRenderer<TileEntityCableBlue> {

    @Override
    public void render(TileEntityCableBlue te, double x, double y, double z, float partialTicks, int destroyStage, float alpha) {
        if(te.getBlockType() != ModBlocks.blue_cable)
            return;

        GL11.glPushMatrix();
        GL11.glTranslated(x + 0.5F, y + 0.5F, z + 0.5F);
        GlStateManager.enableLighting();
        GlStateManager.enableCull();
        bindTexture(ResourceManager.cable_blue_tex);

        // Use Library.canConnectData() for DataNet connections
        boolean pX = Library.canConnectData(te.getWorld(), te.getPos().add(1, 0, 0), Library.POS_X);
        boolean nX = Library.canConnectData(te.getWorld(), te.getPos().add(-1, 0, 0), Library.NEG_X);
        boolean pY = Library.canConnectData(te.getWorld(), te.getPos().add(0, 1, 0), Library.POS_Y);
        boolean nY = Library.canConnectData(te.getWorld(), te.getPos().add(0, -1, 0), Library.NEG_Y);
        boolean pZ = Library.canConnectData(te.getWorld(), te.getPos().add(0, 0, 1), Library.POS_Z);
        boolean nZ = Library.canConnectData(te.getWorld(), te.getPos().add(0, 0, -1), Library.NEG_Z);

        // Use same cable_blue model (same structure as cable_neo)
        if(pX && nX && !pY && !nY && !pZ && !nZ)
            ResourceManager.cable_blue.renderPart("CX");
        else if(!pX && !nX && pY && nY && !pZ && !nZ)
            ResourceManager.cable_blue.renderPart("CY");
        else if(!pX && !nX && !pY && !nY && pZ && nZ)
            ResourceManager.cable_blue.renderPart("CZ");
        else{
            ResourceManager.cable_blue.renderPart("Core");
            if(pX) ResourceManager.cable_blue.renderPart("posX");
            if(nX) ResourceManager.cable_blue.renderPart("negX");
            if(pY) ResourceManager.cable_blue.renderPart("posY");
            if(nY) ResourceManager.cable_blue.renderPart("negY");
            if(pZ) ResourceManager.cable_blue.renderPart("negZ");
            if(nZ) ResourceManager.cable_blue.renderPart("posZ");
        }

        GL11.glTranslated(-x - 0.5F, -y - 0.5F, -z - 0.5F);
        GL11.glPopMatrix();
    }
}
