package com.emc.mongoose.web.load.impl;
//
import com.emc.mongoose.base.api.AsyncIOTask;
import com.emc.mongoose.base.data.persist.FileProducer;
import com.emc.mongoose.base.load.Producer;
import com.emc.mongoose.object.load.impl.ObjectLoadExecutorBase;
import com.emc.mongoose.run.Main;
import com.emc.mongoose.util.conf.RunTimeConfig;
import com.emc.mongoose.util.logging.TraceLogger;
import com.emc.mongoose.util.logging.Markers;
import com.emc.mongoose.util.threading.WorkerFactory;
import com.emc.mongoose.web.api.WSIOTask;
import com.emc.mongoose.web.api.WSRequestConfig;
import com.emc.mongoose.web.data.WSObject;
import com.emc.mongoose.web.data.impl.BasicWSObject;
import com.emc.mongoose.web.load.WSLoadExecutor;
//
import com.emc.mongoose.web.load.impl.reqproc.SharedHeaders;
import com.emc.mongoose.web.load.impl.reqproc.TargetHost;
import com.emc.mongoose.web.load.impl.tasks.ExecuteClientTask;
import org.apache.http.Header;
import org.apache.http.HttpHost;
import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;
import org.apache.http.config.ConnectionConfig;
import org.apache.http.impl.nio.DefaultHttpClientIODispatch;
import org.apache.http.impl.nio.pool.BasicNIOConnPool;
import org.apache.http.impl.nio.reactor.DefaultConnectingIOReactor;
import org.apache.http.impl.nio.reactor.IOReactorConfig;
import org.apache.http.nio.NHttpClientEventHandler;
import org.apache.http.nio.protocol.BasicAsyncRequestProducer;
import org.apache.http.nio.protocol.BasicAsyncResponseConsumer;
import org.apache.http.nio.protocol.HttpAsyncRequestExecutor;
import org.apache.http.nio.protocol.HttpAsyncRequester;
import org.apache.http.nio.reactor.ConnectingIOReactor;
import org.apache.http.nio.reactor.IOEventDispatch;
import org.apache.http.nio.reactor.IOReactorException;
import org.apache.http.protocol.HttpCoreContext;
import org.apache.http.protocol.HttpProcessor;
import org.apache.http.protocol.HttpProcessorBuilder;
import org.apache.http.protocol.RequestConnControl;
import org.apache.http.protocol.RequestContent;
import org.apache.http.protocol.RequestTargetHost;
import org.apache.http.protocol.RequestUserAgent;
//
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
//
import java.io.IOException;
import java.rmi.RemoteException;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
/**
 Created by kurila on 02.12.14.
 */
