package com.emc.mongoose.load.step.client;

import com.emc.mongoose.concurrent.ServiceTaskExecutor;
import com.emc.mongoose.config.AliasingUtil;
import com.emc.mongoose.config.TimeUtil;
import com.emc.mongoose.data.DataInput;
import com.emc.mongoose.env.Extension;
import com.emc.mongoose.exception.InterruptRunException;
import com.emc.mongoose.exception.OmgShootMyFootException;
import com.emc.mongoose.item.Item;
import com.emc.mongoose.item.io.ItemInputFactory;
import com.emc.mongoose.item.op.OpType;
import com.emc.mongoose.item.op.Operation;
import com.emc.mongoose.load.step.LoadStep;
import com.emc.mongoose.load.step.LoadStepBase;
import com.emc.mongoose.load.step.LoadStepFactory;
import com.emc.mongoose.load.step.client.metrics.MetricsAggregator;
import com.emc.mongoose.load.step.client.metrics.MetricsAggregatorImpl;
import com.emc.mongoose.load.step.file.FileManager;
import com.emc.mongoose.logging.LogUtil;
import com.emc.mongoose.logging.Loggers;
import com.emc.mongoose.metrics.DistributedMetricsContext;
import com.emc.mongoose.metrics.DistributedMetricsContextImpl;
import com.emc.mongoose.metrics.DistributedMetricsSnapshot;
import com.emc.mongoose.metrics.MetricsContext;
import com.emc.mongoose.metrics.MetricsManager;
import com.emc.mongoose.metrics.MetricsSnapshot;
import com.emc.mongoose.storage.driver.StorageDriver;
import com.github.akurilov.commons.concurrent.AsyncRunnableBase;
import com.github.akurilov.commons.io.Input;
import com.github.akurilov.commons.net.NetUtil;
import com.github.akurilov.commons.reflection.TypeUtil;
import com.github.akurilov.commons.system.SizeInBytes;
import com.github.akurilov.confuse.Config;
import com.github.akurilov.confuse.impl.BasicConfig;
import com.github.akurilov.fiber4j.ExclusiveFiberBase;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.message.ThreadDumpMessage;

import java.io.IOException;
import java.rmi.RemoteException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.emc.mongoose.Constants.KEY_CLASS_NAME;
import static com.emc.mongoose.Constants.KEY_STEP_ID;
import static com.emc.mongoose.config.ConfigUtil.flatten;
import static org.apache.logging.log4j.CloseableThreadContext.Instance;
import static org.apache.logging.log4j.CloseableThreadContext.put;

