package com.emc.mongoose.object.api.provider.swift;
//
import com.emc.mongoose.object.api.WSRequestConfigBase;
import com.emc.mongoose.object.data.WSObject;
import com.emc.mongoose.util.conf.RunTimeConfig;
//
import org.apache.http.HttpRequest;
import org.apache.http.client.methods.HttpRequestBase;
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
	public WSRequestConfigImpl()
	throws NoSuchAlgorithmException {
		super();
		api = WSRequestConfigImpl.class.getSimpleName();
	}
	//
	@Override
	public WSRequestConfigImpl<T> clone()
	throws CloneNotSupportedException {
		final WSRequestConfigImpl copy = (WSRequestConfigImpl<T>) super.clone();
		// TODO add swift specific fields
		return copy;
	}
	//
	@Override
	public WSRequestConfigImpl<T> setProperties(final RunTimeConfig props) {
		super.setProperties(props);
		// TODO swift specific things
		return this;
	}
	//
	@Override @SuppressWarnings("unchecked")
	public final void readExternal(final ObjectInput in)
		throws IOException, ClassNotFoundException {
		super.readExternal(in);
		// TODO add swift specific fields
	}
	//
	@Override
	public final void writeExternal(final ObjectOutput out)
		throws IOException {
		super.writeExternal(out);
		// TODO add swift specific fields
	}
	//
	@Override
	protected final void applyURI(final HttpRequestBase httpRequest, final WSObject dataItem) {
		// TODO swift specific things
	}
	//
	@Override
	protected final void applyAuthHeader(final HttpRequestBase httpRequest) {
		// TODO swift specific things
	}
	//
	@Override
	public final String getCanonical(final HttpRequest httpRequest) {
		// TODO swift specific things
		return null;
	}
	//
}
