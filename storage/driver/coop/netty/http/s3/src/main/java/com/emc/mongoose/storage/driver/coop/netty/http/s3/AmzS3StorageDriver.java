package com.emc.mongoose.storage.driver.coop.netty.http.s3;

import com.emc.mongoose.base.data.DataInput;
import com.emc.mongoose.base.exception.InterruptRunException;
import com.emc.mongoose.base.exception.OmgShootMyFootException;
import com.emc.mongoose.base.item.DataItem;
import com.emc.mongoose.base.item.Item;
import com.emc.mongoose.base.item.ItemFactory;
import com.emc.mongoose.base.item.op.OpType;
import com.emc.mongoose.base.item.op.Operation;
import com.emc.mongoose.base.item.op.composite.data.CompositeDataOperation;
import com.emc.mongoose.base.item.op.partial.data.PartialDataOperation;
import com.emc.mongoose.base.logging.LogUtil;
import com.emc.mongoose.base.logging.Loggers;
import com.emc.mongoose.base.storage.Credential;
import com.emc.mongoose.storage.driver.coop.netty.http.HttpStorageDriverBase;
import static com.emc.mongoose.base.item.op.Operation.SLASH;

import com.github.akurilov.confuse.Config;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufInputStream;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.EmptyHttpHeaders;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpStatusClass;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.AsciiString;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;

import org.apache.logging.log4j.Level;

import org.xml.sax.SAXException;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import static java.nio.charset.StandardCharsets.UTF_8;

/**
 Created by kurila on 01.08.16.
 */
