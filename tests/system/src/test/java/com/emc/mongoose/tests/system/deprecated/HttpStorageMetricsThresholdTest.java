package com.emc.mongoose.tests.system.deprecated;

import com.emc.mongoose.api.common.SizeInBytes;
import com.emc.mongoose.api.model.io.IoType;
import com.emc.mongoose.run.scenario.JsonScenario;
import com.emc.mongoose.tests.system.base.deprecated.EnvConfiguredScenarioTestBase;
import com.emc.mongoose.tests.system.util.LogPatterns;
import com.emc.mongoose.ui.log.LogUtil;
import org.apache.commons.csv.CSVRecord;
import org.apache.logging.log4j.ThreadContext;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Ignore;
import org.junit.Test;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;

import static com.emc.mongoose.api.common.Constants.KEY_TEST_STEP_ID;
import static com.emc.mongoose.api.common.env.DateUtil.FMT_DATE_ISO8601;
import static com.emc.mongoose.api.common.env.PathUtil.getBaseDir;
import static com.emc.mongoose.run.scenario.Scenario.DIR_SCENARIO;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeFalse;

/**
 Created by andrey on 10.06.17.
 */

@Ignore public class HttpStorageMetricsThresholdTest
extends EnvConfiguredScenarioTestBase {

	private static final double LOAD_THRESHOLD = 0.8;
	private static final int RANDOM_RANGES_COUNT = 10;

	private static String STD_OUTPUT;

	@BeforeClass
	public static void setUpClass()
	throws Exception {
		EXCLUDE_PARAMS.clear();
		EXCLUDE_PARAMS.put(KEY_ENV_STORAGE_DRIVER_TYPE, Arrays.asList("fs"));
		EXCLUDE_PARAMS.put(KEY_ENV_STORAGE_DRIVER_CONCURRENCY, Arrays.asList(1));
		EXCLUDE_PARAMS.put(
			KEY_ENV_ITEM_DATA_SIZE,
			Arrays.asList(new SizeInBytes(0), new SizeInBytes("10KB"), new SizeInBytes("10GB"))
		);
		STEP_ID = HttpStorageMetricsThresholdTest.class.getSimpleName();
		SCENARIO_PATH = Paths.get(
			getBaseDir(), DIR_SCENARIO, "systest", "HttpStorageMetricsThreshold.json"
		);
		ThreadContext.put(KEY_TEST_STEP_ID, STEP_ID);
		EnvConfiguredScenarioTestBase.setUpClass();
		if(SKIP_FLAG) {
			return;
		}
		STD_OUT_STREAM.startRecording();
		SCENARIO = new JsonScenario(CONFIG, SCENARIO_PATH.toFile());
		SCENARIO.run();
		LogUtil.flushAll();
		TimeUnit.SECONDS.sleep(10);
		STD_OUTPUT = STD_OUT_STREAM.stopRecordingAndGet();
	}

	@AfterClass
	public static void tearDownClass()
	throws Exception {
		EnvConfiguredScenarioTestBase.tearDownClass();
	}

	@Test
	public void testMetricsLogFile()
	throws Exception {
		assumeFalse(SKIP_FLAG);
		final List<CSVRecord> metricsLogRecs = getMetricsLogRecords();
		final List<CSVRecord> createMetricsRecs = new ArrayList<>();
		final List<CSVRecord> readMetricsRecs = new ArrayList<>();
		final List<CSVRecord> updateMetricsRecs = new ArrayList<>();
		IoType nextMetricsRecIoType;
		for(final CSVRecord metricsRec : metricsLogRecs) {
			nextMetricsRecIoType = IoType.valueOf(metricsRec.get("TypeLoad"));
			switch(nextMetricsRecIoType) {
				case NOOP:
					fail("Unexpected I/O type: " + nextMetricsRecIoType);
					break;
				case CREATE:
					createMetricsRecs.add(metricsRec);
					break;
				case READ:
					readMetricsRecs.add(metricsRec);
					break;
				case UPDATE:
					updateMetricsRecs.add(metricsRec);
					break;
				case DELETE:
					fail("Unexpected I/O type: " + nextMetricsRecIoType);
					break;
				case LIST:
					fail("Unexpected I/O type: " + nextMetricsRecIoType);
					break;
			}
		}
		final long period = CONFIG.getOutputConfig().getMetricsConfig().getAverageConfig().getPeriod();
		testMetricsLogRecords(
			createMetricsRecs, IoType.CREATE, CONCURRENCY, STORAGE_DRIVERS_COUNT, ITEM_DATA_SIZE,
			0, 0, period
		);
		testMetricsLogRecords(
			readMetricsRecs, IoType.READ, CONCURRENCY, STORAGE_DRIVERS_COUNT, ITEM_DATA_SIZE,
			0, 0, period
		);
		testMetricsLogRecords(
			updateMetricsRecs, IoType.UPDATE, CONCURRENCY, STORAGE_DRIVERS_COUNT,
			new SizeInBytes(2 >> RANDOM_RANGES_COUNT - 1, ITEM_DATA_SIZE.get(), 1),
			0, 0, period
		);
	}

	@Test
	public void testTotalMetricsLogFile()
	throws Exception {
		assumeFalse(SKIP_FLAG);
		final List<CSVRecord> totalMetricsRecs = getMetricsTotalLogRecords();
		testTotalMetricsLogRecord(
			totalMetricsRecs.get(0), IoType.CREATE, CONCURRENCY, STORAGE_DRIVERS_COUNT,
			ITEM_DATA_SIZE, 0, 0
		);
		testTotalMetricsLogRecord(
			totalMetricsRecs.get(1), IoType.READ, CONCURRENCY, STORAGE_DRIVERS_COUNT,
			ITEM_DATA_SIZE, 0, 0
		);
		testTotalMetricsLogRecord(
			totalMetricsRecs.get(2), IoType.UPDATE, CONCURRENCY, STORAGE_DRIVERS_COUNT,
			new SizeInBytes(2 >> RANDOM_RANGES_COUNT - 1, ITEM_DATA_SIZE.get(), 1), 0, 0
		);
	}

	@Test
	public void testMetricsStdout()
	throws Exception {
		assumeFalse(SKIP_FLAG);
		final long period = CONFIG.getOutputConfig().getMetricsConfig().getAverageConfig().getPeriod();
		testSingleMetricsStdout(
			STD_OUTPUT.replaceAll("[\r\n]+", " "),
			IoType.CREATE, CONCURRENCY, STORAGE_DRIVERS_COUNT, ITEM_DATA_SIZE, period
		);
		testSingleMetricsStdout(
			STD_OUTPUT.replaceAll("[\r\n]+", " "),
			IoType.READ, CONCURRENCY, STORAGE_DRIVERS_COUNT, ITEM_DATA_SIZE, period
		);
		testSingleMetricsStdout(
			STD_OUTPUT.replaceAll("[\r\n]+", " "),
			IoType.UPDATE, CONCURRENCY, STORAGE_DRIVERS_COUNT,
			new SizeInBytes(2 >> RANDOM_RANGES_COUNT - 1, ITEM_DATA_SIZE.get(), 1), period
		);
	}

	@Test
	public void testMedTotalMetricsLogFile()
	throws Exception {
		assumeFalse(SKIP_FLAG);
		final List<CSVRecord> totalThresholdMetricsRecs = getMetricsMedTotalLogRecords();
		testTotalMetricsLogRecord(
			totalThresholdMetricsRecs.get(0), IoType.CREATE, CONCURRENCY, STORAGE_DRIVERS_COUNT,
			ITEM_DATA_SIZE, 0, 0
		);
		testTotalMetricsLogRecord(
			totalThresholdMetricsRecs.get(1), IoType.READ, CONCURRENCY, STORAGE_DRIVERS_COUNT,
			ITEM_DATA_SIZE, 0, 0
		);
		testTotalMetricsLogRecord(
			totalThresholdMetricsRecs.get(2), IoType.UPDATE, CONCURRENCY, STORAGE_DRIVERS_COUNT,
			new SizeInBytes(2 >> RANDOM_RANGES_COUNT - 1, ITEM_DATA_SIZE.get(), 1), 0, 0
		);
	}

	@Test
	public void testThresholdConditionMessagesInStdout()
	throws Exception {
		assumeFalse(SKIP_FLAG);
		int n = 0;
		Matcher m;
		while(true) {
			m = LogPatterns.STD_OUT_LOAD_THRESHOLD_ENTRANCE.matcher(STD_OUTPUT);
			if(!m.find()) {
				break;
			}
			final Date dtEnter = FMT_DATE_ISO8601.parse(m.group("dateTime"));
			final int threshold = Integer.parseInt(m.group("threshold"));
			assertEquals(CONCURRENCY * LOAD_THRESHOLD, threshold, 0);
			STD_OUTPUT = m.replaceFirst("");
			m = LogPatterns.STD_OUT_LOAD_THRESHOLD_EXIT.matcher(
				STD_OUTPUT.substring(m.regionStart())
			);
			assertTrue(m.find());
			final Date dtExit = FMT_DATE_ISO8601.parse(m.group("dateTime"));
			assertTrue(
				"Enter date (" + dtEnter + ") should be before exit date (" + dtExit + ")",
				dtEnter.before(dtExit)
			);
			STD_OUTPUT = m.replaceFirst("");
			n ++;
		}
		assertEquals(3, n);
	}
}