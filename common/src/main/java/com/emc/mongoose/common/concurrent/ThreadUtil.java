package com.emc.mongoose.common.concurrent;
/**
 Created by kurila on 09.09.15.
 */
public class ThreadUtil {
	public static int getAvailableConcurrencyLevel() {
		return Runtime.getRuntime().availableProcessors();
	}
}
