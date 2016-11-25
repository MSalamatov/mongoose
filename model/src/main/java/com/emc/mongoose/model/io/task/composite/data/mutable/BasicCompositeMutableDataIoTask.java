package com.emc.mongoose.model.io.task.composite.data.mutable;

import static com.emc.mongoose.model.io.task.data.DataIoTask.DataIoResult;
import com.emc.mongoose.common.api.ByteRange;
import com.emc.mongoose.model.io.IoType;
import com.emc.mongoose.model.io.task.data.mutable.BasicMutableDataIoTask;
import com.emc.mongoose.model.io.task.partial.data.PartialDataIoTask;
import com.emc.mongoose.model.io.task.partial.data.mutable.BasicPartialMutableDataIoTask;
import com.emc.mongoose.model.io.task.partial.data.mutable.PartialMutableDataIoTask;
import com.emc.mongoose.model.item.MutableDataItem;

import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
/**
 Created by andrey on 25.11.16.
 */
public class BasicCompositeMutableDataIoTask<I extends MutableDataItem, R extends DataIoResult>
extends BasicMutableDataIoTask<I, R>
implements CompositeMutableDataIoTask<I, R> {

	private long sizeThreshold;

	private transient final Map<String, String> contextData = new HashMap<>();
	private transient final List<PartialMutableDataIoTask> subTasks = new ArrayList<>();
	private transient AtomicInteger pendingSubTasksCount = new AtomicInteger();

	public BasicCompositeMutableDataIoTask() {
		super();
	}

	public BasicCompositeMutableDataIoTask(
		final IoType ioType, final I item, final String srcPath, final String dstPath,
		final List<ByteRange> fixedRanges, final int randomRangesCount, final long sizeThreshold
	) {
		super(ioType, item, srcPath, dstPath, fixedRanges, randomRangesCount);
		this.sizeThreshold = sizeThreshold;
	}

	@Override
	public final String get(final String key) {
		return contextData.get(key);
	}

	@Override
	public final void put(final String key, final String value) {
		contextData.put(key, value);
	}

	@Override
	public final List<PartialMutableDataIoTask> getSubTasks() {

		if(!subTasks.isEmpty()) {
			return subTasks;
		}

		final int equalPartsCount = sizeThreshold > 0 ? (int) (contentSize / sizeThreshold) : 0;
		final long tailPartSize = contentSize % sizeThreshold;
		I nextPart;
		PartialMutableDataIoTask nextSubTask;
		for(int i = 0; i < equalPartsCount; i ++) {
			nextPart = item.slice(i * sizeThreshold, sizeThreshold);
			nextSubTask = new BasicPartialMutableDataIoTask<>(
				ioType, nextPart, srcPath, dstPath, i, this
			);
			subTasks.add(nextSubTask);
		}
		if(tailPartSize > 0) {
			nextPart = item.slice(equalPartsCount * sizeThreshold , tailPartSize);
			nextSubTask = new BasicPartialMutableDataIoTask<>(
				ioType, nextPart, srcPath, dstPath, equalPartsCount + 1, this
			);
			subTasks.add(nextSubTask);
		}

		pendingSubTasksCount.set(subTasks.size());

		return subTasks;
	}

	@Override
	public final void subTaskCompleted() {
		pendingSubTasksCount.decrementAndGet();
	}

	@Override
	public final boolean allSubTasksDone() {
		return pendingSubTasksCount.get() == 0;
	}

	@Override
	public void writeExternal(final ObjectOutput out)
	throws IOException {
		super.writeExternal(out);
		out.writeLong(sizeThreshold);
	}

	@Override
	public void readExternal(final ObjectInput in)
	throws IOException, ClassNotFoundException {
		super.readExternal(in);
		sizeThreshold = in.readLong();
	}
}