package com.emc.mongoose.core.impl.data.content;
//
import com.emc.mongoose.common.conf.Constants;
import com.emc.mongoose.common.log.LogUtil;
//
import com.emc.mongoose.core.api.data.content.ContentSource;
//
import org.apache.commons.lang.SerializationUtils;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
//
import java.io.EOFException;
import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
/**
 Created by kurila on 16.10.15.
 */
public abstract class ContentSourceBase
implements ContentSource {
	//
	private final static Logger LOG = LogManager.getLogger();
	//
	protected ByteBuffer zeroByteLayer;
	protected final List<ByteBuffer> byteLayers = new ArrayList<>();
	//
	protected ContentSourceBase(final ByteBuffer zeroByteLayer) {
		this.zeroByteLayer = zeroByteLayer;
		byteLayers.add(zeroByteLayer);
	}
	//
	protected ContentSourceBase(final ReadableByteChannel zeroLayerSrcChan, final int size)
	throws IOException {
		this.zeroByteLayer = ByteBuffer.allocateDirect(size);
		byteLayers.add(zeroByteLayer);
		int n = 0, m;
		do {
			m = zeroLayerSrcChan.read(zeroByteLayer);
			if(m < 0) {
				break;
			} else {
				n += m;
			}
		} while(n < size);
	}
	//
	@Override
	public final int getSize() {
		return zeroByteLayer.capacity();
	}
	////////////////////////////////////////////////////////////////////////////////////////////////
	// See for details: http://xorshift.di.unimi.it/murmurhash3.c //////////////////////////////////
	////////////////////////////////////////////////////////////////////////////////////////////////
	public static long nextWord(long word) {
		word ^= (word << A);
		word ^= (word >>> B);
		word ^= (word << C);
		return word;
	}
	//
	////////////////////////////////////////////////////////////////////////////////////////////////
	// Binary serialization implementation /////////////////////////////////////////////////////////
	////////////////////////////////////////////////////////////////////////////////////////////////
	@Override
	public void writeExternal(final ObjectOutput out)
	throws IOException {
		final byte buff[] = new byte[zeroByteLayer.capacity()];
		zeroByteLayer.clear(); // reset
		zeroByteLayer.get(buff);
		out.writeInt(buff.length);
		out.write(buff);
	}
	//
	@Override
	public void readExternal(final ObjectInput in)
	throws IOException, ClassNotFoundException {
		int size = in.readInt(), k;
		final byte buff[] = new byte[size];
		for(int i = 0; i < size; ) {
			k = in.read(buff, i, size - i);
			if(k < 0) {
				throw new EOFException();
			} else {
				i += k;
			}
		}
		zeroByteLayer = ByteBuffer.allocateDirect(size).put(buff);
		byteLayers.clear();
		byteLayers.add(zeroByteLayer);
	}
	////////////////////////////////////////////////////////////////////////////////////////////////
	public static ContentSourceBase DEFAULT = null;
	private final static Lock LOCK = new ReentrantLock();
	public static ContentSourceBase getDefault() {
		LOCK.lock();
		try {
			if(DEFAULT == null) {
				try {
					DEFAULT = new FileContentSource();
				} catch(final Exception e) {
					LogUtil.exception(
						LOG, Level.DEBUG, e,
						"No ring buffer source file available for reading, " +
						"falling back to use the random data ring buffer"
					);
					DEFAULT = new UniformContentSource();
				}
			}
		} finally {
			LOCK.unlock();
		}
		return DEFAULT;
	}
}
