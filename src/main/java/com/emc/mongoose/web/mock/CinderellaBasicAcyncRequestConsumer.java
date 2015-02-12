package com.emc.mongoose.web.mock;

import com.emc.mongoose.util.conf.RunTimeConfig;
import com.emc.mongoose.util.io.HTTPContentInputStream;
import com.emc.mongoose.util.logging.TraceLogger;
import com.emc.mongoose.web.api.impl.WSRequestConfigBase;
import org.apache.http.HttpEntity;
import org.apache.http.HttpEntityEnclosingRequest;
import org.apache.http.HttpRequest;
import org.apache.http.entity.ContentType;
import org.apache.http.nio.ContentDecoder;
import org.apache.http.nio.IOControl;
import org.apache.http.nio.entity.ContentBufferEntity;
import org.apache.http.nio.protocol.BasicAsyncRequestConsumer;
import org.apache.http.nio.util.HeapByteBufferAllocator;
import org.apache.http.nio.util.SimpleInputBuffer;
import org.apache.http.protocol.HttpContext;
import org.apache.http.util.Asserts;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.InputStream;

/**
 * Created by olga on 04.02.15.
 */
public class CinderellaBasicAcyncRequestConsumer
extends BasicAsyncRequestConsumer {
	//
	private final static Logger LOG = LogManager.getLogger();
	//
	private volatile HttpRequest request;
	private volatile SimpleInputBuffer buf;
	private static int MAX_PAGE_SIZE;
	//
	public CinderellaBasicAcyncRequestConsumer(final RunTimeConfig runTimeConfig) {
		super();
		MAX_PAGE_SIZE = (int) runTimeConfig.getDataPageSize();
	}
	//
	@Override
	protected void onRequestReceived(final HttpRequest request) throws IOException {
		this.request = request;
	}
	//
	@Override
	protected void onEntityEnclosed(
		final HttpEntity entity, final ContentType contentType) throws IOException {
		long len = entity.getContentLength();
		//
		if (len < 0 || len > MAX_PAGE_SIZE) {
			len = 4096;
		}
		this.buf = new SimpleInputBuffer((int) len, new HeapByteBufferAllocator());
		((HttpEntityEnclosingRequest) this.request).setEntity(
			new ContentBufferEntity(entity, this.buf));
	}
	//
	@Override
	protected void onContentReceived(
		final ContentDecoder decoder, final IOControl ioctrl)
	throws IOException {
		Asserts.notNull(this.buf, "Content buffer");
		//this.buf.consumeContent(decoder);
		try (final InputStream contentStream = HTTPContentInputStream.getInstance(decoder, ioctrl)) {
			WSRequestConfigBase.playStreamQuetly(contentStream);
			this.buf.shutdown();
		} catch (final InterruptedException e) {
			TraceLogger.failure(LOG, Level.ERROR, e, "Buffer interrupted fault");
		}
	}
		//
	@Override
	protected void releaseResources() {
		this.request = null;
		this.buf = null;
	}
	//
	@Override
	protected HttpRequest buildResult(final HttpContext context) {
		return this.request;
	}
}
