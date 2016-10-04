package com.emc.mongoose.storage.driver.net.base;

import com.emc.mongoose.model.api.io.task.IoTask;
import com.emc.mongoose.model.api.item.Item;
import com.emc.mongoose.ui.log.LogUtil;
import static com.emc.mongoose.model.api.io.task.IoTask.Status.FAIL_UNKNOWN;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;

/**
 Created by kurila on 04.10.16.
 */
public abstract class ClientHandlerBase<M, I extends Item, O extends IoTask<I>>
extends SimpleChannelInboundHandler<M> {
	
	private final static Logger LOG = LogManager.getLogger();
	
	protected NetStorageDriverBase<I, O> driver;
	
	protected ClientHandlerBase(final NetStorageDriverBase<I, O> driver) {
		this.driver = driver;
	}
	
	@Override
	protected final void channelRead0(final ChannelHandlerContext ctx, final M msg)
	throws Exception {
		final Channel channel = ctx.channel();
		final O ioTask = (O) channel.attr(NetStorageDriver.ATTR_KEY_IOTASK).get();
		handle(channel, ioTask, msg);
	}
	
	protected abstract void handle(final Channel channel, final O ioTask, final M msg)
	throws IOException;
	
	protected final void release(final Channel channel, final O ioTask)
	throws IOException {
		ioTask.finishResponse();
		driver.complete(channel, ioTask);
	}
	
	@Override
	public final void exceptionCaught(final ChannelHandlerContext ctx, final Throwable cause)
	throws IOException {
		LogUtil.exception(LOG, Level.WARN, cause, "HTTP client handler failure");
		final Channel channel = ctx.channel();
		final O ioTask = (O) channel.attr(NetStorageDriver.ATTR_KEY_IOTASK).get();
		ioTask.setStatus(FAIL_UNKNOWN);
		driver.complete(channel, ioTask);
	}
}
