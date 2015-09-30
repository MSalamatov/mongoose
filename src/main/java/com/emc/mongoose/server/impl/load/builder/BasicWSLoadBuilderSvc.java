package com.emc.mongoose.server.impl.load.builder;
//mongoose-common.jar
import com.emc.mongoose.common.conf.Constants;
import com.emc.mongoose.common.conf.RunTimeConfig;
import com.emc.mongoose.common.conf.SizeUtil;
import com.emc.mongoose.common.exceptions.DuplicateSvcNameException;
import com.emc.mongoose.common.log.LogUtil;
import com.emc.mongoose.common.log.Markers;
import com.emc.mongoose.common.net.ServiceUtil;
//mongoose-core-api.jar
import com.emc.mongoose.core.api.data.model.DataItemSrc;
import com.emc.mongoose.core.api.io.task.IOTask;
import com.emc.mongoose.core.api.load.executor.LoadExecutor;
import com.emc.mongoose.core.api.data.WSObject;
import com.emc.mongoose.core.api.io.req.WSRequestConfig;
import com.emc.mongoose.core.api.load.executor.WSLoadExecutor;
//mongoose-server-api.jar
import com.emc.mongoose.core.impl.data.model.NewDataItemSrc;
import com.emc.mongoose.server.api.load.executor.WSLoadSvc;
import com.emc.mongoose.server.api.load.builder.WSLoadBuilderSvc;
// mongoose-core-impl.jar
import com.emc.mongoose.core.impl.load.builder.BasicWSLoadBuilder;
// mongoose-server-impl.jar
import com.emc.mongoose.server.impl.load.executor.BasicWSLoadSvc;
//
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
//
import java.io.IOException;
import java.rmi.RemoteException;
import java.util.concurrent.TimeUnit;
/**
 Created by kurila on 30.05.14.
 */
public class BasicWSLoadBuilderSvc<T extends WSObject, U extends WSLoadExecutor<T>>
extends BasicWSLoadBuilder<T, U>
implements WSLoadBuilderSvc<T, U> {
	//
	private final static Logger LOG = LogManager.getLogger();
	//
	private String configTable = null;
	//
	public BasicWSLoadBuilderSvc(final RunTimeConfig runTimeConfig) {
		super(runTimeConfig);
	}
	//
	@Override
	public final BasicWSLoadBuilderSvc<T, U> setProperties(final RunTimeConfig clientConfig) {
		super.setProperties(clientConfig);
		final String runMode = clientConfig.getRunMode();
		if (!runMode.equals(Constants.RUN_MODE_SERVER)
			&& !runMode.equals(Constants.RUN_MODE_COMPAT_SERVER)) {
			configTable = clientConfig.toString();
		}
		RunTimeConfig.getContext();
		return this;
	}
	//
	@Override @SuppressWarnings("unchecked")
	public final String buildRemotely()
	throws RemoteException {
		final WSLoadSvc<T> loadSvc = (WSLoadSvc<T>) build();
		ServiceUtil.create(loadSvc);
		if(configTable != null) {
			LOG.info(Markers.MSG, configTable);
			configTable = null;
		}
		return loadSvc.getName();
	}
	//
	@Override
	public final String getName() {
		return getClass().getPackage().getName();
	}
	//
	@Override
	public final int getNextInstanceNum(final String runId) {
		return LoadExecutor.NEXT_INSTANCE_NUM.get();
	}
	//
	@Override
	public final void setNextInstanceNum(final String runId, final int instanceN) {
		LoadExecutor.NEXT_INSTANCE_NUM.set(instanceN);
	}
	//
	@Override
	protected final void invokePreConditions() {} // discard any precondition invocations in load server mode
	// load server shouldn't use a container listing as an item source
	@Override
	public final DataItemSrc<T> getDefaultItemSource() {
		DataItemSrc<T> itemSrc = null;
		if(IOTask.Type.CREATE.equals(reqConf.getLoadType())) {
			try {
				itemSrc = new NewDataItemSrc<>(
					reqConf.getDataItemClass(), minObjSize, maxObjSize, objSizeBias
				);
			} catch(final NoSuchMethodException e) {
				LogUtil.exception(LOG, Level.ERROR, e, "Failed to use new data input");
			}
		}
		return itemSrc;
	}
	//
	@Override @SuppressWarnings("unchecked")
	protected final U buildActually()
	throws IllegalStateException {
		if(reqConf == null) {
			throw new IllegalStateException("Should specify request builder instance before instancing");
		}
		//
		final WSRequestConfig wsReqConf = WSRequestConfig.class.cast(reqConf);
		final RunTimeConfig localRunTimeConfig = RunTimeConfig.getContext();
		// the statement below fixes hi-level API distributed mode usage and tests
		localRunTimeConfig.setProperty(RunTimeConfig.KEY_RUN_MODE, Constants.RUN_MODE_SERVER);
		if(minObjSize > maxObjSize) {
			throw new IllegalStateException(
				String.format(
					LogUtil.LOCALE_DEFAULT, "Min object size %s should be less than upper bound %s",
					SizeUtil.formatSize(minObjSize), SizeUtil.formatSize(maxObjSize)
				)
			);
		}
		//
		final IOTask.Type loadType = reqConf.getLoadType();
		final int
			connPerNode = loadTypeConnPerNode.get(loadType),
			minThreadCount = getMinIOThreadCount(
				loadTypeWorkerCount.get(loadType), storageNodeAddrs.length, connPerNode
			);
		//
		return (U) new BasicWSLoadSvc<>(
			localRunTimeConfig, wsReqConf, storageNodeAddrs, connPerNode, minThreadCount,
			itemSrc == null ? getDefaultItemSource() : itemSrc,
			maxCount, minObjSize, maxObjSize, objSizeBias,
			manualTaskSleepMicroSecs, rateLimit, updatesPerItem
		);
	}
	//
	public final void start()
	throws RemoteException {
		LOG.debug(Markers.MSG, "Load builder service instance created");
		try {
		/*final RemoteStub stub = */
			ServiceUtil.create(this);
		/*LOG.debug(Markers.MSG, stub.toString());*/
		} catch (final DuplicateSvcNameException e) {
			LogUtil.exception(LOG, Level.ERROR, e, "Possible load service usage collision");
		}
		LOG.info(Markers.MSG, "Server started and waiting for the requests");
	}
	//
	@Override
	public void shutdown()
	throws RemoteException, IllegalStateException {
	}
	//
	@Override
	public void await()
	throws RemoteException, InterruptedException {
		await(Long.MAX_VALUE, TimeUnit.DAYS);
	}
	//
	@Override
	public void await(final long timeOut, final TimeUnit timeUnit)
	throws RemoteException, InterruptedException {
		timeUnit.sleep(timeOut);
	}
	//
	@Override
	public void interrupt()
	throws RemoteException {
	}
	//
	@Override
	public final void close()
	throws IOException {
		ServiceUtil.close(this);
	}
}
