from __future__ import print_function, absolute_import, with_statement
#
from loadbuilder import init as loadBuilderInit
from chain import build as chainBuild
from chain import execute as chainExecute
#
from java.lang import InterruptedException, Long, Short, Throwable, NumberFormatException
#
from org.apache.logging.log4j import Level, LogManager, ThreadContext
#
from com.emc.mongoose.common.conf import RunTimeConfig
from com.emc.mongoose.common.logging import Markers, TraceLogger
#
LOG = LogManager.getLogger()
#
def init():
	runTimeConfig = RunTimeConfig.getContext()
	LOG.debug(Markers.MSG, "Setting the metric update period to zero for chain scenario")
	runTimeConfig.set(RunTimeConfig.KEY_LOAD_METRICS_PERIOD_SEC, 0)
	#
	loadTypesChain = runTimeConfig.getStringArray(RunTimeConfig.KEY_SCENARIO_CHAIN_LOAD)
	LOG.info(Markers.MSG, "Load types chain: {}", loadTypesChain)
	listSizes = runTimeConfig.getStringArray(RunTimeConfig.KEY_SCENARIO_RAMPUP_SIZES)
	LOG.info(Markers.MSG, "Data sizes: {}", listSizes)
	listThreadCounts = runTimeConfig.getStringArray(RunTimeConfig.KEY_SCENARIO_RAMPUP_THREAD_COUNTS)
	LOG.info(Markers.MSG, "Thread counts: {}", listThreadCounts)
	return loadTypesChain, listSizes, listThreadCounts
#
def execute(loadBuilder, rampupParams=((),(),())):
	loadTypesChain = rampupParams[0]
	listSizes = rampupParams[1]
	listThreadCounts = rampupParams[2]
	for index, dataItemSizeStr in enumerate(listSizes):
		try:
			dataItemSize = Long(RunTimeConfig.toSize(dataItemSizeStr))
			for threadCountStr in listThreadCounts:
				nextChain = None
				try:
					threadCount = Short.valueOf(threadCountStr)
				except NumberFormatException as e:
					TraceLogger.failure(Markers.ERR, Level.WARN, e, "Failed to parse the next thread count")
				try:
					LOG.info(Markers.PERF_SUM, "---- Step {}x{} start ----", threadCount, dataItemSizeStr)
					ThreadContext.put("currentSize", dataItemSizeStr + "-" + str(index))
					ThreadContext.put("currentThreadCount", str(threadCount))
					nextChain = chainBuild(
						loadBuilder, loadTypesChain, False, True, dataItemSize, dataItemSize, threadCount
					)
					chainExecute(nextChain, False)
					LOG.debug(Markers.MSG, "---- Step {}x{} finish ----", threadCount, dataItemSizeStr)
				except InterruptedException as e:
					raise e
				except Throwable as e:
					TraceLogger.failure(LOG, Level.ERROR, e, "Chain execution failure")
		except InterruptedException:
			break
		except Throwable as e:
			TraceLogger.failure(LOG, Level.ERROR, e, "Determining the next data item size failure")
#
if __name__ == "__builtin__":
	execute(loadBuilder=loadBuilderInit(), rampupParams=init())
	LOG.info(Markers.MSG, "Scenario end")
