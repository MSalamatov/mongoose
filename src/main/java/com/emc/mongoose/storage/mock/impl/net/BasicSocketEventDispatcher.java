package com.emc.mongoose.storage.mock.impl.net;
// mongoose-common.jar
import com.emc.mongoose.common.concurrent.GroupThreadFactory;
import com.emc.mongoose.common.conf.Constants;
import com.emc.mongoose.common.conf.RunTimeConfig;
import com.emc.mongoose.common.log.LogUtil;
import com.emc.mongoose.common.log.Markers;
// mongoose-core-api.jar
import com.emc.mongoose.core.api.load.executor.LoadExecutor;
// mongoose-storage-mock.jar
import com.emc.mongoose.storage.mock.api.stats.IOStats;
//
import org.apache.http.impl.nio.DefaultHttpServerIODispatch;
import org.apache.http.impl.nio.DefaultNHttpServerConnection;
import org.apache.http.impl.nio.reactor.DefaultListeningIOReactor;
import org.apache.http.impl.nio.reactor.IOReactorConfig;
import org.apache.http.nio.NHttpConnectionFactory;
import org.apache.http.nio.protocol.HttpAsyncService;
import org.apache.http.nio.reactor.IOReactorException;
//import org.apache.http.nio.reactor.ListenerEndpoint;
import org.apache.http.nio.reactor.ListenerEndpoint;
import org.apache.http.nio.reactor.ListeningIOReactor;
//
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
//
import java.io.Closeable;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.InetSocketAddress;
/**
 Created by kurila on 13.05.15.
 */
public final class BasicSocketEventDispatcher
extends DefaultHttpServerIODispatch
implements Closeable, Runnable {
	//
	private final static Logger LOG = LogManager.getLogger();
	//
	private final ListeningIOReactor ioReactor;
	private final InetSocketAddress socketAddress;
	private final IOReactorConfig ioReactorConf;
	private final IOStats ioStats;
	//
	public BasicSocketEventDispatcher(
		final RunTimeConfig runTimeConfig,
		final HttpAsyncService protocolHandler, final int port,
		final NHttpConnectionFactory<DefaultNHttpServerConnection> connFactory,
		final IOStats ioStats
	) throws IOReactorException {
		super(protocolHandler, connFactory);
		socketAddress = new InetSocketAddress(port);
		// set I/O reactor configuration
		ioReactorConf = IOReactorConfig.custom()
			.setIoThreadCount(runTimeConfig.getStorageMockIoThreadsPerSocket())
			.setBacklogSize((int) runTimeConfig.getSocketBindBackLogSize())
			.setInterestOpQueued(runTimeConfig.getSocketInterestOpQueued())
			.setSelectInterval(runTimeConfig.getSocketSelectInterval())
			.setShutdownGracePeriod(runTimeConfig.getSocketTimeOut())
			.setSoKeepAlive(runTimeConfig.getSocketKeepAliveFlag())
			.setSoLinger(runTimeConfig.getSocketLinger())
			.setSoReuseAddress(runTimeConfig.getSocketReuseAddrFlag())
			.setSoTimeout(runTimeConfig.getSocketTimeOut())
			.setTcpNoDelay(runTimeConfig.getSocketTCPNoDelayFlag())
			.setRcvBufSize(Constants.BUFF_SIZE_LO)
			.setSndBufSize(Constants.BUFF_SIZE_LO)
			.setConnectTimeout(runTimeConfig.getConnTimeOut())
			.build();
		// create the server-side I/O reactor
		ioReactor = new DefaultListeningIOReactor(
			ioReactorConf, new GroupThreadFactory("ioReactor")
		);
		this.ioStats = ioStats;
	}
	//
	@Override
	public final void run() {
		//buffSizeAdjustDaemon.start();
		try {
			// Listen of the given port
			ioReactor.listen(socketAddress);
			// Ready to go!
			ioReactor.execute(this);
		} catch (final InterruptedIOException e) {
			LOG.debug(Markers.MSG, "{}: interrupted", this);
		} catch (final IOReactorException e) {
			LogUtil.exception(LOG, Level.WARN, e, "{}: I/O reactor failure", this);
		} catch (final IOException e) {
			LogUtil.exception(LOG, Level.WARN, e, "{}: I/O failure", this);
		} finally {
			try {
				ioReactor.shutdown();
			} catch(final IOException e) {
				LogUtil.exception(LOG, Level.WARN, e, "{}: I/O failure during the shutdown", this);
			}
		}
	}
	//
	@Override
	public final void close()
	throws IOException {
		ioReactor.shutdown();
		for(final ListenerEndpoint endpoint : ioReactor.getEndpoints()) {
			endpoint.close();
		}
	}
	//
	@Override
	public final String toString() {
		return BasicSocketEventDispatcher.class.getSimpleName() + ":" + socketAddress.getPort();
	}
	//
}
