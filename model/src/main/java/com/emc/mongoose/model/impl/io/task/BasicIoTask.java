package com.emc.mongoose.model.impl.io.task;

import com.emc.mongoose.model.api.io.task.IoTask;
import com.emc.mongoose.model.api.item.Item;
import com.emc.mongoose.model.util.LoadType;

import static com.emc.mongoose.model.api.item.Item.SLASH;

/**
 Created by kurila on 20.10.15.
 */
public class BasicIoTask<I extends Item>
implements IoTask<I> {
	
	protected final LoadType ioType;
	protected final I item;
	protected final String dstPath;
	
	protected volatile String nodeAddr;
	protected volatile Status status;
	protected volatile long reqTimeStart;
	protected volatile long reqTimeDone;
	protected volatile long respTimeStart;
	protected volatile long respTimeDone;
	
	public BasicIoTask(final LoadType ioType, final I item, final String dstPath) {
		this.ioType = ioType;
		this.item = item;
		this.dstPath = dstPath;
		reset();
	}
	
	@Override
	public void reset() {
		item.reset();
		nodeAddr = null;
		status = Status.PENDING;
		reqTimeStart = reqTimeDone = respTimeStart = reqTimeDone = 0;
	}
	
	@Override
	public final I getItem() {
		return item;
	}
	
	@Override
	public final LoadType getLoadType() {
		return ioType;
	}
	
	@Override
	public final String getNodeAddr() {
		return nodeAddr;
	}
	
	@Override
	public final void setNodeAddr(final String nodeAddr) {
		this.nodeAddr = nodeAddr;
	}
	
	@Override
	public final Status getStatus() {
		return status;
	}
	
	@Override
	public final void setStatus(final Status status) {
		this.status = status;
	}
	
	@Override
	public final long getReqTimeStart() {
		return reqTimeStart;
	}
	
	@Override
	public final void startRequest() {
		reqTimeStart = System.nanoTime() / 1000;
		status = Status.ACTIVE;
	}

	@Override
	public final void finishRequest() {
		reqTimeDone = System.nanoTime() / 1000;
	}

	@Override
	public final void startResponse() {
		respTimeStart = System.nanoTime() / 1000;
	}

	@Override
	public final void finishResponse() {
		respTimeDone = System.nanoTime() / 1000;
	}
	
	@Override
	public final int getDuration() {
		return (int) (respTimeDone - reqTimeStart);
	}
	
	@Override
	public final int getLatency() {
		return (int) (respTimeStart - reqTimeDone);
	}
	
	@Override
	public final String getDstPath() {
		return dstPath;
	}
	
	protected final static ThreadLocal<StringBuilder> STRB = new ThreadLocal<StringBuilder>() {
		@Override
		protected final StringBuilder initialValue() {
			return new StringBuilder();
		}
	};
	
	@Override
	public String toString() {
		final StringBuilder strb = STRB.get();
		strb.setLength(0);
		final String itemPath = item.getPath();
		final long respLatency = getLatency();
		final long reqDuration = getDuration();
		return strb
			.append(ioType.ordinal()).append(',')
			.append(
				itemPath == null ?
					item.getName() :
					itemPath.endsWith(SLASH) ?
						itemPath + item.getName() :
						itemPath + SLASH + item.getName()
			)
			.append(',')
			.append(status.code).append(',')
			.append(reqTimeStart).append(',')
			.append(respLatency > 0 ? respLatency : 0).append(',')
			.append(reqDuration)
			.toString();
	}
}