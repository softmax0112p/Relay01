package org.no9.r2993;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialProber;
import com.hoho.android.usbserial.util.SerialInputOutputManager;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;

public class SocketController implements Runnable{
	
	public static final String SERVERIP = "127.0.0.1";
	public static final int SERVERPORT = 4444;
	private final ISocketConnectionHandler mSocketConnectionHandler;
	private final Context mApplicationContext;
	private final UsbManager mUsbManager;
	private final UsbSerialDriver driver;
	private final int VID;
	private final int PID;
	protected static final String ACTION_USB_PERMISSION = "org.no9.r2993.USB";
	private Thread mThread;
	private OutputStream out = null;
	private ServerSocket serverSocket;
	private static final Object[] sSendLock = new Object[]{};//learned this trick from some google example :)
	//basically an empty array is lighter than an  actual new Object()...
	private boolean mStop = false;
	
	
	private BroadcastReceiver mPermissionReceiver = new PermissionReceiver(
			new IPermissionListener() {
				@Override
				public void onPermissionDenied(UsbDevice d) {
					L.error("Permission denied on " + d.getDeviceId());
				}
			});

	private static interface IPermissionListener {
		void onPermissionDenied(UsbDevice d);
	}
	
	public static byte[] ENCODE_INT_ARRAY(int[] data) {
        byte[] encoded_data = new byte[data.length * 2];
        ENCODE_STRING(data, ByteBuffer.wrap(encoded_data), 0);
        return encoded_data;
    }
	
	/**
     * Return less significant byte
     *
     * @param value value
     * @return less significant byte
     */
    public static int LSB(int value) {
        return value & 0x7F;
    }

    /**
     * Return most significant byte
     *
     * @param value value
     * @return most significant byte
     */
    public static int MSB(int value) {
        return (value >> 7) & 0x7F;
    }
	
	public static void ENCODE_STRING(int[] original_data, ByteBuffer buffer, int offset) {
        for (int i=0; i<original_data.length; i++) {
            buffer.put(offset++, (byte)LSB(original_data[i]));
            buffer.put(offset++, (byte)MSB(original_data[i]));
        }
    }
	
	private class PermissionReceiver extends BroadcastReceiver {
		private final IPermissionListener mPermissionListener;

		public PermissionReceiver(IPermissionListener permissionListener) {
			mPermissionListener = permissionListener;
		}

		@Override
		public void onReceive(Context context, Intent intent) {
			mApplicationContext.unregisterReceiver(this);
			if (intent.getAction().equals(ACTION_USB_PERMISSION)) {
				if (!intent.getBooleanExtra(
						UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
					mPermissionListener.onPermissionDenied((UsbDevice) intent
							.getParcelableExtra(UsbManager.EXTRA_DEVICE));
				} else {
					L.info("Permission granted");
					UsbDevice dev = (UsbDevice) intent
							.getParcelableExtra(UsbManager.EXTRA_DEVICE);
					if (dev != null) {
						if (dev.getVendorId() == VID
								&& dev.getProductId() == PID) {
							//startHandler(dev);// has new thread
						}
					} else {
						L.error("device not present!");
					}
				}
			}
		}

	}
	
	private void enumerate(IPermissionListener listener) {
		L.info("enumerating");
		HashMap<String, UsbDevice> devlist = mUsbManager.getDeviceList();
		Iterator<UsbDevice> deviter = devlist.values().iterator();
		while (deviter.hasNext()) {
			UsbDevice d = deviter.next();
			L.info("Found device: "
					+ String.format("%04X:%04X", d.getVendorId(),
							d.getProductId()));

			if (d.getVendorId() == VID && d.getProductId() == PID) {
				L.info("Device under: " + d.getDeviceName());
				if (!mUsbManager.hasPermission(d))
					listener.onPermissionDenied(d);
				else{
					//startHandler(d);
					return;
				}
				break;
			}
		}
		L.info("no more devices found");
	}
	
