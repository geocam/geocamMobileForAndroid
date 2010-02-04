package gov.nasa.arc.geocam.geocam;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
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
    
    private static final int[] DOWNSAMPLE_FACTORS = { 4, 2, 1 };
    private static final String FIELD_SEPARATOR = "__step__";

    // Notification
    private NotificationManager mNotificationManager;
    private Notification mNotification;
    
    // Upload thread and queue
    private ConditionVariable cv;
    private AtomicBoolean mIsUploading;
    private AtomicInteger mLastStatus;
    private Thread mUploadThread;
    private GeoCamDbAdapter mUploadQueue;
    
    // IPC calls
    private final IGeoCamService.Stub mBinder = new IGeoCamService.Stub() {

        public void addToUploadQueue(String uri) throws RemoteException {
            GeoCamService.this.addToUploadQueue(uri, 0);
        }

        public void clearQueue() throws RemoteException {
        	mUploadQueue.clearQueue();
        }
        
        public boolean isUploading() {
            return mIsUploading.get();
        }
        
        public List<String> getUploadQueue() throws RemoteException {
        	return mUploadQueue.getQueueRowIds();
        }

        public int lastUploadStatus() {
            return mLastStatus.get();
        }

		public Location getLocation() throws RemoteException {
			return mLocation;
		}
    };
    
    public void addToUploadQueue(String uri, int downsampleStep) {
        Log.d(GeoCamMobile.DEBUG_ID, "GeoCamService - addToUploadQueue: " + uri);
        mUploadQueue.addToQueue(uri + FIELD_SEPARATOR + downsampleStep);
        cv.open();
    }

    private Runnable uploadTask = new Runnable() {

        public String getNumImagesMsg() {
            int qLen = mUploadQueue.size();
            String str;
            if (qLen == 1) {
                str = " image in upload queue";
            } else  {
                str = " images in upload queue";
            }
            return String.valueOf(qLen) + str;
        }

        public void run() {
            Thread thisThread = Thread.currentThread();
            while (thisThread == mUploadThread) {
            	long rowId = mUploadQueue.getNextFromQueue(); 
            	Log.d(GeoCamMobile.DEBUG_ID, "Next row id: " + Long.toString(rowId));
            	// If queue is empty, sleep and try again
                if (rowId < 0) {
                    Log.d(GeoCamMobile.DEBUG_ID, "GeoCamService - empty queue, sleeping...");
                    showNotification("GeoCam uploader idle", "0 images in upload queue");
                    cv.close();
                    cv.block();
                    continue;
                }
                else {
                    showNotification("GeoCam uploader active", getNumImagesMsg());
                }

                // Attempt upload
                String uriAndDownsample = mUploadQueue.getUri(rowId);
                Log.d(GeoCamMobile.DEBUG_ID, "GeoCamService - attempting upload: " + uriAndDownsample);
                String[] fields = uriAndDownsample.split(FIELD_SEPARATOR);
                String uriString = fields[0];
                Uri uri = Uri.parse(uriString);
                int downsampleStep = Integer.parseInt(fields[1]);
                int downsampleFactor = DOWNSAMPLE_FACTORS[downsampleStep];
                
                mIsUploading.set(true);
                boolean success = uploadImage(uri, downsampleFactor);
                mIsUploading.set(false);

                if (success) {
                    // advance to next downsample step or remove photo from queue

                    mUploadQueue.setAsUploaded(rowId); // pops queue
                    if (downsampleStep+1 < DOWNSAMPLE_FACTORS.length) {
                        // still need to upload at higher resolution, re-insert
                        // photo at the tail of the queue
                        addToUploadQueue(uriString, downsampleStep+1);
                    }

                    Log.d(GeoCamMobile.DEBUG_ID, "GeoCamService - upload success, " + getNumImagesMsg());
                } 

                // Otherwise, sleep and try again
                else {
                    Log.d(GeoCamMobile.DEBUG_ID, "GeoCamService - upload failed, sleeping...");
                    
                    if (mUploadThread == null) 
                        continue;
                    
                    showNotification("GeoCam uploader paused", getNumImagesMsg());
                    
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
    private LocationListener mLocationListener = new LocationListener() {
        
        public void onLocationChanged(Location location) {
        	mLocation = location;
            if (mLocation != null) {
                Log.d(GeoCamMobile.DEBUG_ID, "GeoCamService::onLocationChanged");

                Intent i = new Intent(GeoCamMobile.LOCATION_CHANGED);
                i.putExtra(GeoCamMobile.LOCATION_EXTRA, mLocation);
                GeoCamService.this.sendBroadcast(i);
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
        
        mLocationManager = (LocationManager)getSystemService(Context.LOCATION_SERVICE);
        Criteria criteria = new Criteria();
        criteria.setAccuracy(Criteria.ACCURACY_FINE);
        mLocationProvider = mLocationManager.getBestProvider(criteria, true);
        if (mLocationProvider != null) {
            mLocationManager.requestLocationUpdates(mLocationProvider, GeoCamMobile.POS_UPDATE_MSECS, 1, mLocationListener);
        }
                
        mNotificationManager = (NotificationManager)getSystemService(Context.NOTIFICATION_SERVICE);

        // Initialize with cv open so we immediately try to upload when the thread is spawned
        // This is important on service restart with non-zero length queue
        // The thread will close cv if the queue is empty
        cv = new ConditionVariable(true);
        mIsUploading = new AtomicBoolean(false);
        mLastStatus = new AtomicInteger(0);

        if (mUploadQueue == null) {
        	mUploadQueue = new GeoCamDbAdapter(this);
        	mUploadQueue.open();
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
        	long rowId = mUploadQueue.getNextFromQueue();
        	mUploadQueue.setAsUploaded(rowId);
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