public class BasicLoadExecutor<T extends WSObject>
extends ObjectLoadExecutorBase<T>
implements WSLoadExecutor<T> {
	//
	private final static Logger LOG = LogManager.getLogger();
	//
	private final HttpAsyncRequester client;
	private final ConnectingIOReactor ioReactor;
	private final BasicNIOConnPool connPool;
	private final Thread clientThread;
	//
	public BasicLoadExecutor(
		final RunTimeConfig runTimeConfig, final WSRequestConfig<T> reqConfig, final String[] addrs,
		final int threadsPerNode, final String listFile, final long maxCount,
		final long sizeMin, final long sizeMax, final float sizeBias, final int countUpdPerReq
	) {
		super(
			runTimeConfig, reqConfig, addrs, threadsPerNode, listFile, maxCount,
			sizeMin, sizeMax, sizeBias, countUpdPerReq
		);
		//
		final int threadCount = threadsPerNode * addrs.length;
		final List<Header> sharedHeaders = reqConfig.getSharedHeaders();
		final String userAgent = runTimeConfig.getRunName() + "/" + runTimeConfig.getRunVersion();
		//
		final HttpProcessor httpProcessor= HttpProcessorBuilder
			.create()
			.add(new SharedHeaders(sharedHeaders))
			.add(new TargetHost())
			.add(new RequestConnControl())
			.add(new RequestUserAgent(userAgent))
			//.add(new RequestExpectContinue(true))
			.add(new RequestContent(true))
			.build();
		client = new HttpAsyncRequester(httpProcessor);
		//
		final RunTimeConfig thrLocalConfig = Main.RUN_TIME_CONFIG.get();
		//
		final NHttpClientEventHandler reqExecutor = new HttpAsyncRequestExecutor();
		//
		final ConnectionConfig connConfig = ConnectionConfig
			.custom()
			.setBufferSize((int) thrLocalConfig.getDataPageSize())
			.build();
		//
		final IOEventDispatch ioEventDispatch = new DefaultHttpClientIODispatch(
			reqExecutor, connConfig
		);
		//
		ConnectingIOReactor localIOReactor = null;
		BasicNIOConnPool localConnPool = null;
		final IOReactorConfig ioReactorConfig = IOReactorConfig
			.custom()
			.setIoThreadCount(threadCount)
			.setSoKeepAlive(thrLocalConfig.getSocketKeepAliveFlag())
			.setTcpNoDelay(thrLocalConfig.getSocketTCPNoDelayFlag())
			.setSoReuseAddress(thrLocalConfig.getSocketReuseAddrFlag())
			.setRcvBufSize((int) thrLocalConfig.getDataPageSize())
			.setSndBufSize((int) thrLocalConfig.getDataPageSize())
			.build();
		//
		try {
			localIOReactor = new DefaultConnectingIOReactor(
				ioReactorConfig, new WorkerFactory(getName() + "-worker")
			);
			//
			localConnPool = new BasicNIOConnPool(localIOReactor, connConfig);
			localConnPool.setDefaultMaxPerRoute(threadCount);
			localConnPool.setMaxTotal(threadCount);
		} catch(final IOReactorException e) {
			TraceLogger.failure(LOG, Level.FATAL, e, "Failed to build I/O reactor");
		} finally {
			ioReactor = localIOReactor;
			connPool = localConnPool;
		}
		//
		clientThread = new Thread(
			new ExecuteClientTask<>(this, ioEventDispatch, ioReactor),
			getName() + "-asyncWebClient"
		);
	}
	//
	@Override
	public synchronized void start() {
		clientThread.start();
		super.start();
	}
	//
	@Override
	public void close()
	throws IOException {
		super.close();
		LOG.debug(Markers.MSG, "Going to close the web storage client");
		/*final long timeOutMilliSec = Main.RUN_TIME_CONFIG.get().getRunReqTimeOutMilliSec();
		final ExecutorService closeExecutor = Executors.newFixedThreadPool(
			2, new WorkerFactory(getName()+"-closer")
		);
		closeExecutor.submit(
			new Runnable() {
				@Override
				public final void run() {*/
					try {
						ioReactor.shutdown(/*timeOutMilliSec*/);
					} catch(final IOException e) {
						TraceLogger.failure(LOG, Level.DEBUG, e, "I/O reactor shutdown failure");
					}
				/*}
			}
		);
		closeExecutor.submit(
			new Runnable() {
				@Override
				public final void run() {
					try {
						clientThread.join(timeOutMilliSec);
					} catch(final InterruptedException e) {
						ExceptionHandler.failure(LOG, Level.DEBUG, e, "Interruption");
					} finally {*/
						clientThread.interrupt();
					/*}
				}
			}
		);
		closeExecutor.shutdown();
		try {
			closeExecutor.awaitTermination(timeOutMilliSec, TimeUnit.MILLISECONDS);
		} catch(final InterruptedException e) {
			ExceptionHandler.failure(LOG, Level.DEBUG, e, "Interruption");
		} finally {
			closeExecutor.shutdownNow();
		}*/
	}
	//
	@Override
	public final Future<AsyncIOTask.Status> submit(final AsyncIOTask<T> ioTask)
	throws RemoteException {
		final WSIOTask<T> wsTask = (WSIOTask<T>) ioTask;
		Future<WSIOTask.Status> futureResult;
		try {
			futureResult = client.execute(
				wsTask, wsTask, connPool, wsTask.getHttpContext()
			);
		} catch(final IllegalStateException e) {
			throw new RemoteException("I/O task submit failure", e);
		}
		return futureResult;
	}
	//
	@Override
	public final HttpResponse execute(
		final HttpHost tgtHost, final HttpRequest request
	) {
		HttpResponse response = null;
		final HttpCoreContext ctx = new HttpCoreContext();
		ctx.setTargetHost(tgtHost);
		try {
			response = client.execute(
				new BasicAsyncRequestProducer(tgtHost, request),
				new BasicAsyncResponseConsumer(), connPool, ctx
			).get();
		} catch(final InterruptedException e) {
			if(!isTerminating() && !isTerminated()) {
				LOG.debug(Markers.ERR, "Interrupted during HTTP request execution");
			}
		} catch(final ExecutionException e) {
			if(!isTerminating() && !isTerminated()) {
				TraceLogger.failure(LOG, Level.WARN, e, "HTTP request execution failure");
			}
		}
		return response;
	}
	//
	@Override @SuppressWarnings("unchecked")
	protected Producer<T> newFileBasedProducer(final long maxCount, final String listFile) {
		Producer<T> localProducer = null;
		try {
			localProducer = (Producer<T>) new FileProducer<>(
				maxCount, listFile, BasicWSObject.class
			);
		} catch(final NoSuchMethodException e) {
			TraceLogger.failure(LOG, Level.FATAL, e, "Unexpected failure");
		} catch(final IOException e) {
			TraceLogger.failure(
				LOG, Level.ERROR, e,
				String.format("Failed to read the data items file \"%s\"", listFile)
			);
		}
		return localProducer;
	}
	//
	@Override @SuppressWarnings("unchecked")
	protected Producer<T> newDataProducer(
		final long maxCount, final long minObjSize, final long maxObjSize, final float objSizeBias
	) {
		return (Producer<T>) new BasicNewDataProducer<>(
			maxCount, minObjSize, maxObjSize, objSizeBias
		);
	}
}
