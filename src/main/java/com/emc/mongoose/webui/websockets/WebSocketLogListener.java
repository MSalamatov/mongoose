package com.emc.mongoose.webui.websockets;

import java.util.EventListener;

/**
 * Created by gusakk on 10/26/14.
 */
public interface WebSocketLogListener extends EventListener {
	//
	void sendMessage(final Object message);
}
