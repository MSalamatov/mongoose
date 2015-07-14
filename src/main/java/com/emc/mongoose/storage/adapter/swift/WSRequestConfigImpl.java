package com.emc.mongoose.storage.adapter.swift;
// mongoose-common.jar
import com.emc.mongoose.common.conf.RunTimeConfig;
import com.emc.mongoose.common.log.LogUtil;
import com.emc.mongoose.common.log.Markers;
// mongoose-core-api.jar
import com.emc.mongoose.core.api.load.model.Producer;
import com.emc.mongoose.core.api.io.req.MutableWSRequest;
import com.emc.mongoose.core.api.data.WSObject;
// mongoose-core-impl.jar
import com.emc.mongoose.core.impl.data.BasicWSObject;
import com.emc.mongoose.core.impl.io.req.conf.WSRequestConfigBase;
//
import com.emc.mongoose.core.impl.load.model.DataItemInputProducer;
import org.apache.http.Header;
import org.apache.http.message.BasicHeader;
//
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
//
import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.security.NoSuchAlgorithmException;
//
/**
 Created by kurila on 26.03.14.
 */
public final class WSRequestConfigImpl<T extends WSObject>
extends WSRequestConfigBase<T> {
	//
	private final static Logger LOG = LogManager.getLogger();
	public final static String KEY_CONF_SVC_BASEPATH = "api.type.swift.serviceBasepath";
	public final static String KEY_X_AUTH_TOKEN = "X-Auth-Token";
	public final static String KEY_X_AUTH_USER = "X-Auth-User";
	public final static String KEY_X_AUTH_KEY = "X-Auth-Key";
	public final static String KEY_X_VERSIONING = "X-Versions-Location";
	public final static String DEFAULT_VERSIONS_CONTAINER = "archive";
	//
	private String uriSvcBasePath = null, uriSvcBaseContainerPath = null;
	private WSAuthTokenImpl<T> authToken = null;
	private WSContainerImpl<T> container = null;
	//
	public WSRequestConfigImpl()
	throws NoSuchAlgorithmException {
		this(null);
	}
	//
	protected WSRequestConfigImpl(final WSRequestConfigImpl<T> reqConf2Clone)
	throws NoSuchAlgorithmException {
		super(reqConf2Clone);
		//
		if(reqConf2Clone != null) {
			if(reqConf2Clone.uriSvcBasePath != null) {
				uriSvcBasePath = reqConf2Clone.uriSvcBasePath;
			}
			if(reqConf2Clone.uriSvcBaseContainerPath != null) {
				uriSvcBaseContainerPath = reqConf2Clone.uriSvcBaseContainerPath;
			}
			if(reqConf2Clone.sharedHeaders.containsHeader(KEY_X_VERSIONING)) {
				sharedHeaders.updateHeader(
					reqConf2Clone.sharedHeaders.getFirstHeader(KEY_X_VERSIONING)
				);
			}
			setAuthToken(reqConf2Clone.getAuthToken());
			setContainer(reqConf2Clone.getContainer());
		}
		//
		final RunTimeConfig localConfig = RunTimeConfig.getContext();
		if(uriSvcBasePath == null) {
			uriSvcBasePath = localConfig.getString(KEY_CONF_SVC_BASEPATH);
		}
		if(authToken == null) {
			setAuthToken(new WSAuthTokenImpl<>(this, localConfig.getString(RunTimeConfig.KEY_API_SWIFT_AUTH_TOKEN)));
		}
		if(container == null) {
			setContainer(
				new WSContainerImpl<>(
					this, localConfig.getString(RunTimeConfig.KEY_API_SWIFT_CONTAINER),
					localConfig.getDataVersioningEnabled()
				)
			);
		}
		//
		refreshContainerPath();
	}
	//
	private void refreshContainerPath() {
		if(uriSvcBasePath == null) {
			LOG.debug(Markers.MSG, "Swift API URI base path is <null>, not refreshing the container path");
			return;
		}
		final String nameSpace = getNameSpace();
		if(nameSpace == null) {
			LOG.debug(Markers.MSG, "Swift namespace is <null>, not refreshing the container path");
			return;
		}
		if(container == null) {
			LOG.debug(Markers.MSG, "Swift container is <null>, not refreshing the container path");
			return;
		}
		uriSvcBaseContainerPath = "/"+uriSvcBasePath+"/"+nameSpace+"/"+container.getName();
	}
	//
	public final String getSvcBasePath() {
		return uriSvcBasePath;
	}
	//
	public final WSAuthTokenImpl<T> getAuthToken() {
		return authToken;
	}
	//
	public final WSRequestConfigImpl<T> setAuthToken(final WSAuthTokenImpl<T> authToken)
	throws IllegalArgumentException {
		if(authToken == null) {
			throw new IllegalArgumentException("Setting <null> auth token is illegal");
		}
		this.authToken = authToken;
		return this;
	}
	//
	public final WSContainerImpl<T> getContainer() {
		return container;
	}
	//
	public final WSRequestConfigImpl<T> setContainer(final WSContainerImpl<T> container)
	throws IllegalArgumentException, IllegalStateException {
		if(container == null) {
			throw new IllegalArgumentException("Setting <null> container is illegal");
		}
		this.container = container;
		refreshContainerPath();
		return this;
	}
	//
	@Override
	public final WSRequestConfigImpl<T> setNameSpace(final String nameSpace) {
		super.setNameSpace(nameSpace);
		refreshContainerPath();
		return this;
	}
	//
	@Override @SuppressWarnings("CloneDoesntCallSuperClone")
	public WSRequestConfigImpl<T> clone() {
		WSRequestConfigImpl<T> copy = null;
		try {
			copy = new WSRequestConfigImpl<>(this);
		} catch(final NoSuchAlgorithmException e) {
			LOG.fatal(Markers.ERR, "No such algorithm: \"{}\"", signMethod);
		}
		return copy;
	}
	//
	@Override
	public WSRequestConfigImpl<T> setProperties(final RunTimeConfig runTimeConfig) {
		super.setProperties(runTimeConfig);
		//
		if(runTimeConfig.containsKey(KEY_CONF_SVC_BASEPATH)) {
			uriSvcBasePath = runTimeConfig.getString(KEY_CONF_SVC_BASEPATH);
		} else {
			LOG.error(Markers.ERR, "Swift base uri path is not specified");
		}
		//
		if(runTimeConfig.containsKey(RunTimeConfig.KEY_API_SWIFT_AUTH_TOKEN)) {
			authToken = new WSAuthTokenImpl<>(this, runTimeConfig.getString(RunTimeConfig.KEY_API_SWIFT_AUTH_TOKEN));
		} else {
			LOG.error(Markers.ERR, "Swift auth token is not specified");
		}
		//
		if(runTimeConfig.containsKey(RunTimeConfig.KEY_API_SWIFT_CONTAINER)) {
			container = new WSContainerImpl<>(
				this, runTimeConfig.getString(RunTimeConfig.KEY_API_SWIFT_CONTAINER),
				runTimeConfig.getDataVersioningEnabled()
			);
		} else {
			LOG.error(Markers.ERR, "Swift container is not specified");
		}
		//
		if(runTimeConfig.getDataVersioningEnabled()) {
			sharedHeaders.updateHeader(
				new BasicHeader(KEY_X_VERSIONING, DEFAULT_VERSIONS_CONTAINER)
			);
		} else if(sharedHeaders.containsHeader(KEY_X_VERSIONING)) {
			for(final Header header2remove : sharedHeaders.getHeaders(KEY_X_VERSIONING)) {
				sharedHeaders.removeHeader(header2remove);
			}
		}
		//
		refreshContainerPath();
		return this;
	}
	//
	@Override @SuppressWarnings("unchecked")
	public final void readExternal(final ObjectInput in)
	throws IOException, ClassNotFoundException {
		super.readExternal(in);
		uriSvcBasePath = String.class.cast(in.readObject());
		Object t = in.readObject();
		if(t != null) {
			setAuthToken(new WSAuthTokenImpl<>(this, String.class.cast(t)));
		} else {
			LOG.debug(Markers.MSG, "Note: no auth token has been got from load client side");
		}
		t = in.readObject();
		if(t != null) {
			setContainer(
				new WSContainerImpl<>(
					this, String.class.cast(t), runTimeConfig.getDataVersioningEnabled()
				)
			);
		} else {
			LOG.debug(Markers.MSG, "Note: no container has been got from load client side");
		}
	}
	//
	@Override
	public final void writeExternal(final ObjectOutput out)
	throws IOException {
		super.writeExternal(out);
		out.writeObject(uriSvcBasePath);
		out.writeObject(authToken == null ? null : authToken.getValue());
		out.writeObject(container == null ? null : container.getName());
	}
	//
	@Override
	protected final void applyURI(final MutableWSRequest httpRequest, final T dataItem)
	throws IllegalArgumentException {
		if(uriSvcBaseContainerPath == null) {
			LOG.warn(Markers.ERR, "Illegal URI template: <null>");
		}
		if(dataItem == null) {
			throw new IllegalArgumentException("Illegal data item: <null>");
		}
		httpRequest.setUriPath(uriSvcBaseContainerPath + getPathFor(dataItem));
	}
	//
	private Header headerAuthToken = null;
	//
	@Override
	protected final void applyAuthHeader(final MutableWSRequest httpRequest) {
		final String authTokenValue = authToken == null ? null : authToken.getValue();
		if(authTokenValue != null) {
			if(!httpRequest.containsHeader(KEY_X_AUTH_TOKEN)) {
				if(headerAuthToken == null || headerAuthToken.getValue() != authTokenValue) {
					headerAuthToken = new BasicHeader(KEY_X_AUTH_TOKEN, authTokenValue);
				}
				httpRequest.setHeader(headerAuthToken);
			}
		}
	}
	//
	@Override
	public final String getCanonical(final MutableWSRequest httpRequest) {
		return "";
	}
	//
	@Override
	public final void configureStorage(final String storageNodeAddrs[])
	throws IllegalStateException {
		// configure an auth token - create if not specified
		String authTokenValue;
		if(authToken == null) {
			throw new IllegalStateException("No auth token specified");
		} else {
			authTokenValue = authToken.getValue();
			if(authTokenValue == null || authTokenValue.length() < 1) {
				authToken.create(storageNodeAddrs[0]);
				authTokenValue = authToken.getValue();
			}
		}
		if(authTokenValue == null) {
			throw new IllegalStateException("No auth token was created");
		}
		sharedHeaders.updateHeader(new BasicHeader(KEY_X_AUTH_TOKEN, authTokenValue));
		runTimeConfig.set(RunTimeConfig.KEY_API_SWIFT_AUTH_TOKEN, authTokenValue);
		// configure a container
		if(container == null) {
			throw new IllegalStateException("Container is not specified");
		}
		final String containerName = container.getName();
		if(container.exists(storageNodeAddrs[0])) {
			LOG.info(Markers.MSG, "Container \"{}\" already exists", containerName);
		} else {
			container.create(storageNodeAddrs[0]);
			if(container.exists(storageNodeAddrs[0])) {
				runTimeConfig.set(RunTimeConfig.KEY_API_SWIFT_CONTAINER, containerName);
			} else {
				throw new IllegalStateException(
					String.format("Container \"%s\" still doesn't exist", containerName)
				);
			}
		}

	}
	//
	@Override
	public final Producer<T> getAnyDataProducer(final long maxCount, final String addr) {
		Producer<T> producer = null;
		if(anyDataProducerEnabled) {
			try {
				producer = new DataItemInputProducer<>(
					new WSContainerItemInput<>(container, addr, (Class<T>) BasicWSObject.class)
				);
			} catch(final Exception e) {
				LogUtil.exception(LOG, Level.ERROR, e, "Unexpected failure");
			}
		} else {
			LOG.debug(
				Markers.MSG, "req conf {}: using of bucket listing data producer is suppressed",
				hashCode()
			);
		}
		return producer;
	}
	//
}
