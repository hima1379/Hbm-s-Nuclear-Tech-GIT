package com.hbm.packet;

import com.hbm.main.tileentity.network.data.TileEntityFCSConsole;

import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

/**
 * Packet sent from FCS Console GUI to server when player clicks on a target
 * Instructs the FCS Console to designate the target to SPG-62 for illumination
 */
public class FCSTargetDesignationPacket implements IMessage {

	private int x;
	private int y;
	private int z;
	private int targetEntityId;

	public FCSTargetDesignationPacket() {
	}

	public FCSTargetDesignationPacket(BlockPos pos, int targetEntityId) {
		this.x = pos.getX();
		this.y = pos.getY();
		this.z = pos.getZ();
		this.targetEntityId = targetEntityId;
	}

	@Override
	public void fromBytes(ByteBuf buf) {
		x = buf.readInt();
		y = buf.readInt();
		z = buf.readInt();
		targetEntityId = buf.readInt();
	}

	@Override
	public void toBytes(ByteBuf buf) {
		buf.writeInt(x);
		buf.writeInt(y);
		buf.writeInt(z);
		buf.writeInt(targetEntityId);
	}

	public static class Handler implements IMessageHandler<FCSTargetDesignationPacket, IMessage> {

		@Override
		public IMessage onMessage(FCSTargetDesignationPacket message, MessageContext ctx) {
			ctx.getServerHandler().player.server.addScheduledTask(() -> {
				EntityPlayer player = ctx.getServerHandler().player;

				if (player.world == null)
					return;

				BlockPos pos = new BlockPos(message.x, message.y, message.z);
				TileEntity te = player.world.getTileEntity(pos);

				if (te instanceof TileEntityFCSConsole) {
					TileEntityFCSConsole fcs = (TileEntityFCSConsole) te;
					fcs.designateTargetToSPG62(message.targetEntityId);
					System.out.println("[FCS PACKET] Server received target designation for entity ID: " + message.targetEntityId);
				}
			});

			return null;
		}
	}
}