public abstract class LoadStepClientBase
extends LoadStepBase
implements LoadStepClient {

	private final List<LoadStep> stepSlices = new ArrayList<>();
	private final List<FileManager> fileMgrs = new ArrayList<>();
	// for the core configuration options which are using the files
	private final List<AutoCloseable> itemDataInputFileSlicers = new ArrayList<>();
	private final List<AutoCloseable> itemInputFileSlicers = new ArrayList<>();
	private final List<AutoCloseable> itemOutputFileAggregators = new ArrayList<>();
	private final List<AutoCloseable> opTraceLogFileAggregators = new ArrayList<>();
	private final List<AutoCloseable> storageAuthFileSlicers = new ArrayList<>();

	public LoadStepClientBase(
		final Config config, final List<Extension> extensions, final List<Config> ctxConfigs,
		final MetricsManager metricsMgr
	) {
		super(config, extensions, ctxConfigs, metricsMgr);
	}

	private MetricsAggregator metricsAggregator = null;

	@Override
	protected final void doStartWrapped()
	throws InterruptRunException, IllegalArgumentException {
		try(
			final Instance logCtx = put(KEY_STEP_ID, id())
				.put(KEY_CLASS_NAME, getClass().getSimpleName())
		) {
			// need to set the once generated step id
			config.val("load-step-id", id());
			config.val("load-step-idAutoGenerated", false);
			final List<String> nodeAddrs = remoteNodeAddrs(config);
			initFileManagers(nodeAddrs, fileMgrs);
			final int sliceCount = 1 + nodeAddrs.size();
			// init the base/shared config slices
			final List<Config> configSlices = sliceConfig(config, sliceCount);
			addFileClients(config, configSlices);
			// init the config slices for each of the load step context configs
			final List<List<Config>> ctxConfigsSlices = new ArrayList<>(sliceCount);
			for(int i = 0; i < sliceCount; i++) {
				ctxConfigsSlices.add(new ArrayList<>());
			}
			if(null != ctxConfigs) {
				for(final Config ctxConfig : ctxConfigs) {
					final List<Config> ctxConfigSlices = sliceConfig(ctxConfig, sliceCount);
					addFileClients(ctxConfig, ctxConfigSlices);
					for(int i = 0; i < sliceCount; i++) {
						ctxConfigsSlices.get(i).add(ctxConfigSlices.get(i));
					}
				}
			}
			initAndStartStepSlices(nodeAddrs, configSlices, ctxConfigsSlices, metricsMgr);
			initAndStartMetricsAggregator();
			Loggers.MSG.info(
				"{}: load step client started, additional nodes: {}", id(), Arrays.toString(nodeAddrs.toArray())
			);
		}
	}

	// determine the additional/remote full node addresses
	private static List<String> remoteNodeAddrs(final Config config) {
		final Config nodeConfig = config.configVal("load-step-node");
		final int nodePort = nodeConfig.intVal("port");
		final List<String> nodeAddrs = nodeConfig.listVal("addrs");
		return nodeAddrs == null || nodeAddrs.isEmpty() ?
			Collections.EMPTY_LIST :
			nodeAddrs
				.stream()
				.map(addr -> NetUtil.addPortIfMissing(addr, nodePort))
				.collect(Collectors.toList());
	}

	private static void initFileManagers(final List<String> nodeAddrs, final List<FileManager> fileMgrsDst) {
		// local file manager
		fileMgrsDst.add(FileManager.INSTANCE);
		// remote file managers
		nodeAddrs.stream().map(FileManagerClient::resolve).forEachOrdered(fileMgrsDst::add);
	}

	private void addFileClients(final Config config, final List<Config> configSlices)
	throws InterruptRunException {
		final Config loadConfig = config.configVal("load");
		final int batchSize = loadConfig.intVal("batch-size");
		final Config storageConfig = config.configVal("storage");
		final Config itemConfig = config.configVal("item");
		final Config itemDataConfig = itemConfig.configVal("data");
		final boolean verifyFlag = itemDataConfig.boolVal("verify");
		final Config itemDataInputConfig = itemDataConfig.configVal("input");
		final Config itemDataInputLayerConfig = itemDataInputConfig.configVal("layer");
		final Object itemDataInputLayerSizeRaw = itemDataInputLayerConfig.val("size");
		final SizeInBytes itemDataLayerSize;
		if(itemDataInputLayerSizeRaw instanceof String) {
			itemDataLayerSize = new SizeInBytes((String) itemDataInputLayerSizeRaw);
		} else {
			itemDataLayerSize = new SizeInBytes(TypeUtil.typeConvert(itemDataInputLayerSizeRaw, int.class));
		}
		final String itemDataInputFile = itemDataInputConfig.stringVal("file");
		final String itemDataInputSeed = itemDataInputConfig.stringVal("seed");
		final int itemDataInputLayerCacheSize = itemDataInputLayerConfig.intVal("cache");
		try(
			final DataInput dataInput = DataInput.instance(
				itemDataInputFile, itemDataInputSeed, itemDataLayerSize, itemDataInputLayerCacheSize
			);
			final StorageDriver<Item, Operation<Item>> storageDriver = StorageDriver.instance(
				extensions, storageConfig, dataInput, verifyFlag, batchSize, id()
			);
			final Input<Item> itemInput = ItemInputFactory.createItemInput(itemConfig, batchSize, storageDriver)
		) {
			if(null != itemDataInputFile && ! itemDataInputFile.isEmpty()) {
				itemDataInputFileSlicers.add(
					new ItemDataInputFileSlicer(id(), fileMgrs, configSlices, itemDataInputFile, batchSize)
				);
				Loggers.MSG.debug("{}: item data input file slicer initialized", id());
			}
			if(null != itemInput) {
				itemInputFileSlicers.add(new ItemInputFileSlicer(id(), fileMgrs, configSlices, itemInput, batchSize));
				Loggers.MSG.debug("{}: item input file slicer initialized", id());
			}
		} catch(final IOException e) {
			LogUtil.exception(Level.WARN, e, "{}: failed to close the item input");
		} catch(final OmgShootMyFootException e) {
			LogUtil.exception(Level.ERROR, e, "{}: failed to init the storage driver");
		} catch(final InterruptedException e) {
			throw new InterruptRunException(e);
		}
		final String itemOutputFile = config.stringVal("item-output-file");
		if(itemOutputFile != null && !itemOutputFile.isEmpty()) {
			itemOutputFileAggregators.add(new ItemOutputFileAggregator(id(), fileMgrs, configSlices, itemOutputFile));
			Loggers.MSG.debug("{}: item output file aggregator initialized", id());
		}
		if(config.boolVal("output-metrics-trace-persist")) {
			opTraceLogFileAggregators.add(new OpTraceLogFileAggregator(id(), fileMgrs));
			Loggers.MSG.debug("{}: operation traces log file aggregator initialized", id());
		}
		final String storageAuthFile = storageConfig.stringVal("auth-file");
		if(storageAuthFile != null && ! storageAuthFile.isEmpty()) {
			storageAuthFileSlicers.add(
				new TempInputTextFileSlicer(
					id(), storageAuthFile, fileMgrs, "storage-auth-file", configSlices, batchSize
				)
			);
			Loggers.MSG.debug("{}: storage auth file slicer initialized", id());
		}
	}

	private void initAndStartMetricsAggregator() {
		try(
			final Instance logCtx = put(KEY_STEP_ID, id())
				.put(KEY_CLASS_NAME, getClass().getSimpleName())
		) {
			metricsAggregator = new MetricsAggregatorImpl(id(), stepSlices);
			metricsAggregator.start();
		} catch(final Exception e) {
			LogUtil.exception(Level.ERROR, e, "{}: failed to start the metrics aggregator", id());
		}
	}

	private void initAndStartStepSlices(
		final List<String> nodeAddrs, final List<Config> configSlices, final List<List<Config>> ctxConfigsSlices,
		final MetricsManager metricsManager
	)
	throws InterruptRunException {
		final String stepTypeName;
		try {
			stepTypeName = getTypeName();
		} catch(final RemoteException e) {
			throw new AssertionError(e);
		}
		final int sliceCount = configSlices.size();
		for(int i = 0; i < sliceCount; i++) {
			final Config configSlice = configSlices.get(i);
			final LoadStep stepSlice;
			if(i == 0) {
				stepSlice = LoadStepFactory.createLocalLoadStep(
					configSlice, extensions, ctxConfigsSlices.get(i), metricsManager, stepTypeName
				);
			} else {
				final String nodeAddrWithPort = nodeAddrs.get(i - 1);
				stepSlice = LoadStepSliceUtil.resolveRemote(
					configSlice, ctxConfigsSlices.get(i), stepTypeName, nodeAddrWithPort
				);
			}
			stepSlices.add(stepSlice);
			if(stepSlice != null) {
				try {
					stepSlice.start();
				} catch(final InterruptRunException e) {
					throw e;
				} catch(final Exception e) {
					LogUtil.exception(Level.ERROR, e, "{}: failed to start the step slice \"{}\"", id(), stepSlice);
				}
			}
		}
	}

	private List<Config> sliceConfig(final Config config, final int sliceCount) {
		final List<Config> configSlices = new ArrayList<>(sliceCount);
		for(int i = 0; i < sliceCount; i++) {
			final Config configSlice = ConfigSliceUtil.initSlice(config);
			if(i == 0) {
				// local step slice: disable the average metrics output
				configSlice.val("output-metrics-average-period", "0s");
			}
			configSlices.add(configSlice);
		}
		if(sliceCount > 1) { // distributed mode
			final long countLimit = config.longVal("load-op-limit-count");
			if(countLimit > 0) {
				ConfigSliceUtil.sliceLongValue(countLimit, configSlices, "load-op-limit-count");
				configSlices
					.stream()
					.mapToLong(configSlice -> configSlice.longVal("load-op-limit-count"))
					.filter(countLimitSlice -> countLimitSlice == 0)
					.findAny()
					.ifPresent(
						countLimitSlice -> Loggers.MSG.fatal(
							"{}: the count limit ({}) is too small to be sliced among the {} nodes, the load step "
								+ "won't work correctly",
							id(), countLimit, sliceCount
						)
					);
			}
			final long countFailLimit = config.longVal("load-op-limit-fail-count");
			if(countFailLimit > 0) {
				ConfigSliceUtil.sliceLongValue(countFailLimit, configSlices, "load-op-limit-fail-count");
				configSlices
					.stream()
					.mapToLong(configSlice -> configSlice.longVal("load-op-limit-fail-count"))
					.filter(failCountLimitSlice -> failCountLimitSlice == 0)
					.findAny()
					.ifPresent(
						failCountLimitSlice -> Loggers.MSG.error(
							"{}: the failures count limit ({}) is too small to be sliced among the {} nodes, the load "
								+ "step may not work correctly",
							id(), countLimit, sliceCount
						)
					);
			}
			final double rateLimit = config.doubleVal("load-op-limit-rate");
			if(rateLimit > 0) {
				ConfigSliceUtil.sliceDoubleValue(rateLimit, configSlices, "load-op-limit-rate");
			}
			final long sizeLimit;
			final Object sizeLimitRaw = config.val("load-step-limit-size");
			if(sizeLimitRaw instanceof String) {
				sizeLimit = SizeInBytes.toFixedSize((String) sizeLimitRaw);
			} else {
				sizeLimit = TypeUtil.typeConvert(sizeLimitRaw, long.class);
			}
			if(sizeLimit > 0) {
				ConfigSliceUtil.sliceLongValue(sizeLimit, configSlices, "load-step-limit-size");
			}
			try {
				final Config storageNetNodeConfig = config.configVal("storage-net-node");
				final boolean sliceStorageNodesFlag = storageNetNodeConfig.boolVal("slice");
				if(sliceStorageNodesFlag) {
					final List<String> storageNodeAddrs = storageNetNodeConfig.listVal("addrs");
					ConfigSliceUtil.sliceStorageNodeAddrs(configSlices, storageNodeAddrs);
				}
			} catch(final NoSuchElementException ignore) {
			}
		}
		return configSlices;
	}

	private int sliceCount() {
		return stepSlices.size();
	}

	protected final void initMetrics(
		final int originIndex, final OpType opType, final int concurrencyLimit, final Config metricsConfig,
		final SizeInBytes itemDataSize, final boolean outputColorFlag
	) {
		final int concurrencyThreshold = (int) (concurrencyLimit * metricsConfig.doubleVal("threshold"));
		final int metricsAvgPeriod;
		final Object metricsAvgPeriodRaw = metricsConfig.val("average-period");
		if(metricsAvgPeriodRaw instanceof String) {
			metricsAvgPeriod = (int) TimeUtil.getTimeInSeconds((String) metricsAvgPeriodRaw);
		} else {
			metricsAvgPeriod = TypeUtil.typeConvert(metricsAvgPeriodRaw, int.class);
		}
		final boolean metricsAvgPersistFlag = metricsConfig.boolVal("average-persist");
		final boolean metricsSumPersistFlag = metricsConfig.boolVal("summary-persist");
		final boolean metricsSumPerfDbOutputFlag = metricsConfig.boolVal("summary-perfDbResultsFile");
		// it's not known yet how many nodes are involved, so passing the function "this::sliceCount" reference for
		// further usage
		final DistributedMetricsContext metricsCtx = new DistributedMetricsContextImpl<>(
			id(), opType, this::sliceCount, concurrencyLimit, concurrencyThreshold, itemDataSize, metricsAvgPeriod,
			outputColorFlag, metricsAvgPersistFlag, metricsSumPersistFlag, metricsSumPerfDbOutputFlag,
			() -> metricsSnapshotsByIndex(originIndex)
		);
		metricsContexts.add(metricsCtx);
	}

	private List<MetricsSnapshot> metricsSnapshotsByIndex(final int originIndex) {
		return metricsAggregator == null ?
			Collections.emptyList() :
			metricsAggregator.metricsSnapshotsByIndex(originIndex);
	}

	@Override
	protected final void doShutdown() {
		stepSlices
			.parallelStream()
			.forEach(
				stepSlice -> {
					try(
						final Instance logCtx = put(KEY_STEP_ID, id())
							.put(KEY_CLASS_NAME, getClass().getSimpleName())
					) {
						stepSlice.shutdown();
					} catch(final RemoteException e) {
						LogUtil.exception(Level.WARN, e, "{}: failed to shutdown the step service {}", id(), stepSlice);
					}
				}
			);
	}

	@Override
	public final boolean await(final long timeout, final TimeUnit timeUnit)
	throws InterruptRunException, IllegalStateException, InterruptedException {
		final int stepSliceCount = stepSlices.size();
		try(
			final Instance logCtx = put(KEY_STEP_ID, id())
				.put(KEY_CLASS_NAME, getClass().getSimpleName())
		) {
			if(0 == stepSliceCount) {
				throw new IllegalStateException("No step slices are available");
			}
			Loggers.MSG.debug(
				"{}: await for {} step slices for at most {} {}...", id(), stepSliceCount, timeout,
				timeUnit.name().toLowerCase()
			);
			final CountDownLatch awaitCountDown = new CountDownLatch(stepSlices.size());
			final List<AsyncRunnableBase> awaitTasks = stepSlices
				.stream()
				.map(
					stepSlice -> new ExclusiveFiberBase(ServiceTaskExecutor.INSTANCE) {
						@Override
						protected final void invokeTimedExclusively(final long startTimeNanos) {
							Loggers.MSG.trace("{}: await for the step slice \"{}\" started", id(), stepSlice);
							try {
								if(stepSlice.await(1, TimeUnit.NANOSECONDS)) {
									awaitCountDown.countDown();
									stop();
								}
							} catch(final RemoteException e) {
								LogUtil.exception(
									Level.WARN, e, "Failed to invoke the remote await method on the step slice \"{}\"",
									stepSlice
								);
							} catch(final InterruptedException e) {
								throw new InterruptRunException(e);
							} catch(final IllegalStateException e) {
								LogUtil.exception(Level.DEBUG, e, "{}: failure in the await method", id());
							}
						}
					}
				)
				.peek(AsyncRunnableBase::start)
				.collect(Collectors.toList());
			try {
				return awaitCountDown.await(timeout, timeUnit);
			} finally {
				awaitTasks.forEach(
					awaitTask -> {
						try {
							awaitTask.close();
						} catch(final InterruptRunException e) {
							throw e;
						} catch(final Exception e) {
							LogUtil.exception(Level.DEBUG, e, "{}: await task closing failure", id());
						}
					}
				);
			}
		} finally {
			Loggers.MSG.debug("{}: await for {} step slices done", id(), stepSliceCount);
		}
	}

	@Override
	protected final void doStop()
	throws InterruptRunException {
		stepSlices
			.parallelStream()
			.forEach(
				stepSlice -> {
					try(
						final Instance logCtx = put(KEY_STEP_ID, stepSlice.id())
							.put(KEY_CLASS_NAME, getClass().getSimpleName())
					) {
						stepSlice.stop();
					} catch(final InterruptRunException e) {
						throw e;
					} catch(final Exception e) {
						e.printStackTrace();
						LogUtil.exception(Level.WARN, e, "{}: failed to stop the step slice \"{}\"", id(), stepSlice);
					}
				}
			);
		if(null != metricsAggregator) {
			try {
				metricsAggregator.stop();
			} catch(final RemoteException ignored) {
			}
		}
		super.doStop();
	}

	@Override
	protected final void doClose()
	throws InterruptRunException, IOException {

		try(
			final Instance logCtx = put(KEY_STEP_ID, id())
				.put(KEY_CLASS_NAME, getClass().getSimpleName())
		) {

			super.doClose();

			if(null != metricsAggregator) {
				metricsAggregator.close();
				metricsAggregator = null;
			}

			stepSlices
				.parallelStream()
				.forEach(
					stepSlice -> {
						try {
							stepSlice.close();
							Loggers.MSG.debug("{}: step slice \"{}\" closed", id(), stepSlice);
						} catch(final InterruptRunException e) {
							throw e;
						} catch(final Exception e) {
							LogUtil.exception(
								Level.WARN, e, "{}: failed to close the step service \"{}\"", id(), stepSlice
							);
						}
					}
				);
			Loggers.MSG.debug("{}: closed all {} step slices", id(), stepSlices.size());
			stepSlices.clear();

			itemDataInputFileSlicers.forEach(
				itemDataInputFileSlicer -> {
					try {
						itemDataInputFileSlicer.close();
					} catch(final InterruptRunException e) {
						throw e;
					} catch(final Exception e) {
						LogUtil.exception(
							Level.WARN, e, "{}: failed to close the item data input file slicer \"{}\"", id(),
							itemDataInputFileSlicer
						);
					}
				}
			);
			itemDataInputFileSlicers.clear();

			itemInputFileSlicers.forEach(
				itemInputFileSlicer -> {
					try {
						itemInputFileSlicer.close();
					} catch(final InterruptRunException e) {
						throw e;
					} catch(final Exception e) {
						LogUtil.exception(
							Level.WARN, e, "{}: failed to close the item input file slicer \"{}\"", id(),
							itemInputFileSlicer
						);
					}
				}
			);
			itemInputFileSlicers.clear();

			itemOutputFileAggregators
				.parallelStream()
				.forEach(
					itemOutputFileAggregator -> {
						try {
							itemOutputFileAggregator.close();
						} catch(final InterruptRunException e) {
							throw e;
						} catch(final Exception e) {
							LogUtil.exception(
								Level.WARN, e, "{}: failed to close the item output file aggregator \"{}\"", id(),
								itemOutputFileAggregator
							);
						}
					}
				);
			itemOutputFileAggregators.clear();

			opTraceLogFileAggregators
				.parallelStream()
				.forEach(
					opTraceLogFileAggregator -> {
						try {
							opTraceLogFileAggregator.close();
						} catch(final InterruptRunException e) {
							throw e;
						} catch(final Exception e) {
							LogUtil.exception(
								Level.WARN, e, "{}: failed to close the operation traces log file aggregator \"{}\"",
								id(), opTraceLogFileAggregator
							);
						}
					}
				);
			opTraceLogFileAggregators.clear();

			storageAuthFileSlicers.forEach(
				storageAuthFileSlicer -> {
					try {
						storageAuthFileSlicer.close();
					} catch(final InterruptRunException e) {
						throw e;
					} catch(final Exception e) {
						LogUtil.exception(
							Level.WARN, e, "{}: failed to close the storage auth file slicer \"{}\"", id(),
							storageAuthFileSlicer
						);
					}
				}
			);
			storageAuthFileSlicers.clear();
		}
	}

	@Override
	public final <T extends LoadStepClient> T config(final Map<String, Object> configMap)
	throws InterruptRunException {
		if(ctxConfigs != null) {
			throw new IllegalStateException("config(...) should be invoked before any append(...) call");
		}
		final Config configCopy = new BasicConfig(config);
		final Map<String, String> argValPairs = new HashMap<>();
		flatten(configMap, argValPairs, config.pathSep(), null);
		final List<Map<String, Object>> aliasingConfig = config.listVal("aliasing");
		try {
			final Map<String, String> aliasedArgs = AliasingUtil.apply(argValPairs, aliasingConfig);
			if(config.boolVal("load-step-idAutoGenerated")) {
				if(aliasedArgs.get("load-step-id") != null) {
					configCopy.val("load-step-idAutoGenerated", false);
				}
			}
			aliasedArgs.forEach(configCopy::val); // merge
		} catch(final Exception e) {
			LogUtil.exception(Level.FATAL, e, "Scenario syntax error");
			throw new InterruptRunException(e);
		}
		return copyInstance(configCopy, null);
	}

	@Override
	public final <T extends LoadStepClient> T append(final Map<String, Object> context)
	throws InterruptRunException {
		final List<Config> ctxConfigsCopy;
		if(ctxConfigs == null) {
			ctxConfigsCopy = new ArrayList<>(1);
		} else {
			ctxConfigsCopy = ctxConfigs.stream().map(BasicConfig::new).collect(Collectors.toList());
		}
		final Map<String, String> argValPairs = new HashMap<>();
		flatten(context, argValPairs, config.pathSep(), null);
		final List<Map<String, Object>> aliasingConfig = config.listVal("aliasing");
		final Config ctxConfig = new BasicConfig(config);
		try {
			final Map<String, String> aliasedArgs = AliasingUtil.apply(argValPairs, aliasingConfig);
			aliasedArgs.forEach(ctxConfig::val); // merge
		} catch(final Exception e) {
			LogUtil.exception(Level.FATAL, e, "Scenario syntax error");
			throw new InterruptRunException(e);
		}
		ctxConfigsCopy.add(ctxConfig);
		return copyInstance(config, ctxConfigsCopy);
	}

	protected abstract <T extends LoadStepClient> T copyInstance(final Config config, final List<Config> ctxConfigs);
}
