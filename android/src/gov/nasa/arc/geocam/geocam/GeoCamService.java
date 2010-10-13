// __BEGIN_LICENSE__
// Copyright (C) 2008-2010 United States Government as represented by
// the Administrator of the National Aeronautics and Space Administration.
// All Rights Reserved.
// __END_LICENSE__

package gov.nasa.arc.geocam.geocam;

import gov.nasa.arc.geocam.geocam.GeoCamDbAdapter.UploadQueueRow;
import gov.nasa.arc.geocam.geocam.GeoCamDbAdapter.ImageRow;
import gov.nasa.arc.geocam.geocam.GeoCamDbAdapter.TrackRow;
import gov.nasa.arc.geocam.geocam.util.Reflect;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.CursorIndexOutOfBoundsException;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.hardware.GeomagneticField;
import android.location.GpsSatellite;
import android.location.GpsStatus;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.ConditionVariable;
import android.os.CountDownTimer;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.PowerManager.WakeLock;
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
    private int mNumFailures;
    private SharedPreferences.OnSharedPreferenceChangeListener mPrefListener;

    // Current GPS update rate
    private AtomicLong mGpsUpdateRate = new AtomicLong(0);
    private CountDownTimer mPhotoTimer = null;
    
    // Application state
    private boolean mInForeground = false;
    private CountDownTimer mBackgroundTimer = null;
    
    // Track state
    private long mTrackId = -1;
    private long mTrackSegment = 0;
    private boolean mTrackPaused = false;
    private boolean mRecordingTrack = false;
    private WakeLock mWakeLock = null;
    
    // IPC calls
    private final IGeoCamService.Stub mBinder = new IGeoCamService.Stub() {

        public void addToUploadQueue(String uri) throws RemoteException {
        	// Add image stack with downsampled versions to queue and wake the upload thread
        	for (int factor : GeoCamMobile.PHOTO_DOWNSAMPLE_FACTORS) {
                mUploadQueue.addToQueue(uri, factor);
        	}
            mCv.open();
        }
        
        public void addTrackToUploadQueue(long trackId) throws RemoteException {
        	mUploadQueue.addTrackToQueue(trackId);
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
			if (mPhotoTimer != null) {
				Log.d(GeoCamMobile.DEBUG_ID, "Cancelling previous photo increase request");
				mPhotoTimer.cancel();
				mPhotoTimer = null;
			}
			
			mPhotoTimer = new CountDownTimer(GeoCamMobile.POS_UPDATE_FAST_EXPIRATION_MSECS, GeoCamMobile.POS_UPDATE_FAST_EXPIRATION_MSECS) {
				public void onFinish() {
					Log.d(GeoCamMobile.DEBUG_ID, "PhotoTimer finished. Resetting GPS Rate");
					mIsLocationUpdateFast = false;
					registerListener();
				}
				public void onTick(long millisUntilFinished) { }
			};
			
			Log.d(GeoCamMobile.DEBUG_ID, "Increasing GPS rate, PhotoTimer starting");
			mIsLocationUpdateFast = true;
			registerListener();
			mPhotoTimer.start();
		}
		
		// Track state changes
		public void startRecordingTrack() throws RemoteException {
			try {
				mRecordingTrack = true;
				registerListener();
				mTrackId = mGpsLog.startTrack();
				
				PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
				mWakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, GeoCamMobile.DEBUG_ID);
				mWakeLock.acquire();
			} catch(Exception e) {
				mRecordingTrack = false;
				registerListener();
			}
		}

		public void stopRecordingTrack() throws RemoteException {
			mRecordingTrack = false;
			mGpsLog.stopTrack(mTrackId);
			mTrackId = 0;
			mTrackSegment = 0;
			
			registerListener();
			mWakeLock.release();
		}

		public void pauseTrack() throws RemoteException {
			mTrackPaused = true;
		}

		public void resumeTrack() throws RemoteException {
			mTrackSegment += 1;
			mTrackPaused = false;
		}
		
		public boolean isRecordingTrack() throws RemoteException {
			return mRecordingTrack;
		}

		public boolean isTrackPaused() throws RemoteException {
			return mTrackPaused;
		}

		public long currentTrackId() throws RemoteException {
			return mTrackId;
		}
		
		// Application hide/show methods
		public void applicationVisible() throws RemoteException {
			if (mBackgroundTimer != null) {
				Log.d(GeoCamMobile.DEBUG_ID, "Cancelling background timeout.");
				mBackgroundTimer.cancel();
				mBackgroundTimer = null;
			}
			
			mInForeground = true;
			registerListener();
		}
		
		public void applicationInvisible() throws RemoteException {
			// Wait 1.5 seconds to make sure we're just not switching activities
			mBackgroundTimer = new CountDownTimer(1500, 1500) {
				public void onFinish() {
					Log.d(GeoCamMobile.DEBUG_ID, "Timeout reached. Backgrounded. Resetting GPS Rate");
					mInForeground = false;
					registerListener();
					mBackgroundTimer = null;
				}
				public void onTick(long millisUntilFinished) { }
			};
			mBackgroundTimer.start();
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

                if (getIsUploadEnabled()) {
                    // If queue is empty, sleep and try again
                    if (row == null) {
                        Log.d(GeoCamMobile.DEBUG_ID, "GeoCamService - empty queue, sleeping...");
                        buildAndShowNotification("Logging GPS", "0 images in upload queue");
                        mCv.close();
                        mCv.block();
                        continue;
                    }
                    else {
                        Log.d(GeoCamMobile.DEBUG_ID, "Next row id: " + row.toString());
                        buildAndShowNotification("Uploading", getNumImagesMsg());
                    }

                    // Attempt upload
                    boolean success = false;
                    
                    mIsUploading.set(true);
                    if (row.type.equals(GeoCamDbAdapter.TYPE_IMAGE)) {
                	ImageRow imgRow = (ImageRow) row;
                	postProcessLocation(Uri.parse(imgRow.uri));
                	success = uploadImage(Uri.parse(imgRow.uri), imgRow.downsample);
                    } else if (row.type.equals(GeoCamDbAdapter.TYPE_TRACK)) {
                	TrackRow trackRow = (TrackRow) row;
                	Log.d(GeoCamMobile.DEBUG_ID, "Uploading track: " + trackRow.trackId);
                	success = uploadTrack(trackRow.trackId);
                    }
                    
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
                        
                        buildAndShowNotification("Waiting to retry upload", getNumImagesMsg());
                        
                        // Sleep and try again
                        try {
                            Thread.sleep(10000);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        continue;
                    }

                } else {
                    // uploading disabled, go to sleep
                    Log.d(GeoCamMobile.DEBUG_ID, "GeoCamService - uploading disabled, sleeping...");
                    buildAndShowNotification("Logging GPS", "Uploader stopped");
                    mCv.close();
                    mCv.block();
                    continue;
                }
            }
        }
    };
 
    public boolean getIsUploadEnabled() {
        SharedPreferences settings = PreferenceManager.getDefaultSharedPreferences(this);
        return settings.getBoolean(GeoCamMobile.SETTINGS_SERVER_UPLOAD_ENABLED, true);
    }
    
    public void setIsUploadEnabled(boolean isUploadEnabled) {
        SharedPreferences settings = PreferenceManager.getDefaultSharedPreferences(this);
        settings.edit().putBoolean(GeoCamMobile.SETTINGS_SERVER_UPLOAD_ENABLED, isUploadEnabled).commit();
    }

    public void setLastStatus(int val) {
        mLastStatus.set(val);

        if (val == 200) {
            // success, reset failure counter
            mNumFailures = 0;
        } else if (val != -2) {
            // -2 usually means we couldn't connect to server.  we won't
            // count that status as a failure because (a) it's usually
            // due to being out of coverage, so retrying until we regain
            // coverage makes sense, and (b) it usually doesn't cause
            // load on the server, so we can tolerate retries.

            // todo: we should probably back off the retry interval
            // after several connection failures, just in case we are
            // loading the server.
            mNumFailures++;
        }

        // disable uploading if either (a) we failed several times in a
        // row or (b) we got a 'permanent failure' type status code such
        // that retrying doesn't make sense.
        if (mNumFailures >= 3 || (val >= 300 && val != 400)) {
            setIsUploadEnabled(false);
        }
    }

    // Takes URI of image and calls GpsDbAdapter for position bracket based on image timestamp
    // Interpolates position and updates db
    void postProcessLocation(Uri uri) {
        final String[] projection = new String[] {
                MediaStore.Images.ImageColumns._ID,
                MediaStore.Images.ImageColumns.DATE_TAKEN,
                MediaStore.Images.ImageColumns.LATITUDE,
                MediaStore.Images.ImageColumns.LONGITUDE,
                MediaStore.Images.ImageColumns.DESCRIPTION,
                MediaStore.Images.ImageColumns.SIZE,
        };
        
        try {
            Cursor cur = getContentResolver().query(uri, projection, null, null, null);
            cur.moveToFirst();
    
            long dateTakenMillis = cur.getLong(cur.getColumnIndex(MediaStore.Images.ImageColumns.DATE_TAKEN));
            String imageData = cur.getString(cur.getColumnIndex(MediaStore.Images.ImageColumns.DESCRIPTION));
            cur.close();

            List<Location> points = mGpsLog.getBoundingLocations(dateTakenMillis, 1);
            if (points.size() == 2) {
            	Location before = points.get(0);
            	Location after = points.get(1);
            	double lat, lon, alt;

                long beforeDiff = dateTakenMillis - before.getTime();
                long afterDiff = after.getTime() - dateTakenMillis;
                long diff = beforeDiff + afterDiff;
                
                if (diff < GeoCamMobile.PHOTO_BRACKET_INTERVAL_MSECS) {
                    // best case -- not too much time lag between bracketing positions. interpolate.
                    double a = ((double) beforeDiff) / diff;
                    lat = before.getLatitude() + a * (after.getLatitude() - before.getLatitude());
                    lon = before.getLongitude() + a * (after.getLongitude() - before.getLongitude());
                    alt = before.getAltitude() + a * (after.getAltitude() - before.getLongitude());                    
                } else if (beforeDiff < GeoCamMobile.PHOTO_BRACKET_THRESHOLD_MSECS || afterDiff < GeoCamMobile.PHOTO_BRACKET_THRESHOLD_MSECS) {
                    // one of the two points is close enough in time to the photo capture time. use its position.
                    if (beforeDiff < afterDiff) {
                        lat = before.getLatitude();
                        lon = before.getLongitude();
                        alt = before.getAltitude();
                    } else  {
                        lat = after.getLatitude();
                        lon = after.getLongitude();
                        alt = after.getAltitude();
                    }
            	} else {
                    // otherwise, we don't have any usable position data
                    lat = 0.0;
                    lon = 0.0;
                    alt = 0.0;
            	}
            	
            	// Fix the geomagnetic declination if we have all of our info
            	if (!(lat == 0.0 && lon == 0.0 && alt == 0.0)) {
            		GeomagneticField field = new GeomagneticField((float) lat, (float) lon, (float) alt, dateTakenMillis);
            		float declination = field.getDeclination();
            		
                    try {
                        JSONObject dataObj = new JSONObject(imageData);
                        
                        double[] angles = GeoCamMobile.rpyUnSerialize(dataObj.getString("rpy"));
                        angles[2] += declination;
                        Log.d(GeoCamMobile.DEBUG_ID, "Fixed heading. Declination: " + declination + " New heading: " + angles[2]);
                        
                        dataObj.put("rpy", GeoCamMobile.rpySerialize(angles[0], angles[1], angles[2]));
                        dataObj.put("yawRef", GeoCamMobile.YAW_TRUE);
                        
                        imageData = dataObj.toString();
                    } catch (JSONException e) {
                    	Log.e(GeoCamMobile.DEBUG_ID, "Error decoding/encoding for geomagnetic stuff" + e);
                    }
            	}
            	
            	ContentValues values = new ContentValues(2);
            	values.put(MediaStore.Images.ImageColumns.LATITUDE, lat);
            	values.put(MediaStore.Images.ImageColumns.LONGITUDE, lon);
            	values.put(MediaStore.Images.ImageColumns.DESCRIPTION, imageData);
            	getContentResolver().update(uri, values, null, null);
            }            
        }
        catch (CursorIndexOutOfBoundsException e) {

        }
    }
    
    // Location service
    private LocationManager mLocationManager;
    private Location mLocation;
    private Location mPrevLocation;
    private boolean mIsLocationRegistered = false;
    private boolean mIsLocationUpdateFast = false;
    private LocationListener mLocationListener = new LocationListener() {

        public void onLocationChanged(Location location) {
        	mLocation = location;
            if (mLocation != null) {
                //Log.d(GeoCamMobile.DEBUG_ID, "GeoCamService::onLocationChanged");

                boolean tracked = logLocation(location);
                
                // Broadcast change in location
                Intent i = new Intent(GeoCamMobile.LOCATION_CHANGED);
                i.putExtra(GeoCamMobile.LOCATION_EXTRA, mLocation);
                i.putExtra(GeoCamMobile.LOCATION_TRACKED, tracked);
                GeoCamService.this.sendBroadcast(i);

                // Potentially update live track
                updateLiveLocation();
            }
        }

        // Do nothing... we only support GPS, and we are proud of it...
        public void onProviderDisabled(String provider) {
        	Log.d(GeoCamMobile.DEBUG_ID, "onProviderDisabled: " + provider);
        }

        public void onProviderEnabled(String provider) {
        	Log.d(GeoCamMobile.DEBUG_ID, "onProviderEnabled: " + provider);
        }

        public void onStatusChanged(String provider, int status, Bundle extras) {
        	Log.d(GeoCamMobile.DEBUG_ID, "onStatusChanged: " + provider + " status: " + status);
        }
    };
    private GpsStatus.Listener mGpsStatusListener = new GpsStatus.Listener() {
    	private int mNumSatellites = -1;
    	
		public void onGpsStatusChanged(int event) {			
			if (event == GpsStatus.GPS_EVENT_STARTED) {
				Log.d(GeoCamMobile.DEBUG_ID, "GPS engine started");
			} else if (event == GpsStatus.GPS_EVENT_STOPPED) {
				Log.d(GeoCamMobile.DEBUG_ID, "GPS engine stopped");
			} else if (event == GpsStatus.GPS_EVENT_FIRST_FIX) {
				GpsStatus status = mLocationManager.getGpsStatus(null);
				Log.d(GeoCamMobile.DEBUG_ID, "GPS engine first fix: " + status.getTimeToFirstFix() + "ms");
			} else {
				int satellites = 0;
				GpsStatus status = mLocationManager.getGpsStatus(null);
				Iterable<GpsSatellite> list = status.getSatellites();
				Iterator<GpsSatellite> i = list.iterator();
				while (i.hasNext()) {
					satellites ++;
					i.next();
				}
				
				if (satellites != mNumSatellites) {
					mNumSatellites = satellites;
					Log.d(GeoCamMobile.DEBUG_ID, "GPS satellites: " + satellites);
				}
			}
		}
    };
    
    private Object mMutex = new Object();
    
    private void registerListener() {
    	long minTime = GeoCamMobile.POS_UPDATE_MSECS_SLOW;
    	
    	if (mInForeground)
    		minTime = GeoCamMobile.POS_UPDATE_MSECS_ACTIVE;
    	
    	if (mRecordingTrack)
    		minTime = GeoCamMobile.POS_UPDATE_MSECS_RECORDING;
    	
    	if (mIsLocationUpdateFast)
    		minTime = GeoCamMobile.POS_UPDATE_MSECS_FAST;
    	
    	if (minTime == mGpsUpdateRate.get()) {
    		Log.d(GeoCamMobile.DEBUG_ID, "Current rate is same as previous. Not re-registering GPS updates: " + minTime + "ms");
    		return;
    	}
    	
    	synchronized(mMutex) {
    	
    		if (mIsLocationRegistered)
    			unregisterListener();
    	
    		Log.d(GeoCamMobile.DEBUG_ID, "Registering GPS listener for " + minTime + "ms update rate");
    	
    		try {
    			mLocationManager.requestLocationUpdates(
    					LocationManager.GPS_PROVIDER, 
    					minTime, 0, 
    					mLocationListener);
    		} catch (RuntimeException e) {
    			Log.e(GeoCamMobile.DEBUG_ID, "Unable to register LocationListener: " + e);
    			return;
    		}
        
    		mGpsUpdateRate.set(minTime);
    		mIsLocationRegistered = true;
    	}
    }
    
    private void unregisterListener() {
    	if (mIsLocationRegistered == false) return;
    	
    	Log.d(GeoCamMobile.DEBUG_ID, "Unregistering GPS listener");
    	
    	synchronized(mMutex) {
    		mLocationManager.removeUpdates(mLocationListener);
    	}
    }
    
    private boolean logLocation(Location location) {
    	double distance = 10.0;
    	if (mPrevLocation != null) {
    		distance = location.distanceTo(mPrevLocation);
    		//Log.d(GeoCamMobile.DEBUG_ID, "Distance: " + distance);
    	}
    	
    	if(mRecordingTrack && !mTrackPaused && distance >= 3.0)  {
    		mGpsLog.addPointToTrack(mTrackId, mTrackSegment, location);
    		mPrevLocation = location;
    		return true;
    	}
    	
    	mGpsLog.addPoint(location);
    	return false;
    }

    // Live-tracks
    private long mLastLiveUpload = 0;
    private Runnable mLiveUploadRunnable = new Runnable() {
        public void run() {
            uploadLiveLocation(mLocation);
        }
    };
    private volatile Thread mThread = null;
    private void updateLiveLocation() {
        //Log.d(GeoCamMobile.DEBUG_ID, "updateLiveLocation: Determining whether to upload new live location");

    	SharedPreferences settings = PreferenceManager.getDefaultSharedPreferences(this);
        String uploadFreqStr = settings.getString(GeoCamMobile.SETTINGS_TRACKING_FREQ_KEY, "0");
        long uploadFreq = Integer.parseInt(uploadFreqStr) * 1000;

        if (uploadFreq == 0) {
            //Log.d(GeoCamMobile.DEBUG_ID, "updateLiveLocation: nope, disabled");
            return;
        }

        if ((mLocation.getTime() - mLastLiveUpload) < uploadFreq) {
            //Log.d(GeoCamMobile.DEBUG_ID, "updateLiveLocation: nope, not late enough");
            return;
        }
        
        Log.d(GeoCamMobile.DEBUG_ID, "updateLiveLocation: uploading new live location");
        mLastLiveUpload = mLocation.getTime();

        Thread moribund = mThread;
        mThread = new Thread(mLiveUploadRunnable);
        mThread.start();

        return;
    }
    
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
    	Log.d(GeoCamMobile.DEBUG_ID, "GeoCamService::onCreate called");
    	super.onCreate();
    	
    	// Prevent this service from being prematurely killed to reclaim memory
        buildNotification("GeoCam uploader starting...", "Starting...");
        Reflect.Service.startForeground(this, NOTIFICATION_ID, mNotification);
        
        // Location Manager
        mLocationManager = (LocationManager)getSystemService(Context.LOCATION_SERVICE);
        registerListener();
         
        mLocationManager.addGpsStatusListener(mGpsStatusListener);
        
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
        
        mPrefListener = new SharedPreferences.OnSharedPreferenceChangeListener() {
                public void onSharedPreferenceChanged(SharedPreferences prefs, String key) {
                    // wake up upload thread if upload was just enabled
                    boolean isUploadEnabled = prefs.getBoolean(GeoCamMobile.SETTINGS_SERVER_UPLOAD_ENABLED, true);
                    Log.d(GeoCamMobile.DEBUG_ID, "GeoCamService.mPrefListener.onSharedPreferenceChanged"
                          + " key=" + key
                          + " isUploadEnabled=" + Boolean.toString(isUploadEnabled));
                    if (key.equals(GeoCamMobile.SETTINGS_SERVER_UPLOAD_ENABLED)
                        && isUploadEnabled) {
                        Log.d(GeoCamMobile.DEBUG_ID, "GeoCamService.mPrefListener.onSharedPreferenceChanged"
                              + " waking up upload thread");
                        mCv.open();
                    }
                }
            };
    	SharedPreferences settings = PreferenceManager.getDefaultSharedPreferences(this);
        settings.registerOnSharedPreferenceChangeListener(mPrefListener);

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
        unregisterListener();
        mLocationManager.removeGpsStatusListener(mGpsStatusListener);

        if (mBackgroundTimer != null)
        	mBackgroundTimer.cancel();
        if (mPhotoTimer != null)
        	mPhotoTimer.cancel();
        
        if (mUploadQueue != null)
        	mUploadQueue.close();
        mUploadQueue = null;
        
        if (mGpsLog != null)
        	mGpsLog.close();
        mGpsLog = null;
        
        mNotificationManager.cancel(NOTIFICATION_ID);
        mNotificationManager = null;
        
    	SharedPreferences settings = PreferenceManager.getDefaultSharedPreferences(this);
        settings.unregisterOnSharedPreferenceChangeListener(mPrefListener);
        mPrefListener = null;

        mUploadThread = null;
        
        Reflect.Service.stopForeground(this, NOTIFICATION_ID);

        Log.d(GeoCamMobile.DEBUG_ID, "GeoCamService::onDestroy called");
    }    
     
    @Override
    public IBinder onBind(Intent intent) {
    	Log.d(GeoCamMobile.DEBUG_ID, "GeoCamService::onBind called");
        return mBinder;
    }

    private void buildNotification(CharSequence title, CharSequence notifyText) {
        Intent notificationIntent = new Intent(getApplication(), GeoCamMobile.class);
        //notificationIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        notificationIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0, notificationIntent, 0);

        if (mNotification == null) {
            mNotification = new Notification(R.drawable.camera_48x48, notifyText, System.currentTimeMillis());
            mNotification.flags |= Notification.FLAG_ONGOING_EVENT;
            mNotification.flags |= Notification.FLAG_NO_CLEAR;
        }
        mNotification.setLatestEventInfo(getApplicationContext(), title, notifyText, contentIntent);
    }

    private void showNotification() {
        if (mNotification == null) return;
        mNotificationManager.notify(NOTIFICATION_ID, mNotification);
    }

    private void buildAndShowNotification(CharSequence title, CharSequence notifyText) {
        buildNotification(title, notifyText);
        showNotification();
    }

    public boolean uploadLiveLocation(Location location) {
    	SharedPreferences settings = PreferenceManager.getDefaultSharedPreferences(this);
        String serverUrl = settings.getString(GeoCamMobile.SETTINGS_SERVER_URL_KEY, "BOGUS");
        String username = settings.getString(GeoCamMobile.SETTINGS_SERVER_USERNAME_KEY, "BOGUS");
        String password = settings.getString(GeoCamMobile.SETTINGS_SERVER_PASSWORD_KEY, "BOGUS");
        String phoneUid = settings.getString(GeoCamMobile.SETTINGS_UNIQUE_ID, "BOGUS");

        JSONObject feature;

        try {
            // Create the coordinates array .. x, y and z
            JSONArray coordinates = new JSONArray();
            coordinates.put(location.getLongitude());
            coordinates.put(location.getLatitude());
            if (location.hasAltitude()) {
                coordinates.put(location.getAltitude());
            }

            // Create the point geometry
            JSONObject geometry = new JSONObject();
            geometry.put("type", "Point");
            geometry.put("coordinates", coordinates);
       
            // Create the properties object
            JSONObject properties = new JSONObject();
            properties.put("name", username);
            properties.put("userName", username);

            if (location.hasAccuracy()) {
                properties.put("accuracyMeters", location.getAccuracy());
            }

            if (location.hasSpeed()) {
                properties.put("speedMetersPerSecond", location.getSpeed());
            }

            properties.put("timestamp", GpxWriter.dateToISO8601(location.getTime()));

            // Create a GeoJson feature for this
            feature = new JSONObject();
            feature.put("type", "Feature");
            feature.put("id", phoneUid);
            feature.put("geometry", geometry);
            feature.put("properties", properties);
        } catch (JSONException e) {
            Log.e(GeoCamMobile.DEBUG_ID, "uploadLiveLocation: error creating json " + e);
            return false;
        }
        
        Log.i(GeoCamMobile.DEBUG_ID, "uploadLiveLocation: " + feature.toString());

        String postUrl = serverUrl + "tracking/post/";
        
        int out = 0;
        try {
            out = HttpPost.post(postUrl, feature, username, password);
        } catch (IOException e) {
            Log.w(GeoCamMobile.DEBUG_ID, "uploadLiveLocation: " + e);
            return false;
        }

        return (out == 200);
    }
    
    public boolean uploadTrack(long trackId) {
    	SharedPreferences settings = PreferenceManager.getDefaultSharedPreferences(this);
        String serverUrl = settings.getString(GeoCamMobile.SETTINGS_SERVER_URL_KEY, "BOGUS");
        String serverUsername = settings.getString(GeoCamMobile.SETTINGS_SERVER_USERNAME_KEY, "BOGUS");
        String username = settings.getString(GeoCamMobile.SETTINGS_SERVER_USERNAME_KEY, "BOGUS");
        String password = settings.getString(GeoCamMobile.SETTINGS_SERVER_PASSWORD_KEY, "BOGUS");
    		
        String postUrl;
        if (password.equals("")) {
            // old style -- username in url
            postUrl = serverUrl + "/track/upload/" + serverUsername + "/";
        } else {
            // new style -- username will be in credentials
            postUrl = serverUrl + "/track/upload-m/";
        }
        
        Log.d(GeoCamMobile.DEBUG_ID, "Uploading track " + trackId);
        
    	boolean success = false;
    	
    	String trackUid = mGpsLog.getTrackUuid(trackId);
    	GpxWriter writer = mGpsLog.trackToGpx(trackId);

    	HashMap<String,String> vars = new HashMap<String,String>();
    	vars.put("trackUploadProtocolVersion", "1.0");
    	vars.put("icon", "camera");
    	vars.put("uuid", trackUid);
    	//vars.put("lineColor", "");
    	//vars.put("icon", "");
    	//vars.put("icon", "");
    	
    	try {
    		InputStream inputStream = new ByteArrayInputStream(writer.toString().getBytes("utf-8"));
    	
    		Log.d(GeoCamMobile.DEBUG_ID, "Posting to " + postUrl);
    		int out = HttpPost.post(postUrl, vars, "gpxFile", trackUid, inputStream, username, password);
    		
    		Log.d(GeoCamMobile.DEBUG_ID, "Post response: " + out);
    		
    		success = (out == 200);
    	} catch (UnsupportedEncodingException e) {
    		Log.e(GeoCamMobile.DEBUG_ID, "No utf-8 encoding. WTF");
    		return false;
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
    	
    	return success;
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
            String yawRef;
            try {
                JSONObject imageData = new JSONObject(description);
                angles = GeoCamMobile.rpyUnSerialize(imageData.getString("rpy"));
                note = imageData.getString("note");
                tag = imageData.getString("tag");
                uuid = imageData.getString("uuid");
                yawRef = imageData.getString("yawRef");
            }
            catch (JSONException e) {
                angles[0] = angles[1] = angles[2] = 0.0;
                note = "";
                tag = "";
                uuid = "";
                yawRef = GeoCamMobile.YAW_MAGNETIC;
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
            vars.put("yawRef", yawRef);
            
            Log.d(GeoCamMobile.DEBUG_ID, "Uploading with yawRef: " + yawRef);

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
        String serverUrl = settings.getString(GeoCamMobile.SETTINGS_SERVER_URL_KEY, "BOGUS");
        String serverUsername = settings.getString(GeoCamMobile.SETTINGS_SERVER_USERNAME_KEY, "BOGUS");
        String username = settings.getString(GeoCamMobile.SETTINGS_SERVER_USERNAME_KEY, "BOGUS");
        String password = settings.getString(GeoCamMobile.SETTINGS_SERVER_PASSWORD_KEY, "BOGUS");
    		
        String postUrl;
        if (password.equals("")) {
            // old style -- username in url
            postUrl = serverUrl + "/upload/" + serverUsername + "/";
        } else {
            // new style -- username will be in credentials
            postUrl = serverUrl + "/upload-m/";
        }

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
            
            Log.d(GeoCamMobile.DEBUG_ID, "Posting to URL " + postUrl);
            int out = HttpPost.post(postUrl, vars, "photo", String.valueOf(id) + ".jpg", readJpeg,
                                    username, password);
            
            Log.d(GeoCamMobile.DEBUG_ID, "POST response: " + (new Integer(out).toString()));
            
            setLastStatus(out);
            return (out == 200);
        } 
        catch (FileNotFoundException e) {
            Log.e(GeoCamMobile.DEBUG_ID, "FileNotFoundException: " + e);
            return false;
        } 
        catch (IOException e) {
            Log.e(GeoCamMobile.DEBUG_ID, "IOException: " + e);
            setLastStatus(-2); // our code for i/o error in upload
            return false;
        }
        catch (NullPointerException e) {
            Log.e(GeoCamMobile.DEBUG_ID, "NullPointerException: " + e);
            return false;
        }
    }
}
