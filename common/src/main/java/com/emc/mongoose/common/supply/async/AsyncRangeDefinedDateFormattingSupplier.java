package com.emc.mongoose.common.supply.async;

import com.emc.mongoose.common.exception.OmgDoesNotPerformException;
import com.emc.mongoose.common.supply.RangeDefinedSupplier;

import org.apache.commons.lang.time.FastDateFormat;

import java.text.Format;
import java.util.Date;

public final class AsyncRangeDefinedDateFormattingSupplier
extends AsyncRangeDefinedSupplierBase<Date> {

	private final Format format;
	private final RangeDefinedSupplier<Long> longGenerator;
	
	public AsyncRangeDefinedDateFormattingSupplier(
		final long seed, final Date minValue, final Date maxValue, final String formatString
	) throws OmgDoesNotPerformException{
		super(seed, minValue, maxValue);
		this.format = formatString == null || formatString.isEmpty() ?
			null : FastDateFormat.getInstance(formatString);
		longGenerator = new AsyncRangeDefinedLongFormattingSupplier(
			seed, minValue.getTime(), maxValue.getTime(), null
		);
	}

	@Override
	protected final Date computeRange(final Date minValue, final Date maxValue) {
		return null;
	}

	@Override
	protected final Date rangeValue() {
		return new Date(longGenerator.value());
	}

	@Override
	protected final Date singleValue() {
		return new Date(longGenerator.value());
	}

	@Override
	protected final String toString(final Date value) {
		return format == null ? value.toString() : format.format(value);
	}

	@Override
	public final boolean isInitialized() {
		return longGenerator != null;
	}
}
