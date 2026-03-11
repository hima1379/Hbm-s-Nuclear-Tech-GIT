package com.hbm.packet;

import com.hbm.main.tileentity.network.data.TileEntityFCSConsole;

import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

import java.util.UUID;

/**
 * Packet sent from FCS Console GUI to server when player clicks LAUNCH button
 * Instructs the FCS Console to launch a missile from the specified Launch Pad
 */
public class MissileLaunchPacket implements IMessage {

	private int x;
	private int y;
	private int z;
	private long launchPadIdMost;  // UUID most significant bits
	private long launchPadIdLeast; // UUID least significant bits
	private int targetEntityId;
	private boolean sarhMode;      // true = SARH, false = ARH

	public MissileLaunchPacket() {
	}

	public MissileLaunchPacket(BlockPos fcsPos, UUID launchPadId, int targetEntityId, boolean sarhMode) {
		this.x = fcsPos.getX();
		this.y = fcsPos.getY();
		this.z = fcsPos.getZ();
		this.launchPadIdMost = launchPadId.getMostSignificantBits();
		this.launchPadIdLeast = launchPadId.getLeastSignificantBits();
		this.targetEntityId = targetEntityId;
		this.sarhMode = sarhMode;
	}

	@Override
	public void fromBytes(ByteBuf buf) {
		x = buf.readInt();
		y = buf.readInt();
		z = buf.readInt();
		launchPadIdMost = buf.readLong();
		launchPadIdLeast = buf.readLong();
		targetEntityId = buf.readInt();
		sarhMode = buf.readBoolean();
	}

	@Override
	public void toBytes(ByteBuf buf) {
		buf.writeInt(x);
		buf.writeInt(y);
		buf.writeInt(z);
		buf.writeLong(launchPadIdMost);
		buf.writeLong(launchPadIdLeast);
		buf.writeInt(targetEntityId);
		buf.writeBoolean(sarhMode);
	}

	public static class Handler implements IMessageHandler<MissileLaunchPacket, IMessage> {

		@Override
		public IMessage onMessage(MissileLaunchPacket message, MessageContext ctx) {
			ctx.getServerHandler().player.server.addScheduledTask(() -> {
				EntityPlayer player = ctx.getServerHandler().player;

				if (player.world == null)
					return;

				BlockPos pos = new BlockPos(message.x, message.y, message.z);
				TileEntity te = player.world.getTileEntity(pos);

				if (te instanceof TileEntityFCSConsole) {
					TileEntityFCSConsole fcs = (TileEntityFCSConsole) te;
					UUID launchPadId = new UUID(message.launchPadIdMost, message.launchPadIdLeast);

					System.out.println("[MISSILE LAUNCH PACKET] Server received launch request:");
					System.out.println("  Launch Pad: " + launchPadId);
					System.out.println("  Target: " + message.targetEntityId);
					System.out.println("  Mode: " + (message.sarhMode ? "SARH" : "ARH"));

					// Execute missile launch
					fcs.launchMissile(launchPadId, message.targetEntityId, message.sarhMode);
				}
			});

			return null;
		}
	}
}
