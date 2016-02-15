package com.emc.mongoose.common.generator;

import java.util.Random;
import java.util.concurrent.Callable;

public abstract class AsyncNumberGeneratorBase<T extends Number> implements ValueGenerator<T> {

	protected final Random random = new Random();
	private T minValue;
	private T range;
	private AsyncValueGenerator<T> generator;

	public AsyncNumberGeneratorBase(T minValue, T maxValue) {
		this.minValue = minValue;
		this.range = computeRange(minValue, maxValue);
		this.generator = new AsyncValueGenerator<>(minValue, new Callable<T>() {
			@Override
			public T call() throws Exception {
				return rangeValue();
			}
		});
	}

	public AsyncNumberGeneratorBase(T initialValue) {
		this.generator = new AsyncValueGenerator<>(initialValue, new Callable<T>() {
			@Override
			public T call() throws Exception {
				return singleValue();
			}
		});
	}

	abstract T computeRange(T minValue, T maxValue);
	abstract T rangeValue();
	abstract T singleValue();

	public T minValue() {
		return minValue;
	}

	public T range() {
		return range;
	}

	@Override
	public T get() {
		return generator.get();
	}

}
