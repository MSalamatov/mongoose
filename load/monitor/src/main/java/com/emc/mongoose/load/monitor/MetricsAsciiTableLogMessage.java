package com.emc.mongoose.load.monitor;

import com.emc.mongoose.model.io.IoType;
import com.emc.mongoose.model.load.LoadController;
import com.emc.mongoose.model.metrics.MetricsContext;
import com.emc.mongoose.ui.log.LogMessageBase;

import static com.emc.mongoose.common.env.DateUtil.FMT_DATE_METRICS_TABLE;
import static com.emc.mongoose.ui.log.LogUtil.RESET;
import static com.emc.mongoose.common.Constants.MIB;
import com.emc.mongoose.ui.log.LogUtil;
import static com.emc.mongoose.ui.log.LogUtil.getFailureRatioAnsiColorCode;

import org.apache.commons.lang.text.StrBuilder;

import static org.apache.commons.lang.SystemUtils.LINE_SEPARATOR;

import java.rmi.RemoteException;
import java.util.Date;
import java.util.Map;
import java.util.SortedSet;

/**
 Created by kurila on 18.05.17.
 Not thread safe, relies on the MetricsManager's (caller) exclusive invocation lock
 */
public class MetricsAsciiTableLogMessage
extends LogMessageBase {

	public static final String TABLE_HEADER =
		"----------------------------------------------------------------------------------------------------" + LINE_SEPARATOR +
		"    Step    |  Timestamp  |T|Concurrency|       Count       |   Last Rate    |  Mean    |   Mean    " + LINE_SEPARATOR +
		"    Name    |             |y|     x     |-------------------|----------------| Latency  | Duration  " + LINE_SEPARATOR +
		"            |yyMMdd-HHmmss|p|  Drivers  |   Success  |Failed| [op/s] |[MB/s] |  [us]    |   [us]    " + LINE_SEPARATOR +
		"------------|-------------|-|-----------|------------|------|--------|-------|----------|-----------" + LINE_SEPARATOR;
	public static final String TABLE_BORDER_BOTTOM =
		"----------------------------------------------------------------------------------------------------";
	public static final String TABLE_BORDER_VERTICAL = "|";
	public static final int TABLE_HEADER_PERIOD = 20;

	private static volatile long ROW_OUTPUT_COUNTER = 0;

	private final Map<LoadController, SortedSet<MetricsContext>> metrics;
	private volatile String formattedMsg = null;
	
	public MetricsAsciiTableLogMessage(
		final Map<LoadController, SortedSet<MetricsContext>> metrics
	) {
		this.metrics = metrics;
	}
	
	@Override
	public final void formatTo(final StringBuilder buffer) {
		if(formattedMsg == null) {
			final StrBuilder strb = new StrBuilder();
			MetricsContext.Snapshot snapshot;
			long succCount;
			long failCount;
			IoType ioType;
			for(final LoadController loadController : metrics.keySet()) {
				try {
					if(loadController.isInterrupted() || loadController.isClosed()) {
						continue;
					}
				} catch(final RemoteException ignored) {
				}
				for(final MetricsContext metricsContext : metrics.get(loadController)) {
					snapshot = metricsContext.getLastSnapshot();
					succCount = snapshot.getSuccCount();
					failCount = snapshot.getFailCount();
					ioType = metricsContext.getIoType();
					if(0 == ROW_OUTPUT_COUNTER % TABLE_HEADER_PERIOD) {
						strb.append(TABLE_HEADER);
					}
					ROW_OUTPUT_COUNTER ++;
					strb
						.appendFixedWidthPadLeft(metricsContext.getStepName(), 12, ' ')
						.append(TABLE_BORDER_VERTICAL)
						.appendFixedWidthPadLeft(FMT_DATE_METRICS_TABLE.format(new Date()), 13, ' ')
						.append(TABLE_BORDER_VERTICAL);
					if(LogUtil.isConsoleColoringEnabled()) {
						switch(ioType) {
							case NOOP:
								strb.append(LogUtil.NOOP_COLOR);
								break;
							case CREATE:
								strb.append(LogUtil.CREATE_COLOR);
								break;
							case READ:
								strb.append(LogUtil.READ_COLOR);
								break;
							case UPDATE:
								strb.append(LogUtil.UPDATE_COLOR);
								break;
							case DELETE:
								strb.append(LogUtil.DELETE_COLOR);
								break;
							case LIST:
								strb.append(LogUtil.LIST_COLOR);
								break;
						}
					}
					strb.append(metricsContext.getIoType().name().substring(0, 1));
					if(LogUtil.isConsoleColoringEnabled()) {
						strb.append(RESET);
					}
					strb
						.append(TABLE_BORDER_VERTICAL)
						.appendFixedWidthPadLeft(
							Integer.toString(metricsContext.getConcurrency()) + 'x' +
								Integer.toString(metricsContext.getDriverCount()),
							11, ' '
						)
						.append(TABLE_BORDER_VERTICAL)
						.appendFixedWidthPadLeft(succCount, 12, ' ').append(TABLE_BORDER_VERTICAL);
					if(LogUtil.isConsoleColoringEnabled()) {
						strb.append(getFailureRatioAnsiColorCode(succCount, failCount));
					}
					strb.appendFixedWidthPadLeft(failCount, 6, ' ');
					if(LogUtil.isConsoleColoringEnabled()) {
						strb.append(RESET);
					}
					strb
						.append(TABLE_BORDER_VERTICAL)
						.appendFixedWidthPadRight(snapshot.getSuccRateLast(), 8, ' ')
						.append(TABLE_BORDER_VERTICAL)
						.appendFixedWidthPadRight(snapshot.getByteRateLast() / MIB, 7, ' ')
						.append(TABLE_BORDER_VERTICAL)
						.appendFixedWidthPadLeft((long) snapshot.getLatencyMean(), 10, ' ')
						.append(TABLE_BORDER_VERTICAL)
						.appendFixedWidthPadLeft((long) snapshot.getDurationMean(), 11, ' ')
						.appendNewLine();
				}
			}
			formattedMsg = strb.toString();
		}
		buffer.append(formattedMsg);
	}
}
