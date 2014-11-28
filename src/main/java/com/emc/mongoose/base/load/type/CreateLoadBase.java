package com.emc.mongoose.base.load.type;
//
import com.emc.mongoose.base.api.RequestConfig;
import com.emc.mongoose.base.data.DataItem;
import com.emc.mongoose.base.load.Consumer;
import com.emc.mongoose.base.load.impl.LoadExecutorBase;
import com.emc.mongoose.base.load.Producer;
import com.emc.mongoose.util.conf.RunTimeConfig;
import com.emc.mongoose.util.logging.ExceptionHandler;
import com.emc.mongoose.util.logging.Markers;
//
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
//
import java.io.IOException;
import java.rmi.RemoteException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadLocalRandom;
/**
 Created by kurila on 20.10.14.
 Custom implementation should at least define methods: "newDataItemsProducer" returning new data items
 producer instance and "produceSpecificDataItem" creating one data item and passing it to consumer.
 */
public abstract class CreateLoadBase<T extends DataItem>
extends LoadExecutorBase<T> {
	//
	private final static Logger LOG = LogManager.getLogger();
	//
	protected CreateLoadBase(
		final RunTimeConfig runTimeConfig,
		final String[] addrs, final RequestConfig<T> recConf, final long maxCount,
		final int threadsPerNode, final String listFile,
		final long minObjSize, final long maxObjSize, final float objSizeBias
	) throws IOException, CloneNotSupportedException {
		super(runTimeConfig, addrs, recConf, maxCount, threadsPerNode, listFile);
		if(producer==null) { // otherwise producer is reader specified by "listFile"
			producer = newDataItemProducer(minObjSize, maxObjSize, objSizeBias);
			producer.setConsumer(this);
		}
	}
	//
	protected abstract Producer<T> newDataItemProducer(
		final long minObjSize, final long maxObjSize, final float objSizeBias
	);
	//
	protected abstract class DataItemProducerBase
	extends Thread
	implements Producer<T> {
		//
		protected final long minObjSize, maxObjSize;
		protected final float objSizeBias;
		protected Consumer<T> newDataConsumer;
		//
		protected DataItemProducerBase(
			final long minObjSize, final long maxObjSize, final float objSizeBias
		) {
			this.minObjSize = minObjSize;
			this.maxObjSize = maxObjSize;
			this.objSizeBias = objSizeBias;
			setName(getClass().getSimpleName());
		}
		//
		@Override
		public final void setConsumer(final Consumer<T> consumer) {
			this.newDataConsumer = consumer;
		}
		//
		@Override
		public final Consumer<T> getConsumer()
			throws RemoteException {
			return newDataConsumer;
		}
		//
		protected abstract T produceSpecificDataItem(final long nextSize)
		throws IOException;
		//
		private final static String
			FMT_MSG_SUBMIT_NEXT = "Submitted object #%d of size %x",
			MSG_SUBMIT_REJECTED = "Submitting the object rejected by consumer",
			MSG_SUBMIT_FAILED = "Failed to submit object to consumer",
			MSG_INTERRUPTED = "Interrupted";
		//
		@Override
		public final void run() {
			final long sizeRange = maxObjSize - minObjSize;
			final ThreadLocalRandom thrLocalRnd = ThreadLocalRandom.current();
			long i = 0, nextSize;
			//
			LOG.debug(
				Markers.MSG, "Will try to produce up to {} objects of {} size", maxCount,
				minObjSize == maxObjSize ?
					RunTimeConfig.formatSize(minObjSize)
					:
					RunTimeConfig.formatSize(minObjSize)+".."+RunTimeConfig.formatSize(maxObjSize)
			);
			//
			do {
				try {
					nextSize = (long) (Math.pow(thrLocalRnd.nextDouble(), objSizeBias) * sizeRange);
					nextSize += minObjSize;
					newDataConsumer.submit(produceSpecificDataItem(nextSize));
					if(LOG.isTraceEnabled(Markers.MSG)) {
						LOG.trace(Markers.MSG, String.format(FMT_MSG_SUBMIT_NEXT, i, nextSize));
					}
					i ++;
				} catch(final RejectedExecutionException e) {
					LOG.trace(Markers.ERR, MSG_SUBMIT_REJECTED);
				} catch(final IOException e) {
					ExceptionHandler.trace(LOG, Level.TRACE, e, MSG_SUBMIT_FAILED);
				} catch(final InterruptedException e) {
					LOG.debug(Markers.MSG, MSG_INTERRUPTED);
					break;
				}
			} while(isAlive());
			LOG.debug(Markers.MSG, "Finished, generated {} items", i);
		}
		//
	}
	//
}
