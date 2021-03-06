package com.bewitchment.common.network;

import com.bewitchment.Bewitchment;
import com.bewitchment.common.world.BiomeChangingUtils;
import io.netty.buffer.ByteBuf;
import net.minecraft.client.Minecraft;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

public class PacketBiomeUpdate implements IMessage {
	private BlockPos pos;
	private int biomeId;

	public PacketBiomeUpdate(BlockPos pos, int biomeId) {
		this.pos = pos;
		this.biomeId = biomeId;
	}

	public PacketBiomeUpdate() {
	}

	@Override
	public void fromBytes(ByteBuf buf) {
		pos = BlockPos.fromLong(buf.readLong());
		biomeId = buf.readInt();
	}

	@Override
	public void toBytes(ByteBuf buf) {
		buf.writeLong(pos.toLong());
		buf.writeInt(biomeId);
	}

	public static class Handler implements IMessageHandler<PacketBiomeUpdate, IMessage> {

		@Override
		public IMessage onMessage(PacketBiomeUpdate packet, MessageContext ctx) {
			if (ctx.side.isClient()) {
				BlockPos pos = packet.pos;
				int id = packet.biomeId;

				Minecraft.getMinecraft().addScheduledTask(() -> {
					World world = Bewitchment.proxy.getPlayer(ctx).world;

					BiomeChangingUtils.setBiome(world, pos, id);

					int r = 2;
					world.markBlockRangeForRenderUpdate(pos.add(-r, -r, -r), pos.add(r, r, r));
				});
			}
			return null;
		}
	}
}
