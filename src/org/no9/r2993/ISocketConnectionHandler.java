package org.no9.r2993;

public interface ISocketConnectionHandler {
	
	void onConnected();
	void onError(String errormsg);
	void onData(String data);
	void onClientClosed();
	void Emit(byte[] buf);
}