	private void init() {
		enumerate(new IPermissionListener() {
			@Override
			public void onPermissionDenied(UsbDevice d) {
				UsbManager usbman = (UsbManager) mApplicationContext
						.getSystemService(Context.USB_SERVICE);
				PendingIntent pi = PendingIntent.getBroadcast(
						mApplicationContext, 0, new Intent(
								ACTION_USB_PERMISSION), 0);
				mApplicationContext.registerReceiver(mPermissionReceiver,
						new IntentFilter(ACTION_USB_PERMISSION));
				usbman.requestPermission(d, pi);
			}
		});
	}
	
	
	private SerialInputOutputManager mSerialIoManager;
	private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();
	
    private final SerialInputOutputManager.Listener mListener =
            new SerialInputOutputManager.Listener() {

        @Override
        public void onRunError(Exception e) {
            L.error("Runner stopped.");
            L.error(e);
        } 

        @Override
        public void onNewData(final byte[] data) {
        	try{
        		out.write(data);
    			out.flush();
    		    L.info(">>>Out Data : " + data);
    			} catch (IOException e) {
    			 L.error(e);
    			}
    		
        }
    };
    
	public SocketController(Context ctx, ISocketConnectionHandler handler, int vid, int pid){
		
		VID = vid;
		PID = pid;
		mApplicationContext = ctx;
		mUsbManager = (UsbManager) mApplicationContext
				.getSystemService(Context.USB_SERVICE);
		
		handler.onData("Starting USB Discovery");
		init();
		
		
		mSocketConnectionHandler = handler;
		driver = UsbSerialProber.acquire(mUsbManager);
		handler.onData("Aquired USB Manager");
		mSerialIoManager = new SerialInputOutputManager(driver, mListener);
		mExecutor.submit(mSerialIoManager);
		  try {
				driver.open();
				driver.setBaudRate(57600);
			} catch (IOException e1) {
				L.error(e1);
			}
		  handler.onData("USB Open at 57600 BAUD");  
		mThread = new Thread(this);
		handler.onData("Starting TCP Server");
		mThread.start();
	}
	
	
	
	public void stop() {
		mStop = true;
		
		try {
			serverSocket.close();
		} catch (IOException e1) {
			L.error(e1);
		}
		
		synchronized (sSendLock) {
			sSendLock.notify();
		}
		try {
			if(mThread != null)
				mThread.join();
		} catch (InterruptedException e) {
			L.error(e.toString());
		}
		mStop = false;
		mThread = null;
	}
	
	public void run() {

        try {
        	
            serverSocket = new ServerSocket(SERVERPORT);
            mSocketConnectionHandler.onConnected();
        	
            while (true) {
            	
                Socket client = serverSocket.accept();
                out = client.getOutputStream();
                mSocketConnectionHandler.onData("Client Connected");
                try {
                	InputStream is = client.getInputStream();
                	int nRead;
                	byte[] data = new byte[4096];
                	
                	
                	while ((nRead = is.read(data, 0, data.length)) != -1) {
                		
                		// Don't know how many bytes we are going to recieve
                		ArrayList al = new ArrayList<Byte>();
                		for(int i = 0; i < data.length; i++){
                			if((int)data[i] != 0)
                				al.add(data[i]);
                		}
                		
                		// We have to trim the trailing 0's of the byte array or the Arduino crashes.
                		byte[] req = new byte[al.size()];
                		for (int i = 0; i < req.length; i++) {
                			req[i] = data[i];
                		}
                		
                		
                		driver.write(req, 1000);
                		
                		L.info(">>>In Data : " + req);
            		    
                		Thread.sleep(5);
                	}

                } catch(Exception e) {

                	L.error("Error in buffer reader: " + e.getMessage() + "\n");
                	L.error(e);
                	mSocketConnectionHandler.onError("Error in buffer reader: " + e.getMessage() + "\n");
                    
                } finally {
                       L.info("S: Client Disconnected.");
                }
            }      

        } catch (Exception e) {
        	mSocketConnectionHandler.onError("Error in connection : " + e.getMessage() + "\n");
        	
        	L.error("S: Error");
        	L.error(e);
        } finally {
        	try {
        		if(serverSocket != null)
				serverSocket.close();
			} catch (IOException e) {
				L.error("Failed to close server socktet");
			}
        	}
        }
   }
