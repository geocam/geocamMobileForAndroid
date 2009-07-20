package gov.nasa.arc.geocam.geocam;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.json.JSONException;
import org.json.JSONObject;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.CursorIndexOutOfBoundsException;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.ConditionVariable;
import android.os.IBinder;
import android.os.RemoteException;
import android.preference.PreferenceManager;
import android.provider.MediaStore;
import android.util.Log;

public class GeoCamService extends Service {

	private static final int NOTIFICATION_ID = 1;
	
	// Notification
	private NotificationManager mNotificationManager;
	private Notification mNotification;
	
	// Upload thread and queue
	private ConditionVariable cv;
	private AtomicBoolean mIsUploading;
	private AtomicInteger mLastStatus;
	private Thread mUploadThread;
	private JsonQueueFileStore<String> mUploadQueue;	// this is thread-safe

	// IPC calls
	private final IGeoCamService.Stub mBinder = new IGeoCamService.Stub() {

		public void addToUploadQueue(String uri) throws RemoteException {
			Log.d(GeoCamMobile.DEBUG_ID, "GeoCamService - addToUploadQueue: " + uri);
			mUploadQueue.add(uri);
			cv.open();
		}

		public boolean isUploading() {
			return mIsUploading.get();
		}
		
		public List<String> getUploadQueue() throws RemoteException {
			String[] d = new String[mUploadQueue.size()];
			return Arrays.asList(mUploadQueue.toArray(d));
		}

		public int lastUploadStatus() {
			return mLastStatus.get();
		}
	};
	
	private Runnable uploadTask = new Runnable() {

		public void run() {
			Thread thisThread = Thread.currentThread();
			while (thisThread == mUploadThread) {
				int qLen = mUploadQueue.size();
				String uriString = mUploadQueue.peek();	// Fetch but don't remove from queue just yet

				// If queue is empty, sleep and try again
				if (uriString == null) {
					Log.d(GeoCamMobile.DEBUG_ID, "GeoCamService - empty queue, sleeping...");
					showNotification("GeoCam uploader idle", "0 images in upload queue");
					cv.close();
					cv.block();
					continue;
				}
				else {
					String str;
					if (qLen == 1)
						str = " image in upload queue";
					else 
						str = " images in upload queue";
					showNotification("GeoCam uploader active", String.valueOf(qLen) + str);
				}

				// Attempt upload
				Log.d(GeoCamMobile.DEBUG_ID, "GeoCamService - queue not empty, attempting upload: " + uriString);
				Uri uri = Uri.parse(uriString);
				mIsUploading.set(true);
				boolean success = uploadImage(uri);
				mIsUploading.set(false);

				// Remove entry from queue on success
				if (success) {
					mUploadQueue.poll();
					String str;
					if (qLen-1 == 1) 
						str = " image in upload queue";
					else 
						str = " images in upload queue";
					Log.d(GeoCamMobile.DEBUG_ID, "GeoCamService - upload success, " + String.valueOf(qLen-1) + str);
				} 

				// Otherwise, sleep and try again
				else {
					Log.d(GeoCamMobile.DEBUG_ID, "GeoCamService - upload failed, sleeping...");
					
					if (mUploadThread == null) 
						continue;
					
					String str;
					if (qLen == 1)
						str = " image in upload queue";
					else 
						str = " images in upload queue";

					showNotification("GeoCam uploader paused", String.valueOf(qLen) + str);
					
					try {
						Thread.sleep(10000);
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
					}
					continue;
				}
			}
		}
	};
	
	@Override
	public void onCreate() {
		// Prevent this service from being prematurely killed to reclaim memory
		this.setForeground(true);

		mNotificationManager = (NotificationManager)getSystemService(Context.NOTIFICATION_SERVICE);

		// Initialize with cv open so we immediately try to upload when the thread is spawned
		// This is important on service restart with non-zero length queue
		// The thread will close cv if the queue is empty
		cv = new ConditionVariable(true);
		mIsUploading = new AtomicBoolean(false);
		mLastStatus = new AtomicInteger(0);

		if (mUploadQueue == null)
			mUploadQueue = new JsonQueueFileStore<String>(this, GeoCamMobile.UPLOAD_QUEUE_FILENAME);
				
		mUploadThread = new Thread(null, uploadTask, "UploadThread");
		mUploadThread.start();
	}

	@Override
	public void onStart(Intent intent, int startId) {
		super.onStart(intent, startId);
	}
	
	@Override
	public void onDestroy() {
		super.onDestroy();
		mNotificationManager.cancel(NOTIFICATION_ID);
		mUploadThread = null;
	}	
	 
	@Override
	public IBinder onBind(Intent intent) {
		return mBinder;
	}

