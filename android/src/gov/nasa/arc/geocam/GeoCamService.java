package gov.nasa.arc.geocam;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

public class GeoCamService extends Service {

	private static final int NOTIFICATION_ID = 1;
	
	private NotificationManager mNotificationManager;
	private Notification mNotification;
	
	private volatile Thread mUploadThread;
	
	@Override
	public IBinder onBind(Intent intent) {
		return mBinder;
	}

	private final IGeoCamService.Stub mBinder = new IGeoCamService.Stub() {

		public boolean addToUploadQueue(int id) throws RemoteException {

			return false;
		}

		public int getCurrentId() throws RemoteException {

			return 0;
		}
	};
	
	private Runnable uploadTask = new Runnable() {

		public void run() {
			Thread thisThread = Thread.currentThread();
			try {
				int i = 0;
				while(thisThread == mUploadThread) {
					Log.d(GeoCamMobile.DEBUG_ID, "GeoCamService - upload ping");
					showNotification(String.valueOf(i));
					i++;
					Thread.sleep(3000);
				}
			}
			catch (InterruptedException e) {
				Log.d(GeoCamMobile.DEBUG_ID, "GeoCamService - " + e);
			}
		}
		
	};
	
	@Override
	public void onStart(Intent intent, int startId) {
		super.onStart(intent, startId);
		//Toast.makeText(this, "Starting GeoCam Service", Toast.LENGTH_SHORT).show();
		mUploadThread = new Thread(null, uploadTask, "UploadThread");
		mUploadThread.start();
		
		mNotificationManager = (NotificationManager)getSystemService(Context.NOTIFICATION_SERVICE);
		showNotification("GeoCam Service started");
	}
	
	@Override
	public void onDestroy() {
		super.onDestroy();
		mNotificationManager.cancel(NOTIFICATION_ID);
		mUploadThread = null;
		//Toast.makeText(this, "Stopping GeoCam Service", Toast.LENGTH_SHORT).show();
	}	
	
	private void showNotification(CharSequence notifyText) {
		Intent notificationIntent = new Intent(this, GeoCamMobile.class);
		PendingIntent contentIntent = PendingIntent.getActivity(this, 0, notificationIntent, 0);

		if (mNotification == null) {
			mNotification = new Notification(R.drawable.arrow_up_16x16, notifyText, System.currentTimeMillis());
			mNotification.flags |= Notification.FLAG_ONGOING_EVENT;
			mNotification.flags |= Notification.FLAG_NO_CLEAR;
		}
		mNotification.setLatestEventInfo(getApplicationContext(), "GeoCam Mobile", notifyText, contentIntent);
		mNotificationManager.notify(NOTIFICATION_ID, mNotification);
	}
}