public class AmzS3StorageDriver<I extends Item, O extends Operation<I>>
extends HttpStorageDriverBase<I, O> {

	private static final Base64.Encoder BASE64_ENCODER = Base64.getEncoder();
	private static final ThreadLocal<SAXParser> THREAD_LOCAL_XML_PARSER = new ThreadLocal<>();
	private static final ThreadLocal<StringBuilder>
		BUFF_CANONICAL = ThreadLocal.withInitial(StringBuilder::new),
		BUCKET_LIST_QUERY = ThreadLocal.withInitial(StringBuilder::new);
	private static final ThreadLocal<Map<String, Mac>> MAC_BY_SECRET = ThreadLocal.withInitial(HashMap::new);
	private static final Function<String, Mac> GET_MAC_BY_SECRET = secret -> {
		final SecretKeySpec secretKey = new SecretKeySpec(secret.getBytes(UTF_8), AmzS3Api.SIGN_METHOD);
		try {
			final Mac mac = Mac.getInstance(AmzS3Api.SIGN_METHOD);
			mac.init(secretKey);
			return mac;
		} catch(final NoSuchAlgorithmException | InvalidKeyException e) {
			throw new AssertionError(e);
		}
	};

	protected final boolean fsAccess;
	protected final boolean versioning;

	public AmzS3StorageDriver(
		final String stepId, final DataInput itemDataInput, final Config storageConfig, final boolean verifyFlag,
		final int batchSize
	) throws OmgShootMyFootException, InterruptedException {
		super(stepId, itemDataInput, storageConfig, verifyFlag, batchSize);
		final Config httpConfig = storageConfig.configVal("net-http");
		fsAccess = httpConfig.boolVal("fsAccess");
		versioning = httpConfig.boolVal("versioning");
		requestAuthTokenFunc = null; // do not use
	}

	@Override
	protected String requestNewPath(final String path)
	throws InterruptRunException {
		final String bucketUri = path.startsWith(SLASH) ? path : SLASH + path;
		// check the destination bucket if it exists w/ HEAD request
		final String nodeAddr = storageNodeAddrs[0];
		HttpHeaders reqHeaders = new DefaultHttpHeaders();
		reqHeaders.set(HttpHeaderNames.HOST, nodeAddr);
		reqHeaders.set(HttpHeaderNames.CONTENT_LENGTH, 0);
		reqHeaders.set(HttpHeaderNames.DATE, dateSupplier.get());
		applyDynamicHeaders(reqHeaders);
		applySharedHeaders(reqHeaders);
		final Credential credential = pathToCredMap.getOrDefault(bucketUri, this.credential);
		applyAuthHeaders(reqHeaders, HttpMethod.HEAD, bucketUri, credential);
		final FullHttpRequest checkBucketReq = new DefaultFullHttpRequest(
			HttpVersion.HTTP_1_1, HttpMethod.HEAD, bucketUri, Unpooled.EMPTY_BUFFER, reqHeaders,
			EmptyHttpHeaders.INSTANCE
		);
		final FullHttpResponse checkBucketResp;
		try {
			checkBucketResp = executeHttpRequest(checkBucketReq);
		} catch(final InterruptedException e) {
			throw new InterruptRunException(e);
		} catch(final ConnectException e) {
			LogUtil.exception(Level.WARN, e, "Failed to connect to the storage node");
			return null;
		}
		boolean bucketExistedBefore = true;
		if(checkBucketResp != null) {
			if(!HttpStatusClass.SUCCESS.equals(checkBucketResp.status().codeClass())) {
				Loggers.MSG.info("The bucket checking response is: {}", checkBucketResp.status().toString());
				bucketExistedBefore = false;
			}
			checkBucketResp.release();
		}
		// create the destination bucket if it doesn't exists
		if(!bucketExistedBefore) {
			reqHeaders = new DefaultHttpHeaders();
			reqHeaders.set(HttpHeaderNames.HOST, nodeAddr);
			reqHeaders.set(HttpHeaderNames.CONTENT_LENGTH, 0);
			reqHeaders.set(HttpHeaderNames.DATE, dateSupplier.get());
			applyMetaDataHeaders(reqHeaders);
			applyDynamicHeaders(reqHeaders);
			applySharedHeaders(reqHeaders);
			applyAuthHeaders(reqHeaders, HttpMethod.PUT, bucketUri, credential);
			final FullHttpRequest putBucketReq = new DefaultFullHttpRequest(
				HttpVersion.HTTP_1_1, HttpMethod.PUT, bucketUri, Unpooled.EMPTY_BUFFER, reqHeaders,
				EmptyHttpHeaders.INSTANCE
			);
			final FullHttpResponse putBucketResp;
			try {
				putBucketResp = executeHttpRequest(putBucketReq);
			} catch(final InterruptedException e) {
				throw new InterruptRunException(e);
			} catch(final ConnectException e) {
				LogUtil.exception(Level.WARN, e, "Failed to connect to the storage node");
				return null;
			}
			if(!HttpStatusClass.SUCCESS.equals(putBucketResp.status().codeClass())) {
				Loggers.ERR.warn("The bucket creating response is: {}", putBucketResp.status().toString());
				return null;
			}
			putBucketResp.release();
		}

		// check the bucket versioning state
		final String bucketVersioningReqUri = bucketUri + "?" + AmzS3Api.URL_ARG_VERSIONING;
		reqHeaders = new DefaultHttpHeaders();
		reqHeaders.set(HttpHeaderNames.HOST, nodeAddr);
		reqHeaders.set(HttpHeaderNames.CONTENT_LENGTH, 0);
		reqHeaders.set(HttpHeaderNames.DATE, dateSupplier.get());
		applyDynamicHeaders(reqHeaders);
		applySharedHeaders(reqHeaders);
		applyAuthHeaders(reqHeaders, HttpMethod.GET, bucketVersioningReqUri, credential);
		final FullHttpRequest getBucketVersioningReq = new DefaultFullHttpRequest(
			HttpVersion.HTTP_1_1, HttpMethod.GET, bucketVersioningReqUri, Unpooled.EMPTY_BUFFER, reqHeaders,
			EmptyHttpHeaders.INSTANCE
		);
		final FullHttpResponse getBucketVersioningResp;
		try {
			getBucketVersioningResp = executeHttpRequest(getBucketVersioningReq);
			if(getBucketVersioningResp == null) {
				Loggers.ERR.warn("Response timeout");
			} else {
				try {
					handleCheckBucketVersioningResponse(getBucketVersioningResp, nodeAddr, bucketVersioningReqUri);
				} finally {
					getBucketVersioningResp.release();
				}
			}
		} catch(final InterruptedException e) {
			throw new InterruptRunException(e);
		} catch(final ConnectException e) {
			LogUtil.exception(Level.WARN, e, "Failed to connect to the storage node");
		}
		return path;
	}

	protected void handleCheckBucketVersioningResponse(
		final FullHttpResponse getBucketVersioningResp, final String nodeAddr, final String bucketVersioningReqUri
	) {
		boolean versioningEnabled = false;
		if(!HttpStatusClass.SUCCESS.equals(getBucketVersioningResp.status().codeClass())) {
			Loggers.ERR.warn(
				"The bucket versioning checking response is: {}", getBucketVersioningResp.status().toString()
			);
		} else {
			final String content = getBucketVersioningResp
				.content()
				.toString(StandardCharsets.US_ASCII);
			versioningEnabled = content.contains("Enabled");
		}
		if(versioning && !versioningEnabled) {
			enableBucketVersioning(nodeAddr, bucketVersioningReqUri);
		}
	}

	protected void enableBucketVersioning(final String nodeAddr, final String bucketVersioningReqUri) {
		final HttpHeaders reqHeaders = new DefaultHttpHeaders();
		reqHeaders.set(HttpHeaderNames.HOST, nodeAddr);
		reqHeaders.set(HttpHeaderNames.DATE, dateSupplier.get());
		reqHeaders.set(HttpHeaderNames.CONTENT_LENGTH, AmzS3Api.VERSIONING_ENABLE_CONTENT.length);
		applyAuthHeaders(reqHeaders, HttpMethod.PUT, bucketVersioningReqUri, credential);
		final FullHttpRequest putBucketVersioningReq = new DefaultFullHttpRequest(
			HttpVersion.HTTP_1_1, HttpMethod.PUT, bucketVersioningReqUri,
			Unpooled.wrappedBuffer(AmzS3Api.VERSIONING_ENABLE_CONTENT).retain(), reqHeaders,
			EmptyHttpHeaders.INSTANCE
		);
		final FullHttpResponse putBucketVersioningResp;
		try {
			putBucketVersioningResp = executeHttpRequest(putBucketVersioningReq);
			if(!HttpStatusClass.SUCCESS.equals(putBucketVersioningResp.status().codeClass())) {
				Loggers.ERR.warn(
					"The bucket versioning setting response is: {}", putBucketVersioningResp.status().toString()
				);
			}
			putBucketVersioningResp.release();
		} catch(final InterruptedException e) {
			throw new InterruptRunException(e);
		} catch(final ConnectException e) {
			LogUtil.exception(Level.WARN, e, "Failed to connect to the storage node");
		}
	}

	@Override
	protected final String requestNewAuthToken(final Credential credential) {
		throw new AssertionError("Should not be invoked");
	}

	@Override
	public final List<I> list(
		final ItemFactory<I> itemFactory, final String path, final String prefix, final int idRadix,
		final I lastPrevItem, final int count
	) throws InterruptRunException, IOException {
		final int countLimit = count < 1 || count > AmzS3Api.MAX_KEYS_LIMIT ? AmzS3Api.MAX_KEYS_LIMIT : count;
		final String nodeAddr = storageNodeAddrs[0];
		final HttpHeaders reqHeaders = new DefaultHttpHeaders();
		reqHeaders.set(HttpHeaderNames.HOST, nodeAddr);
		reqHeaders.set(HttpHeaderNames.CONTENT_LENGTH, 0);
		reqHeaders.set(HttpHeaderNames.DATE, dateSupplier.get());
		applyDynamicHeaders(reqHeaders);
		applySharedHeaders(reqHeaders);
		final StringBuilder queryBuilder = BUCKET_LIST_QUERY.get();
		queryBuilder.setLength(0);
		queryBuilder.append(path).append('?');
		if(prefix != null && !prefix.isEmpty()) {
			queryBuilder.append("prefix=").append(prefix);
		}
		if(lastPrevItem != null) {
			if('?' != queryBuilder.charAt(queryBuilder.length() - 1)) {
				queryBuilder.append('&');
			}
			String lastItemName = lastPrevItem.name();
			if(lastItemName.contains("/")) {
				lastItemName = lastItemName.substring(lastItemName.lastIndexOf('/') + 1);
			}
			queryBuilder.append("marker=").append(lastItemName);
		}
		if('?' != queryBuilder.charAt(queryBuilder.length() - 1)) {
			queryBuilder.append('&');
		}
		queryBuilder.append("max-keys=").append(countLimit);
		final String query = queryBuilder.toString();
		applyAuthHeaders(reqHeaders, HttpMethod.GET, path, credential);
		final FullHttpRequest checkBucketReq = new DefaultFullHttpRequest(
			HttpVersion.HTTP_1_1, HttpMethod.GET, query, Unpooled.EMPTY_BUFFER, reqHeaders,
			EmptyHttpHeaders.INSTANCE
		);
		final List<I> buff = new ArrayList<>(countLimit);
		try {
			final FullHttpResponse listResp = executeHttpRequest(checkBucketReq);
			try {
				final ByteBuf listRespContent = listResp.content();
				SAXParser listRespParser = THREAD_LOCAL_XML_PARSER.get();
				if(listRespParser == null) {
					listRespParser = SAXParserFactory.newInstance().newSAXParser();
					THREAD_LOCAL_XML_PARSER.set(listRespParser);
				} else {
					listRespParser.reset();
				}
				final BucketXmlListingHandler<I> listingHandler = new BucketXmlListingHandler<>(
					buff, path, itemFactory, idRadix
				);
				try(final InputStream contentStream = new ByteBufInputStream(listRespContent)) {
					listRespParser.parse(contentStream, listingHandler);
				}
				if(buff.size() == 0) {
					throw new EOFException();
				}
				if(!listingHandler.isTruncated()) {
					buff.add(null); // poison
				}
			} finally {
				listResp.release();
			}
		} catch(final InterruptedException e) {
			throw new InterruptRunException(e);
		} catch(final SAXException | ParserConfigurationException e) {
			LogUtil.exception(Level.WARN, e, "Failed to init the XML response parser");
		} catch(final ConnectException e) {
			LogUtil.exception(Level.WARN, e, "Failed to connect to the storage node");
		} catch(final NullPointerException e) {
			LogUtil.exception(Level.WARN, e, "Timeout response");
		}
		return buff;
	}

	@Override
	protected HttpRequest httpRequest(final O op, final String nodeAddr)
	throws URISyntaxException {
		final HttpRequest httpRequest;
		final OpType opType = op.type();
		if(op instanceof CompositeDataOperation) {
			if(OpType.CREATE.equals(opType)) {
				final CompositeDataOperation mpuOp = (CompositeDataOperation) op;
				if(mpuOp.allSubOperationsDone()) {
					httpRequest = getCompleteMpuRequest(mpuOp, nodeAddr);
				} else { // this is the initial state of the task
					httpRequest = getInitMpuRequest(op, nodeAddr);
				}
			} else {
				throw new AssertionError("Non-create multipart operations are not implemented yet");
			}
		} else if(op instanceof PartialDataOperation) {
			if(OpType.CREATE.equals(opType)) {
				httpRequest = getUploadPartRequest((PartialDataOperation) op, nodeAddr);
			} else {
				throw new AssertionError("Non-create multipart operations are not implemented yet");
			}
		} else {
			httpRequest = super.httpRequest(op, nodeAddr);
		}
		return httpRequest;
	}

	@Override
	protected final HttpMethod tokenHttpMethod(final OpType opType) {
		throw new AssertionError("Not implemented yet");
	}

	@Override
	protected final HttpMethod pathHttpMethod(final OpType opType) {
		switch(opType) {
			case UPDATE:
				throw new AssertionError("Not implemented yet");
			case READ:
				return HttpMethod.GET;
			case DELETE:
				return HttpMethod.DELETE;
			default:
				return HttpMethod.PUT;
		}
	}

	@Override
	protected final String tokenUriPath(final I item, final String srcPath, final String dstPath, final OpType opType) {
		throw new AssertionError("Not implemented");
	}

	@Override
	protected final String pathUriPath(final I item, final String srcPath, final String dstPath, final OpType opType) {
		final String itemName = item.name();
		if(itemName.startsWith(SLASH)) {
			return itemName;
		} else {
			return SLASH + itemName;
		}
	}

	@Override
	protected void applyMetaDataHeaders(final HttpHeaders httpHeaders) {
	}

	private HttpRequest getInitMpuRequest(final O op, final String nodeAddr) {
		final I item = op.item();
		final String srcPath = op.srcPath();
		if(srcPath != null && !srcPath.isEmpty()) {
			throw new AssertionError(
				"Multipart copy operation is not implemented yet"
			);
		}
		final String uriPath = dataUriPath(item, srcPath, op.dstPath(), OpType.CREATE) + "?uploads";
		final HttpHeaders httpHeaders = new DefaultHttpHeaders();
		if(nodeAddr != null) {
			httpHeaders.set(HttpHeaderNames.HOST, nodeAddr);
		}
		httpHeaders.set(HttpHeaderNames.DATE, dateSupplier.get());
		httpHeaders.set(HttpHeaderNames.CONTENT_LENGTH, 0);
		final HttpMethod httpMethod = HttpMethod.POST;
		final HttpRequest httpRequest = new DefaultHttpRequest(
			HTTP_1_1, httpMethod, uriPath, httpHeaders
		);
		applyMetaDataHeaders(httpHeaders);
		applyDynamicHeaders(httpHeaders);
		applySharedHeaders(httpHeaders);
		applyAuthHeaders(httpHeaders, httpMethod, uriPath, op.credential());
		return httpRequest;
	}

	private HttpRequest getUploadPartRequest(final PartialDataOperation partialDataOp, final String nodeAddr) {
		final I item = (I) partialDataOp.item();
		final String srcPath = partialDataOp.srcPath();
		final String uriPath = dataUriPath(item, srcPath, partialDataOp.dstPath(), OpType.CREATE)
			+ "?partNumber=" + (partialDataOp.partNumber() + 1)
			+ "&uploadId=" + partialDataOp.parent().get(AmzS3Api.KEY_UPLOAD_ID);
		final HttpHeaders httpHeaders = new DefaultHttpHeaders();
		if(nodeAddr != null) {
			httpHeaders.set(HttpHeaderNames.HOST, nodeAddr);
		}
		httpHeaders.set(HttpHeaderNames.DATE, dateSupplier.get());
		final HttpMethod httpMethod = HttpMethod.PUT;
		final HttpRequest httpRequest = new DefaultHttpRequest(
			HTTP_1_1, httpMethod, uriPath, httpHeaders
		);
		try {
			httpHeaders.set(HttpHeaderNames.CONTENT_LENGTH, ((DataItem) item).size());
		} catch(final IOException ignored) {
		}
		applyMetaDataHeaders(httpHeaders);
		applyDynamicHeaders(httpHeaders);
		applySharedHeaders(httpHeaders);
		applyAuthHeaders(httpHeaders, httpMethod, uriPath, partialDataOp.credential());
		return httpRequest;
	}

	private final static ThreadLocal<StringBuilder> THREAD_LOCAL_STRB = ThreadLocal.withInitial(StringBuilder::new);

	private FullHttpRequest getCompleteMpuRequest(final CompositeDataOperation mpuTask, final String nodeAddr) {
		final StringBuilder content = THREAD_LOCAL_STRB.get();
		content.setLength(0);
		content.append(AmzS3Api.COMPLETE_MPU_HEADER);
		final List<PartialDataOperation> subTasks = mpuTask.subOperations();
		int nextPartNum;
		String nextEtag;
		for(final PartialDataOperation subTask : subTasks) {
			nextPartNum = subTask.partNumber() + 1;
			nextEtag = mpuTask.get(Integer.toString(nextPartNum));
			content
				.append(AmzS3Api.COMPLETE_MPU_PART_NUM_START)
				.append(nextPartNum)
				.append(AmzS3Api.COMPLETE_MPU_PART_NUM_END)
				.append(nextEtag)
				.append(AmzS3Api.COMPLETE_MPU_PART_ETAG_END);
		}
		content.append(AmzS3Api.COMPLETE_MPU_FOOTER);
		final String srcPath = mpuTask.srcPath();
		final I item = (I) mpuTask.item();
		final String uploadId = mpuTask.get(AmzS3Api.KEY_UPLOAD_ID);
		final String uriPath = dataUriPath(item, srcPath, mpuTask.dstPath(), OpType.CREATE) +
			"?uploadId=" + uploadId;
		final HttpHeaders httpHeaders = new DefaultHttpHeaders();
		httpHeaders.set(HttpHeaderNames.HOST, nodeAddr);
		httpHeaders.set(HttpHeaderNames.DATE, dateSupplier.get());
		final HttpMethod httpMethod = HttpMethod.POST;
		final String contentStr = content.toString();
		final FullHttpRequest httpRequest = new DefaultFullHttpRequest(
			HTTP_1_1, httpMethod, uriPath, Unpooled.wrappedBuffer(contentStr.getBytes()),
			httpHeaders, EmptyHttpHeaders.INSTANCE
		);
		httpHeaders.set(HttpHeaderNames.CONTENT_LENGTH, content.length());
		applyMetaDataHeaders(httpHeaders);
		applyDynamicHeaders(httpHeaders);
		applySharedHeaders(httpHeaders);
		applyAuthHeaders(httpHeaders, httpMethod, uriPath, mpuTask.credential());
		return httpRequest;
	}

	@Override
	public void complete(final Channel channel, final O op) {
		if(channel != null && op instanceof CompositeDataOperation) {
			final CompositeDataOperation compositeOp = (CompositeDataOperation) op;
			if(compositeOp.allSubOperationsDone()) {
				Loggers.MULTIPART.info(
					"{},{},{}", compositeOp.item().name(), compositeOp.get(AmzS3Api.KEY_UPLOAD_ID),
					compositeOp.latency()
				);
			} else {
				final String uploadId = channel.attr(AmzS3Api.KEY_ATTR_UPLOAD_ID).get();
				if(uploadId == null) {
					op.status(Operation.Status.RESP_FAIL_NOT_FOUND);
				} else {
					// multipart upload has been initialized as a result of this load operation
					compositeOp.put(AmzS3Api.KEY_UPLOAD_ID, uploadId);
				}
			}
		}
		super.complete(channel, op);
	}

	@Override
	protected final void appendHandlers(final Channel channel) {
		super.appendHandlers(channel);
		channel.pipeline().addLast(new AmzS3ResponseHandler<>(this, verifyFlag));
	}

	@Override
	protected final void applyCopyHeaders(final HttpHeaders httpHeaders, final String srcPath)
	throws URISyntaxException {
		httpHeaders.set(AmzS3Api.KEY_X_AMZ_COPY_SOURCE, srcPath);
	}

	@Override
	protected final void applyAuthHeaders(
		final HttpHeaders httpHeaders, final HttpMethod httpMethod, final String dstUriPath, final Credential credential
	) {
		final String uid;
		final String secret;
		if(credential != null) {
			uid = credential.getUid();
			secret = credential.getSecret();
		} else if(this.credential != null) {
			uid = this.credential.getUid();
			secret = this.credential.getSecret();
		} else {
			return;
		}
		if(uid == null || secret == null) {
			return;
		}
		final Mac mac = MAC_BY_SECRET.get().computeIfAbsent(secret, GET_MAC_BY_SECRET);
		final String canonicalForm = getCanonical(httpHeaders, httpMethod, dstUriPath);
		final byte sigData[] = mac.doFinal(canonicalForm.getBytes());
		httpHeaders.set(
			HttpHeaderNames.AUTHORIZATION, AmzS3Api.AUTH_PREFIX + uid + ':' + BASE64_ENCODER.encodeToString(sigData)
		);
	}

	protected String getCanonical(final HttpHeaders httpHeaders, final HttpMethod httpMethod, final String dstUriPath) {
		final StringBuilder buffCanonical = BUFF_CANONICAL.get();
		buffCanonical.setLength(0); // reset/clear
		buffCanonical.append(httpMethod.name());
		for(final AsciiString headerName : AmzS3Api.HEADERS_CANONICAL) {
			if(httpHeaders.contains(headerName)) {
				for(final String headerValue : httpHeaders.getAll(headerName)) {
					buffCanonical.append('\n').append(headerValue);
				}
			} else if(sharedHeaders != null && sharedHeaders.contains(headerName)) {
				buffCanonical.append('\n').append(sharedHeaders.get(headerName));
			} else {
				buffCanonical.append('\n');
			}
		}
		// x-amz-*
		String headerName;
		final Map<String, String> sortedHeaders = new TreeMap<>();
		if(sharedHeaders != null) {
			for(final Map.Entry<String, String> header : sharedHeaders) {
				headerName = header.getKey().toLowerCase();
				if(headerName.startsWith(AmzS3Api.PREFIX_KEY_X_AMZ)) {
					sortedHeaders.put(headerName, header.getValue());
				}
			}
		}
		for(final Map.Entry<String, String> header : httpHeaders) {
			headerName = header.getKey().toLowerCase();
			if(headerName.startsWith(AmzS3Api.PREFIX_KEY_X_AMZ)) {
				sortedHeaders.put(headerName, header.getValue());
			}
		}
		for(final Map.Entry<String, String> sortedHeader : sortedHeaders.entrySet()) {
			buffCanonical
				.append('\n').append(sortedHeader.getKey())
				.append(':').append(sortedHeader.getValue());
		}
		buffCanonical.append('\n');
		buffCanonical.append(dstUriPath);
		if(Loggers.MSG.isTraceEnabled()) {
			Loggers.MSG.trace("Canonical representation:\n{}", buffCanonical);
		}
		return buffCanonical.toString();
	}

	@Override
	public String toString() {
		return String.format(super.toString(), "amzs3");
	}
}
