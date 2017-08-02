package com.emc.mongoose.api.metrics;

/**
 Created by kurila on 18.07.16.
 */

import com.codahale.metrics.Reservoir;
import com.codahale.metrics.Snapshot;
import com.codahale.metrics.UniformSnapshot;
import com.emc.mongoose.api.common.math.Random;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * A random sampling reservoir of a stream of {@code long}s. Uses Vitter's Algorithm R to produce a
 * statistically representative sample.
 *
 * @see <a href="http://www.cs.umd.edu/~samir/498/vitter.pdf">Random Sampling with a Reservoir</a>
 */
public class UnsafeButFasterUniformReservoir implements Reservoir {
	private static final int DEFAULT_SIZE = 1028;
	private static final int BITS_PER_LONG = 63;
	private final AtomicLong count = new AtomicLong(0);
	private final AtomicLongArray values;

	private static final Random RND = new Random();

	/**
	 * Creates a new {@link com.codahale.metrics.UniformReservoir} of 1028 elements, which offers a 99.9% confidence level
	 * with a 5% margin of error assuming a normal distribution.
	 */
	public UnsafeButFasterUniformReservoir() {
		this(DEFAULT_SIZE);
	}

	/**
	 * Creates a new {@link com.codahale.metrics.UniformReservoir}.
	 *
	 * @param size the number of samples to keep in the sampling reservoir
	 */
	public UnsafeButFasterUniformReservoir(int size) {
		this.values = new AtomicLongArray(size);
		for(int i = 0; i < values.length(); i++) {
			values.set(i, 0);
		}
		count.set(0);
	}

	@Override
	public int size() {
		final long c = count.get();
		if(c > values.length()) {
			return values.length();
		}
		return (int) c;
	}

	@Override
	public void update(long value) {
		final long c = count.incrementAndGet();
		if(c <= values.length()) {
			values.set((int) c - 1, value);
		} else {
			final long r = nextLong(c);
			if(r < values.length()) {
				values.set((int) r, value);
			}
		}
	}

	/**
	 * Get a pseudo-random long uniformly between 0 and n-1. Stolen from
	 * {@link java.util.Random#nextInt()}.
	 *
	 * @param n the bound
	 * @return a value select randomly from the range {@code [0..n)}.
	 */
	private static long nextLong(long n) {
		long bits, val;
		do {
			bits = RND.nextLong() & (~(1L << BITS_PER_LONG));
			val = bits % n;
		} while (bits - val + (n - 1) < 0L);
		return val;
	}

	@Override
	public Snapshot getSnapshot() {
		final int s = size();
		final List<Long> copy = new ArrayList<Long>(s);
		for(int i = 0; i < s; i++) {
			copy.add(values.get(i));
		}
		return new UniformSnapshot(copy);
	}
}