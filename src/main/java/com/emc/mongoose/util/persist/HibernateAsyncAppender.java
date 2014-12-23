package com.emc.mongoose.util.persist;

import com.emc.mongoose.util.logging.Markers;
import org.apache.logging.log4j.core.AbstractLifeCycle;
import org.apache.logging.log4j.core.Appender;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.async.RingBufferLogEvent;
import org.apache.logging.log4j.core.config.AppenderControl;
import org.apache.logging.log4j.core.config.AppenderRef;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.plugins.Plugin;
import org.apache.logging.log4j.core.config.plugins.PluginAliases;
import org.apache.logging.log4j.core.config.plugins.PluginAttribute;
import org.apache.logging.log4j.core.config.plugins.PluginConfiguration;
import org.apache.logging.log4j.core.config.plugins.PluginElement;
import org.apache.logging.log4j.core.config.plugins.PluginFactory;
import org.apache.logging.log4j.core.impl.Log4jLogEvent;
import org.apache.logging.log4j.status.StatusLogger;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

/**
 * Created by olga on 09.12.14.
 */
@Plugin(name = "HibernateAsync", category = "Core", elementType = "appender", printObject = true)
public class HibernateAsyncAppender
extends AbstractAppender {

	//
	public static final org.apache.logging.log4j.Logger LOGGER = StatusLogger.getLogger();
	//
	private static final long serialVersionUID = 1L;
	private static final int DEFAULT_QUEUE_SIZE = 128,
		POOL_SIZE=1000,
		DEFAULT_THREADS_FOR_QUEUE = 1;
	private static final String SHUTDOWN = "Shutdown";
	private final BlockingQueue<Serializable> queue;
	private final ThreadPoolExecutor executor = new ThreadPoolExecutor(POOL_SIZE, POOL_SIZE,50, TimeUnit.SECONDS,new ArrayBlockingQueue<Runnable>(100));
	private final int queueSize;
	private final int threadsForQueue;
	private final boolean blocking;
	private final Configuration config;
	private final AppenderRef[] appenderRefs;
	private final String errorRef;
	private final boolean includeLocation;
	private AppenderControl errorAppender;
	//private AsyncThread thread;
	private static final AtomicLong threadSequence = new AtomicLong(1);
	private static ThreadLocal<Boolean> isAppenderThread = new ThreadLocal<Boolean>();


	private HibernateAsyncAppender(final String name, final Filter filter, final AppenderRef[] appenderRefs,
						  final String errorRef, final int queueSize, final boolean blocking,
						  final boolean ignoreExceptions, final Configuration config,
						  final boolean includeLocation, final int threadsForQueue) {
		super(name, filter, null, ignoreExceptions);
		this.queue = new ArrayBlockingQueue<Serializable>(queueSize);
		this.queueSize = queueSize;
		this.threadsForQueue = threadsForQueue;
		this.blocking = blocking;
		this.config = config;
		this.appenderRefs = appenderRefs;
		this.errorRef = errorRef;
		this.includeLocation = includeLocation;
	}
	//
	@Override
	public void start() {
		Runtime.getRuntime().addShutdownHook(new ShutDownThread(executor));
		final Map<String, Appender> map = config.getAppenders();
		final List<AppenderControl> appenders = new ArrayList<AppenderControl>();
		for (final AppenderRef appenderRef : appenderRefs) {
			if (map.containsKey(appenderRef.getRef())) {
				appenders.add(new AppenderControl(map.get(appenderRef.getRef()), appenderRef.getLevel(),
						appenderRef.getFilter()));
			} else {
				LOGGER.error("No appender named {} was configured", appenderRef);
			}
		}
		if (errorRef != null) {
			if (map.containsKey(errorRef)) {
				errorAppender = new AppenderControl(map.get(errorRef), null, null);
			} else {
				LOGGER.error("Unable to set up error Appender. No appender named {} was configured", errorRef);
			}
		}
		for (int i=0; i<40; i++) {
			executor.submit(new QueueProcessorTask(appenders, queue));
		}
		super.start();
	}
	//
	@Override
	public void stop() {
		super.stop();
		LOGGER.trace("AsyncAppender stopping. Queue still has {} events.", queue.size());
		executor.shutdown();
	}
	//
	@Override
	public void append(LogEvent logEvent) {
		if (!isStarted()) {
			throw new IllegalStateException("AsyncAppender " + getName() + " is not active");
		}
		if (!(logEvent instanceof Log4jLogEvent)) {
			if (!(logEvent instanceof RingBufferLogEvent)) {
				return; // only know how to Serialize Log4jLogEvents and RingBufferLogEvents
			}
			logEvent = ((RingBufferLogEvent) logEvent).createMemento();
		}
		logEvent.getMessage().getFormattedMessage(); // LOG4J2-763: ask message to freeze parameters
		final Log4jLogEvent coreEvent = (Log4jLogEvent) logEvent;
		boolean appendSuccessful = false;
		if (blocking) {
			if (isAppenderThread.get() == Boolean.TRUE && queue.remainingCapacity() == 0) {
				// LOG4J2-485: avoid deadlock that would result from trying
				// to add to a full queue from appender thread
				coreEvent.setEndOfBatch(false); // queue is definitely not empty!
				//appendSuccessful = thread.callAppenders(coreEvent);
			} else {
				try {
					// wait for free slots in the queue
					queue.put(Log4jLogEvent.serialize(coreEvent, includeLocation));
					appendSuccessful = true;
				} catch (final InterruptedException e) {
					LOGGER.warn("Interrupted while waiting for a free slot in the AsyncAppender LogEvent-queue {}",
							getName());
				}
			}
		} else {
			appendSuccessful = queue.offer(Log4jLogEvent.serialize(coreEvent, includeLocation));
			if (!appendSuccessful) {
				error("Appender " + getName() + " is unable to write primary appenders. queue is full");
			}
		}
		if (!appendSuccessful && errorAppender != null) {
			errorAppender.callAppender(coreEvent);
		}
	}

	/**
	 * Create an AsyncAppender.
	 * @param appenderRefs The Appenders to reference.
	 * @param errorRef An optional Appender to write to if the queue is full or other errors occur.
	 * @param blocking True if the Appender should wait when the queue is full. The default is true.
	 * @param size The size of the event queue. The default is 128.
	 * @param name The name of the Appender.
	 * @param includeLocation whether to include location information. The default is false.
	 * @param filter The Filter or null.
	 * @param config The Configuration.
	 * @param ignoreExceptions If {@code "true"} (default) exceptions encountered when appending events are logged;
	 *                         otherwise they are propagated to the caller.
	 * @return The AsyncAppender.
	 */
	@PluginFactory
	public static HibernateAsyncAppender createAppender(@PluginElement("AppenderRef") final AppenderRef[] appenderRefs,
    	@PluginAttribute("errorRef") @PluginAliases("error-ref") final String errorRef,
    	@PluginAttribute(value = "blocking", defaultBoolean = true) final boolean blocking,
    	@PluginAttribute(value = "bufferSize", defaultInt = DEFAULT_QUEUE_SIZE) final int size,
    	@PluginAttribute(value = "threadsForQueue", defaultInt = DEFAULT_THREADS_FOR_QUEUE) final int threadsForQueue,
    	@PluginAttribute("name") final String name,
    	@PluginAttribute(value = "includeLocation", defaultBoolean = false) final boolean includeLocation,
    	@PluginElement("Filter") final Filter filter,
    	@PluginConfiguration final Configuration config,
    	@PluginAttribute(value = "ignoreExceptions", defaultBoolean = true) final boolean ignoreExceptions)
	{
		if (name == null) {
			LOGGER.error("No name provided for AsyncAppender");
			return null;
		}
		if (appenderRefs == null) {
			LOGGER.error("No appender references provided to AsyncAppender {}", name);
		}

		return new HibernateAsyncAppender(name, filter, appenderRefs, errorRef,
				size, blocking, ignoreExceptions, config, includeLocation, threadsForQueue);
	}
	////////////////////
	//Task for thread pool executor
	///////////////////
	private final class QueueProcessorTask
	implements Runnable{
		private volatile boolean shutdown = false;
		private final List<AppenderControl> appenders;
		private final BlockingQueue<Serializable> queue;

		public QueueProcessorTask(final List<AppenderControl> appenders, final BlockingQueue<Serializable> queue) {
			this.appenders = appenders;
			this.queue = queue;
		}

		@Override
		public void run() {
			isAppenderThread.set(Boolean.TRUE); // LOG4J2-485
			while (!shutdown) {
				Serializable s;
				try {
					s = queue.take();
					if (s != null && s instanceof String && SHUTDOWN.equals(s.toString())) {
						shutdown = true;
						continue;
					}
				} catch (final InterruptedException ex) {
					break; // LOG4J2-830
				}
				final Log4jLogEvent event = Log4jLogEvent.deserialize(s);
				event.setEndOfBatch(queue.isEmpty());
				final boolean success = callAppenders(event);
				if (!success && errorAppender != null) {
					try {
						errorAppender.callAppender(event);
					} catch (final Exception ex) {
						// Silently accept the error.
					}
				}
			}
			// Process any remaining items in the queue.
			LOGGER.trace("AsyncAppender.AsyncThread shutting down. Processing remaining {} queue events.",
					queue.size());
			int count= 0;
			int ignored = 0;
			while (!queue.isEmpty()) {
				try {
					final Serializable s = queue.take();
					if (Log4jLogEvent.canDeserialize(s)) {
						final Log4jLogEvent event = Log4jLogEvent.deserialize(s);
						event.setEndOfBatch(queue.isEmpty());
						callAppenders(event);
						count++;
					} else {
						ignored++;
						LOGGER.trace("Ignoring event of class {}", s.getClass().getName());
					}
				} catch (final InterruptedException ex) {
					// May have been interrupted to shut down.
					// Here we ignore interrupts and try to process all remaining events.
				}
			}
			LOGGER.trace("AsyncAppender.AsyncThread stopped. Queue has {} events remaining. " +
							"Processed {} and ignored {} events since shutdown started.",
					queue.size(), count, ignored);
		}

		/**
		 * Calls {@link AppenderControl#callAppender(LogEvent) callAppender} on
		 * all registered {@code AppenderControl} objects, and returns {@code true}
		 * if at least one appender call was successful, {@code false} otherwise.
		 * Any exceptions are silently ignored.
		 *
		 * @param event the event to forward to the registered appenders
		 * @return {@code true} if at least one appender call succeeded, {@code false} otherwise
		 */
		boolean callAppenders(final Log4jLogEvent event) {
			boolean success = false;
			for (final AppenderControl control : appenders) {
				try {
					control.callAppender(event);
					success = true;
				} catch (final Exception ex) {
					// If no appender is successful the error appender will get it.
				}
			}
			return success;
		}
	}

	/**
	 * Returns the names of the appenders that this asyncAppender delegates to
	 * as an array of Strings.
	 * @return the names of the sink appenders
	 */
	public String[] getAppenderRefStrings() {
		final String[] result = new String[appenderRefs.length];
		for (int i = 0; i < result.length; i++) {
			result[i] = appenderRefs[i].getRef();
		}
		return result;
	}

	/**
	 * Returns {@code true} if this AsyncAppender will take a snapshot of the stack with
	 * every log event to determine the class and method where the logging call
	 * was made.
	 * @return {@code true} if location is included with every event, {@code false} otherwise
	 */
	public boolean isIncludeLocation() {
		return includeLocation;
	}

	/**
	 * Returns {@code true} if this AsyncAppender will block when the queue is full,
	 * or {@code false} if events are dropped when the queue is full.
	 * @return whether this AsyncAppender will block or drop events when the queue is full.
	 */
	public boolean isBlocking() {
		return blocking;
	}

	/**
	 * Returns the name of the appender that any errors are logged to or {@code null}.
	 * @return the name of the appender that any errors are logged to or {@code null}
	 */
	public String getErrorRef() {
		return errorRef;
	}

	public int getQueueCapacity() {
		return queueSize;
	}

	public int getQueueRemainingCapacity() {
		return queue.remainingCapacity();
	}
}
/////////////////////////////////////
final class ShutDownThread
		extends Thread
{
	private final ThreadPoolExecutor threadPoolExecutor;

	//
	public ShutDownThread(final ThreadPoolExecutor threadPoolExecutor)
	{
		super("HibernateShutDown");
		this.threadPoolExecutor=threadPoolExecutor;
	}

	@Override
	public final void run()
	{
		final int reqTimeOutMilliSec=15;
		if(!threadPoolExecutor.isShutdown()) {
			threadPoolExecutor.shutdown();
		}
		if(!threadPoolExecutor.isTerminated()) {
			try {
				threadPoolExecutor.awaitTermination(reqTimeOutMilliSec, TimeUnit.SECONDS);
			} catch(final InterruptedException e) {
				HibernateAsyncAppender.LOGGER.debug("Interrupted waiting for submit executor to finish");
			}
		}
		if(threadPoolExecutor.getTaskCount()!=threadPoolExecutor.getCompletedTaskCount()){
			final long failed = threadPoolExecutor.getTaskCount() - threadPoolExecutor.getCompletedTaskCount();
			HibernateAsyncAppender.LOGGER.debug("Failed tasks: ", failed);
		}
	}
}
//////////////////////////////////////