	private void showNotification(CharSequence title, CharSequence notifyText) {
		Intent notificationIntent = new Intent(this, GeoCamMobile.class);
		//notificationIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
		notificationIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
		PendingIntent contentIntent = PendingIntent.getActivity(this, 0, notificationIntent, 0);

		if (mNotification == null) {
			mNotification = new Notification(R.drawable.arrow_up_16x16, notifyText, System.currentTimeMillis());
			mNotification.flags |= Notification.FLAG_ONGOING_EVENT;
			mNotification.flags |= Notification.FLAG_NO_CLEAR;
		}
		mNotification.setLatestEventInfo(getApplicationContext(), title, notifyText, contentIntent);
		mNotificationManager.notify(NOTIFICATION_ID, mNotification);
	}
	
	public boolean uploadImage(Uri uri) {
		String[] projection = new String[] {
				MediaStore.Images.ImageColumns._ID,
				MediaStore.Images.ImageColumns.DATE_TAKEN,
				MediaStore.Images.ImageColumns.LATITUDE,
				MediaStore.Images.ImageColumns.LONGITUDE,
				MediaStore.Images.ImageColumns.DESCRIPTION,
				MediaStore.Images.ImageColumns.SIZE,
		};

		boolean success = false;
		
		try {
			Cursor cur = getContentResolver().query(uri, projection, null, null, null);
			cur.moveToFirst();
	
			long id = cur.getLong(cur.getColumnIndex(MediaStore.Images.ImageColumns._ID));
			long dateTakenMillis = cur.getLong(cur.getColumnIndex(MediaStore.Images.ImageColumns.DATE_TAKEN));
			double latitude = cur.getDouble(cur.getColumnIndex(MediaStore.Images.ImageColumns.LATITUDE));
			double longitude = cur.getDouble(cur.getColumnIndex(MediaStore.Images.ImageColumns.LONGITUDE));
			String description = cur.getString(cur.getColumnIndex(MediaStore.Images.ImageColumns.DESCRIPTION));
	
			double[] angles = new double[3];
			String note;
			try {
				JSONObject imageData = new JSONObject(description);
				angles = GeoCamMobile.rpyUnSerialize(imageData.getString("rpy"));
				note = imageData.getString("note");
			}
			catch (JSONException e) {
				angles[0] = angles[1] = angles[2] = 0.0;
				note = "";
			}
			
			SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
			String cameraTime = df.format(new Date(dateTakenMillis));
						
			Map<String,String> vars = new HashMap<String,String>();
			vars.put("cameraTime", cameraTime);
			vars.put("latitude", String.valueOf(latitude));
			vars.put("longitude", String.valueOf(longitude));
			vars.put("roll", String.valueOf(angles[0]));
			vars.put("pitch", String.valueOf(angles[1]));
			vars.put("yaw", String.valueOf(angles[2]));
			vars.put("notes", note);

			success = uploadImage(uri, id, vars);
		}
		catch (CursorIndexOutOfBoundsException e) {
			// Bad db entry, remove from queue and report success so we can move on
			mUploadQueue.poll();
			Log.d(GeoCamMobile.DEBUG_ID, "Invalid entry in upload queue, removing: " + e);
			success = true;
		}
		return success;
	}
		
	public boolean uploadImage(Uri uri, long id, Map<String,String> vars) {
        SharedPreferences settings = PreferenceManager.getDefaultSharedPreferences(this);
        String serverUrl = settings.getString(GeoCamMobile.SETTINGS_SERVER_URL_KEY, GeoCamMobile.SETTINGS_SERVER_URL_DEFAULT);
        String serverUsername = settings.getString(GeoCamMobile.SETTINGS_SERVER_USERNAME_KEY, GeoCamMobile.SETTINGS_SERVER_USERNAME_DEFAULT);
        
		Log.i(GeoCamMobile.DEBUG_ID, "Uploading image #" + String.valueOf(id));
		try {
			Bitmap bitmap = MediaStore.Images.Media.getBitmap(getContentResolver(), uri);
			ByteArrayOutputStream bytes = new ByteArrayOutputStream();
			bitmap.compress(Bitmap.CompressFormat.JPEG, 90, bytes);
			ByteArrayInputStream stream = new ByteArrayInputStream(bytes.toByteArray());

			HttpPost post = new HttpPost();
			String postUrl = serverUrl + "/upload/" + serverUsername + "/";
			Log.d(GeoCamMobile.DEBUG_ID, "Posting to URL " + postUrl);
			int out = post.post(postUrl, true, vars, "photo", String.valueOf(id) + ".jpg", stream);
			
			Log.d(GeoCamMobile.DEBUG_ID, "POST response: " + (new Integer(out).toString()));

			bitmap.recycle();
			
			mLastStatus.set(out);
			return (out == 200);
		} 
		catch (FileNotFoundException e) {
			Log.e(GeoCamMobile.DEBUG_ID, "FileNotFoundException: " + e);
			return false;
		} 
		catch (IOException e) {
			Log.e(GeoCamMobile.DEBUG_ID, "IOException: " + e);
			return false;
		}
		catch (NullPointerException e) {
			Log.e(GeoCamMobile.DEBUG_ID, "NullPointerException: " + e);
			return false;
		}
	}
}
