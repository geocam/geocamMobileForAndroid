package gov.nasa.arc.geocam.geocam;

import gov.nasa.arc.geocam.geocam.GeoCamDbAdapter.UploadQueueRow;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
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
import android.graphics.BitmapFactory;
import android.location.Criteria;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Bundle;
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
    private ConditionVariable mCv;
    private AtomicBoolean mIsUploading;
    private AtomicInteger mLastStatus;
    private Thread mUploadThread;
    private GeoCamDbAdapter mUploadQueue;
    private GpsDbAdapter mGpsLog;
    
    // IPC calls
    private final IGeoCamService.Stub mBinder = new IGeoCamService.Stub() {

        public void addToUploadQueue(String uri) throws RemoteException {
        	// Add image stack with downsampled versions to queue and wake the upload thread
        	for (int factor : GeoCamMobile.PHOTO_DOWNSAMPLE_FACTORS) {
                mUploadQueue.addToQueue(uri, factor);
        	}
            mCv.open();
        }

        public void clearQueue() throws RemoteException {
        	mUploadQueue.clearQueue();
        }
        
        public boolean isUploading() {
            return mIsUploading.get();
        }
        
        public int getQueueSize() throws RemoteException {
        	return mUploadQueue.size();
        }

        public int lastUploadStatus() {
            return mLastStatus.get();
        }

		public Location getLocation() throws RemoteException {
			// Return null if our location data is too old
			if ((mLocation != null) && (System.currentTimeMillis() - mLocation.getTime() > GeoCamMobile.LOCATION_STALE_MSECS)) {
            	return null;
            }
			return mLocation;
		}
		
		// Call this function to temporarily increase the location update rate
		public void increaseLocationUpdateRate() throws RemoteException {
			mLocationUpdateFastTimer = System.currentTimeMillis();
			if (!mIsLocationUpdateFast) {
            	Log.d(GeoCamMobile.DEBUG_ID, "Setting location update rate to fast");
				mLocationManager.removeUpdates(mLocationListener);
				mLocationManager.requestLocationUpdates(mLocationProvider, GeoCamMobile.POS_UPDATE_MSECS_FAST, 1, mLocationListener);
				mIsLocationUpdateFast = true;
			}
		}
    };

    private Runnable uploadTask = new Runnable() {

        public String getNumImagesMsg() {
            int qLen = mUploadQueue.size();
            return String.valueOf(qLen) + (qLen == 1 ? " image in upload queue" : " images in upload queue");
        }

        public void run() {
            Thread thisThread = Thread.currentThread();
            while (thisThread == mUploadThread) {
            	UploadQueueRow row = mUploadQueue.getNextFromQueue();

            	// If queue is empty, sleep and try again
                if (row == null) {
                    Log.d(GeoCamMobile.DEBUG_ID, "GeoCamService - empty queue, sleeping...");
                    showNotification("GeoCam uploader idle", "0 images in upload queue");
                    mCv.close();
                    mCv.block();
                    continue;
                }
                else {
                	Log.d(GeoCamMobile.DEBUG_ID, "Next row id: " + row.toString());
                    showNotification("GeoCam uploader active", getNumImagesMsg());
                }

                // Attempt upload
                mIsUploading.set(true);
                boolean success = uploadImage(Uri.parse(row.uri), row.downsample);
                mIsUploading.set(false);
                
                if (success) {
                    mUploadQueue.setAsUploaded(row); // pop from queue
                    Log.d(GeoCamMobile.DEBUG_ID, "GeoCamService - upload success, " + getNumImagesMsg());
                }
                else {
                    Log.d(GeoCamMobile.DEBUG_ID, "GeoCamService - upload failed, sleeping...");
                    
                    // Verify thread is still valid
                    if (mUploadThread == null) 
                        continue;
                    
                    showNotification("GeoCam uploader paused", getNumImagesMsg());

                    // Sleep and try again
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

    // Location service
    private LocationManager mLocationManager;
    private Location mLocation;
    private String mLocationProvider;
    private boolean mIsLocationUpdateFast = false;
    private long mLocationUpdateFastTimer = 0;
    private LocationListener mLocationListener = new LocationListener() {

        public void onLocationChanged(Location location) {
        	mLocation = location;
            if (mLocation != null) {
                Log.d(GeoCamMobile.DEBUG_ID, "GeoCamService::onLocationChanged");

                mGpsLog.addPoint(location);
                
                // Broadcast change in location
                Intent i = new Intent(GeoCamMobile.LOCATION_CHANGED);
                i.putExtra(GeoCamMobile.LOCATION_EXTRA, mLocation);
                GeoCamService.this.sendBroadcast(i);
            }
            
            // See if we need to change the location update rate
            String updateRate = mIsLocationUpdateFast ? "fast" : "slow";
            Log.d(GeoCamMobile.DEBUG_ID, "Location update rate is " + updateRate);
            long timeDiff = System.currentTimeMillis() - mLocationUpdateFastTimer;
            if (mIsLocationUpdateFast) {
            	Log.d(GeoCamMobile.DEBUG_ID, timeDiff + " msec elapsed in fast timer");
            }
            if (mIsLocationUpdateFast && (timeDiff > GeoCamMobile.POS_UPDATE_FAST_EXPIRATION_MSECS)) {
            	Log.d(GeoCamMobile.DEBUG_ID, timeDiff + " msec elapsed, setting location update rate to slow");
				mLocationManager.removeUpdates(mLocationListener);
				mLocationManager.requestLocationUpdates(mLocationProvider, GeoCamMobile.POS_UPDATE_MSECS_SLOW, 1, mLocationListener);
            	mIsLocationUpdateFast = false;
            }
        }

        public void onProviderDisabled(String provider) {
        }

        public void onProviderEnabled(String provider) {
            mLocationProvider = provider;
        }

        public void onStatusChanged(String provider, int status, Bundle extras) {
            mLocationProvider = provider;
        }
    };
    
    public GeoCamService() {
    	super();
    	
    	Log.d(GeoCamMobile.DEBUG_ID, "GeoCamService::GeoCamService called [Contructed]");
    }
    
    protected void finalize() throws Throwable {
    	Log.d(GeoCamMobile.DEBUG_ID, "GeoCamService::finalize called [Destructed]");
    	super.finalize();
    }
    
    @Override
    public void onCreate() {
        // Prevent this service from being prematurely killed to reclaim memory
        this.setForeground(true);

        Log.d(GeoCamMobile.DEBUG_ID, "GeoCamService::onCreate called");
        
        // Location Manager
        mLocationManager = (LocationManager)getSystemService(Context.LOCATION_SERVICE);
        Criteria criteria = new Criteria();
        criteria.setAccuracy(Criteria.ACCURACY_FINE);
        mLocationProvider = mLocationManager.getBestProvider(criteria, true);
        if (mLocationProvider != null) {
            mLocationManager.requestLocationUpdates(mLocationProvider, GeoCamMobile.POS_UPDATE_MSECS_SLOW, 1, mLocationListener);
        }
                
        // Notification Manager
        mNotificationManager = (NotificationManager)getSystemService(Context.NOTIFICATION_SERVICE);

        // Upload queue and thread
        // Initialize with mCv open so we immediately try to upload when the thread is spawned
        // This is important on service restart with non-zero length queue
        // The thread will close mCv if the queue is empty
        mCv = new ConditionVariable(true);
        mIsUploading = new AtomicBoolean(false);
        mLastStatus = new AtomicInteger(0);

        if (mUploadQueue == null) {
        	mUploadQueue = new GeoCamDbAdapter(this);
        	mUploadQueue.open();
        }
        
        if (mGpsLog == null) {
        	mGpsLog = new GpsDbAdapter(this);
        	mGpsLog.open();
        }
        
        mUploadThread = new Thread(null, uploadTask, "UploadThread");
        mUploadThread.start();
    }

    @Override
    public void onStart(Intent intent, int startId) {
        super.onStart(intent, startId);
        Log.d(GeoCamMobile.DEBUG_ID, "GeoCamService::onStart called");
    }
    
    @Override
    public void onDestroy() {
        super.onDestroy();
        mLocationManager.removeUpdates(mLocationListener);

        if (mUploadQueue != null)
        	mUploadQueue.close();
        mUploadQueue = null;
        
        if (mGpsLog != null)
        	mGpsLog.close();
        mGpsLog = null;
        
        mNotificationManager.cancel(NOTIFICATION_ID);
        mNotificationManager = null;
        
        mUploadThread = null;
        
        Log.d(GeoCamMobile.DEBUG_ID, "GeoCamService::onDestroy called");
    }    
     
    @Override
    public IBinder onBind(Intent intent) {
    	Log.d(GeoCamMobile.DEBUG_ID, "GeoCamService::onBind called");
        return mBinder;
    }

    private void showNotification(CharSequence title, CharSequence notifyText) {
        Intent notificationIntent = new Intent(getApplication(), GeoCamMobile.class);
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
    
    public boolean uploadImage(Uri uri, int downsampleFactor) {
        final String[] projection = new String[] {
                MediaStore.Images.ImageColumns._ID,
                MediaStore.Images.ImageColumns.DATE_TAKEN,
                MediaStore.Images.ImageColumns.LATITUDE,
                MediaStore.Images.ImageColumns.LONGITUDE,
                MediaStore.Images.ImageColumns.DESCRIPTION,
                MediaStore.Images.ImageColumns.SIZE,
        };
        
        final SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

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
            String tag;
            String uuid;
            try {
                JSONObject imageData = new JSONObject(description);
                angles = GeoCamMobile.rpyUnSerialize(imageData.getString("rpy"));
                note = imageData.getString("note");
                tag = imageData.getString("tag");
                uuid = imageData.getString("uuid");
            }
            catch (JSONException e) {
                angles[0] = angles[1] = angles[2] = 0.0;
                note = "";
                tag = "";
                uuid = "";
            }
            
            
            String cameraTime = df.format(new Date(dateTakenMillis));
                        
            Map<String,String> vars = new HashMap<String,String>();
            vars.put("cameraTime", cameraTime);
            vars.put("latitude", String.valueOf(latitude));
            vars.put("longitude", String.valueOf(longitude));
            vars.put("roll", String.valueOf(angles[0]));
            vars.put("pitch", String.valueOf(angles[1]));
            vars.put("yaw", String.valueOf(angles[2]));
            vars.put("notes", note);
            vars.put("tags", tag);
            vars.put("uuid", uuid);

            success = uploadImage(uri, id, vars, downsampleFactor);
            cur.close();
        }
        catch (CursorIndexOutOfBoundsException e) {
            // Bad db entry, remove from queue and report success so we can move on
        	mUploadQueue.setAsUploaded(mUploadQueue.getNextFromQueue());
            Log.d(GeoCamMobile.DEBUG_ID, "Invalid entry in upload queue, removing: " + e);
            success = true;
        }
        return success;
    }

    public boolean uploadImage(Uri uri, long id, Map<String,String> vars, int downsampleFactor) {
        SharedPreferences settings = PreferenceManager.getDefaultSharedPreferences(this);
        String serverUrl = settings.getString(GeoCamMobile.SETTINGS_SERVER_URL_KEY, GeoCamMobile.SETTINGS_SERVER_URL_DEFAULT);
        String serverUsername = settings.getString(GeoCamMobile.SETTINGS_SERVER_USERNAME_KEY, GeoCamMobile.SETTINGS_SERVER_USERNAME_DEFAULT);

        Log.i(GeoCamMobile.DEBUG_ID, "Uploading image #" + String.valueOf(id));
        try {
            InputStream readJpeg = null;
            if (downsampleFactor == 1) {
                readJpeg = getContentResolver().openInputStream(uri);
            } else {
                InputStream readFullJpeg = getContentResolver().openInputStream(uri); 
                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inSampleSize = downsampleFactor;
                Bitmap bitmap = BitmapFactory.decodeStream(readFullJpeg, null, opts);
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, bytes);
                bitmap.recycle();
                readJpeg = new ByteArrayInputStream(bytes.toByteArray());
            }
            
            boolean useSSL = false;
            if (serverUrl.startsWith("https")) {
            	useSSL = true;
            }

            HttpPost post = new HttpPost();
            String postUrl = serverUrl + "/upload/" + serverUsername + "/";
            Log.d(GeoCamMobile.DEBUG_ID, "Posting to URL " + postUrl);
            int out = post.post(postUrl, useSSL, vars, "photo", String.valueOf(id) + ".jpg", readJpeg);
            
            Log.d(GeoCamMobile.DEBUG_ID, "POST response: " + (new Integer(out).toString()));
            
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
