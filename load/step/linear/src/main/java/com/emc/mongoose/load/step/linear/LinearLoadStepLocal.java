package com.emc.mongoose.load.step.linear;

import com.emc.mongoose.env.Extension;
import com.emc.mongoose.exception.InterruptRunException;
import com.emc.mongoose.exception.OmgShootMyFootException;
import com.emc.mongoose.data.DataInput;
import com.emc.mongoose.item.op.OpType;
import com.emc.mongoose.item.Item;
import com.emc.mongoose.item.ItemFactory;
import com.emc.mongoose.item.io.ItemInfoFileOutput;
import com.emc.mongoose.item.ItemType;
import com.emc.mongoose.load.step.local.context.LoadStepContext;
import com.emc.mongoose.load.generator.LoadGenerator;
import com.emc.mongoose.load.generator.LoadGeneratorBuilder;
import com.emc.mongoose.load.step.local.LoadStepLocalBase;
import com.emc.mongoose.metrics.MetricsManager;
import com.emc.mongoose.storage.driver.StorageDriver;
import com.emc.mongoose.load.step.local.context.LoadStepContextImpl;
import com.emc.mongoose.load.generator.LoadGeneratorBuilderImpl;
import com.emc.mongoose.logging.LogUtil;
import com.emc.mongoose.logging.Loggers;

import com.github.akurilov.commons.io.Output;
import com.github.akurilov.commons.reflection.TypeUtil;
import com.github.akurilov.commons.system.SizeInBytes;
import com.github.akurilov.commons.concurrent.throttle.RateThrottle;

import com.github.akurilov.confuse.Config;

import org.apache.logging.log4j.Level;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

public class LinearLoadStepLocal
extends LoadStepLocalBase {

	public LinearLoadStepLocal(
		final Config baseConfig, final List<Extension> extensions, final List<Config> contexts,
		final MetricsManager metricsManager
	) {
		super(baseConfig, extensions, contexts, metricsManager);
	}

	@Override
	public String getTypeName() {
		return LinearLoadStepExtension.TYPE;
	}

	@Override
	protected void init()
	throws InterruptRunException {

		final String autoStepId = "linear_" + LogUtil.getDateTimeStamp();
		if(config.boolVal("load-step-idAutoGenerated")) {
			config.val("load-step-id", autoStepId);
		}

		final Config loadConfig = config.configVal("load");
		final Config opConfig = loadConfig.configVal("op");
		final Config stepConfig = loadConfig.configVal("step");
		final OpType opType = OpType.valueOf(opConfig.stringVal("type").toUpperCase());
		final Config storageConfig = config.configVal("storage");
		final int concurrencyLimit = storageConfig.intVal("driver-limit-concurrency");
		final Config outputConfig = config.configVal("output");
		final Config metricsConfig = outputConfig.configVal("metrics");
		final SizeInBytes itemDataSize;
		final Object itemDataSizeRaw = config.val("item-data-size");
		if(itemDataSizeRaw instanceof String) {
			itemDataSize = new SizeInBytes((String) itemDataSizeRaw);
		} else {
			itemDataSize = new SizeInBytes(TypeUtil.typeConvert(itemDataSizeRaw, long.class));
		}
		final int originIndex = 0;
		final boolean outputColorFlag = outputConfig.boolVal("color");
		initMetrics(originIndex, opType, concurrencyLimit, metricsConfig, itemDataSize, outputColorFlag);

		final Config itemConfig = config.configVal("item");
		final Config dataConfig = itemConfig.configVal("data");
		final Config dataInputConfig = dataConfig.configVal("input");
		final Config dataLayerConfig = dataInputConfig.configVal("layer");

		final String testStepId = stepConfig.stringVal("id");

		try {

			final Object dataLayerSizeRaw = dataLayerConfig.val("size");
			final SizeInBytes dataLayerSize;
			if(dataLayerSizeRaw instanceof String) {
				dataLayerSize = new SizeInBytes((String) dataLayerSizeRaw);
			} else {
				dataLayerSize = new SizeInBytes(TypeUtil.typeConvert(dataLayerSizeRaw, int.class));
			}

			final DataInput dataInput = DataInput.instance(
				dataInputConfig.stringVal("file"), dataInputConfig.stringVal("seed"), dataLayerSize,
				dataLayerConfig.intVal("cache")
			);

			final int batchSize = loadConfig.intVal("batch-size");

			try {

				final StorageDriver driver = StorageDriver.instance(
					extensions, storageConfig, dataInput, dataConfig.boolVal("verify"), batchSize, testStepId
				);

				final ItemType itemType = ItemType.valueOf(itemConfig.stringVal("type").toUpperCase());
				final ItemFactory<Item> itemFactory = ItemType.getItemFactory(itemType);
				final double rateLimit = opConfig.doubleVal("limit-rate");

				try {
					final LoadGeneratorBuilder generatorBuilder = new LoadGeneratorBuilderImpl<>()
						.itemConfig(itemConfig)
						.loadConfig(loadConfig)
						.itemType(itemType)
						.itemFactory((ItemFactory) itemFactory)
						.loadOperationsOutput(driver)
						.authConfig(storageConfig.configVal("auth"))
						.originIndex(0);
					if(rateLimit > 0) {
						generatorBuilder.addThrottle(new RateThrottle(rateLimit));
					}
					final LoadGenerator generator = generatorBuilder.build();
					final LoadStepContext stepCtx = new LoadStepContextImpl<>(
						testStepId, generator, driver, metricsContexts.get(0), loadConfig,
						outputConfig.boolVal("metrics-trace-persist")
					);
					stepContexts.add(stepCtx);

					final String itemOutputFile = itemConfig.stringVal("output-file");
					if(itemOutputFile != null && itemOutputFile.length() > 0) {
						final Path itemOutputPath = Paths.get(itemOutputFile);
						if(Files.exists(itemOutputPath)) {
							Loggers.ERR.warn("Items output file \"{}\" already exists", itemOutputPath);
						}
						try {
							final Output<? extends Item> itemOutput = new ItemInfoFileOutput<>(itemOutputPath);
							stepCtx.operationsResultsOutput(itemOutput);
						} catch(final IOException e) {
							LogUtil.exception(
								Level.ERROR, e,
								"Failed to initialize the item output, the processed items info won't be persisted"
							);
						}
					}
				} catch(final OmgShootMyFootException e) {
					throw new IllegalStateException("Failed to initialize the load generator", e);
				}
			} catch(final OmgShootMyFootException e) {
				throw new IllegalStateException("Failed to initialize the storage driver", e);
			} catch(final InterruptedException e) {
				throw new InterruptRunException(e);
			}
		} catch(final IOException e) {
			throw new IllegalStateException("Failed to initialize the data input", e);
		}
	}
}
