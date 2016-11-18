package com.emc.mongoose.storage.driver.net.http.s3;

import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.util.AsciiString;

/**
 Created by kurila on 02.08.16.
 */
public interface S3Constants {
	String PREFIX_KEY_X_AMZ = "x-amz-";
	String AUTH_PREFIX = "AWS ";
	String KEY_X_AMZ_COPY_SOURCE = "x-amz-copy-source";
	AsciiString HEADERS_CANONICAL[] = {
		HttpHeaderNames.CONTENT_MD5, HttpHeaderNames.CONTENT_TYPE, HttpHeaderNames.RANGE,
		HttpHeaderNames.DATE
	};
	String URL_ARG_VERSIONING = "versioning";
	String SIGN_METHOD = "HmacSHA1";
}