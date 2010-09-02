// __BEGIN_LICENSE__
// Copyright (C) 2008-2010 United States Government as represented by
// the Administrator of the National Aeronautics and Space Administration.
// All Rights Reserved.
// __END_LICENSE__

package gov.nasa.arc.geocam.geocam;

import gov.nasa.arc.geocam.geocam.util.ForegroundTracker;
import gov.nasa.arc.geocam.geocam.util.Reflect;

import java.io.OutputStream;
import java.io.IOException;
import java.text.NumberFormat;
import java.util.Formatter;
import java.util.List;
import java.util.UUID;

import org.json.JSONException;
import org.json.JSONObject;

import android.app.Activity;
import android.app.Dialog;
import android.app.ProgressDialog;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.res.Configuration;
import android.database.Cursor;
import android.hardware.Camera;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.provider.MediaStore;
import android.util.Log;
import android.view.KeyEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
import android.graphics.PixelFormat;

public class CameraActivity extends Activity implements SurfaceHolder.Callback {

    private static final int DIALOG_SAVE_PROGRESS = 991;
    private static final int DIALOG_HIDE_KEYBOARD = 992;
    
    // UI elements
    private ProgressDialog mSaveProgressDialog;
    private Dialog mHideKeyboardDialog;
    private ImageView mFocusRect;
    
    // Camera preview surface
    private SurfaceView mCameraPreview;
    SurfaceHolder mHolder;

    // Camera 
    Camera mCamera;
    private boolean mLensIsFocused = false;
    private boolean mPictureTaken = false;
    private byte mImageBytes[];
    private JSONObject mImageData;
    private Uri mImageUri;

    // Orientation
    private SensorManager mSensorManager;
    private float[] mOrientation = { 0.0f, 0.0f, 0.0f };
    private float[] mAccelerometerData = { 0.0f, 0.0f, 0.0f };
    private float[] mMagneticFieldData = { 0.0f, 0.0f, 0.0f };
    private boolean mAccelerometerGood = false, mMagneticFieldGood = false;
    private final SensorEventListener mSensorListener = new SensorEventListener() {
        private NumberFormat mmRPYFormatter = NumberFormat.getInstance();
        private TextView mmRollText = null;
        private TextView mmPitchText = null;
        private TextView mmYawText = null;

        public void onAccuracyChanged(Sensor sensor, int accuracy) {
        }
    
        public void onSensorChanged(SensorEvent event) {
            switch (event.sensor.getType()) {
            case Sensor.TYPE_ACCELEROMETER:
                mAccelerometerGood = true;
                mAccelerometerData = event.values.clone();
                break;
            case Sensor.TYPE_MAGNETIC_FIELD:
                mMagneticFieldGood = true;
                mMagneticFieldData = event.values.clone();
                break;
            }        	
        	
            float[] rotationMatrix = new float[16];
            float[] inclinationMatrix = new float[16];
            float[] remappedRotationMatrix = new float[16];
        	
            // yaw, pitch, roll
            mOrientation[0] = mOrientation[1] = mOrientation[2] = 0.0f;
        	
            if (mAccelerometerGood && mMagneticFieldGood) {
                boolean rotationGood = SensorManager.getRotationMatrix(rotationMatrix, inclinationMatrix, mAccelerometerData, mMagneticFieldData);
                rotationGood &= SensorManager.remapCoordinateSystem(rotationMatrix, SensorManager.AXIS_Z, SensorManager.AXIS_MINUS_X, remappedRotationMatrix);
                if (rotationGood) {
                    SensorManager.getOrientation(remappedRotationMatrix, mOrientation);

                    mOrientation[2] *= 180.0/Math.PI;
                    mOrientation[1] *= -180.0/Math.PI;
                    mOrientation[0] *= 180.0/Math.PI;
        			
                    // map yaw from -180->180 to 0->360
                    if (mOrientation[0] < 0) {
                        mOrientation[0] += 360.0f;
                    }
                }
            }

            if (mmRollText == null) {
                mmRollText = (TextView)findViewById(R.id.camera_textview_roll);
                mmPitchText = (TextView)findViewById(R.id.camera_textview_pitch);
                mmYawText = (TextView)findViewById(R.id.camera_textview_yaw);
                mmRPYFormatter.setMaximumFractionDigits(1);
            }

            mmRollText.setText("Roll: " + mmRPYFormatter.format(mOrientation[2]) + "\u00b0");
            mmPitchText.setText("Pitch: " + mmRPYFormatter.format(mOrientation[1]) + "\u00b0");
            mmYawText.setText("Yaw: " + mmRPYFormatter.format(mOrientation[0]) + "\u00b0");
        }
    };
    
    // Location
    private LocationReceiver mLocationReceiver;
    private Location mLocation;
    private NumberFormat mLocationFormatter = NumberFormat.getInstance();
    private TextView mLatText;
    private TextView mLonText;
    
    // GeoCam Service
    private IGeoCamService mService;
    private boolean mServiceBound = false;
    private ServiceConnection mServiceConn = new ServiceConnection() {
    	
    	public void onServiceConnected(ComponentName name, IBinder service) {
            mService = IGeoCamService.Stub.asInterface(service);
            mServiceBound = true;
    		
            try {
            	mLocation = mService.getLocation();
            	if (mLocation != null) {
                    Log.d(GeoCamMobile.DEBUG_ID, "CameraActivity::onServiceConnected");
            	}
            	else {
                    Log.d(GeoCamMobile.DEBUG_ID, "CameraActivity::onServiceConnected - no location");
            	}
            }
            catch (RemoteException e) {
                Log.e(GeoCamMobile.DEBUG_ID, "CameraActivity::onServiceConnected - error getting location from service");
            }
    	}
    	
        public void onServiceDisconnected(ComponentName name) {
            mService = null;
            mServiceBound = false;
    	}
    };
    
    // Foreground/background
    private ForegroundTracker mForeground;
    
    // Activity methods
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
                
        // Window and view properties
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        setContentView(R.layout.camera);

        // Location
        mLocationReceiver = new LocationReceiver();
        mLocationFormatter.setMaximumFractionDigits(6);

        mLatText = (TextView)findViewById(R.id.camera_textview_lat);
        mLonText = (TextView)findViewById(R.id.camera_textview_lon);
        
        // Orientation
        mSensorManager = (SensorManager)getSystemService(Context.SENSOR_SERVICE);

        // Camera
        mCameraPreview = (SurfaceView)findViewById(R.id.camera_surfaceview);

        mHolder = mCameraPreview.getHolder();
        mHolder.addCallback(this);
        mHolder.setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);

        // UI elements        
        mFocusRect = (ImageView)findViewById(R.id.camera_focus_imageview);
        mFocusRect.setImageDrawable(getResources().getDrawable(R.drawable.frame_unfocused_64x48));
        
        final ImageButton shutterButton = (ImageButton)findViewById(R.id.camera_shutter_button);
        shutterButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
            	if (!mPictureTaken) {
                    mPictureTaken = true;
                    if (!mLensIsFocused) {
                        mFocusRect.setImageDrawable(getResources().getDrawable(R.drawable.frame_unfocused_64x48));
                        mLensIsFocused = true;
                        CameraActivity.this.focusLens(true);
                    } else {
                        CameraActivity.this.takePicture();
                    }
            	}
            }
        });
        
        mForeground = new ForegroundTracker(this);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
    }
    
    @Override
    public void onPause() {
        super.onPause();
        
        mForeground.background();
        
        if (mServiceBound) 
        	unbindService(mServiceConn);
        this.unregisterReceiver(mLocationReceiver);
        mSensorManager.unregisterListener(mSensorListener);
    }

    @Override
    public void onResume() {
        super.onResume();

        mServiceBound = bindService(new Intent(this, GeoCamService.class), mServiceConn, Context.BIND_AUTO_CREATE);
        if (!mServiceBound) {
        	Log.e(GeoCamMobile.DEBUG_ID, "CameraActivity::onResume - error binding to service");
        }

        mForeground.foreground();
        
        mSensorManager.registerListener(mSensorListener, 
                        mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER),
                        SensorManager.SENSOR_DELAY_GAME);
        mSensorManager.registerListener(mSensorListener,
        		mSensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD),
        		SensorManager.SENSOR_DELAY_GAME);

        mLatText.setText("Lat: unavailable");
        mLonText.setText("Lon: unavailable");

        IntentFilter filter = new IntentFilter(GeoCamMobile.LOCATION_CHANGED);
        this.registerReceiver(mLocationReceiver, filter);
        
        if (getResources().getConfiguration().hardKeyboardHidden == Configuration.HARDKEYBOARDHIDDEN_NO) {
            showDialog(DIALOG_HIDE_KEYBOARD);
        }
        
        // Unset focus and picture status flags when returning from another activity
        mLensIsFocused = false;
        mPictureTaken = false;        
    }

    // Show hide keyboard dialog if keyboard is open in this activity
    // This overriding this method with the appropriate entry in the manifest
    // prevents the activity from restarting when the hardware keyboard is opened
    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);

        Log.d(GeoCamMobile.DEBUG_ID, "Keyboard hidden: " + String.valueOf(newConfig.keyboardHidden));
        if (newConfig.hardKeyboardHidden == Configuration.HARDKEYBOARDHIDDEN_NO) {
            showDialog(DIALOG_HIDE_KEYBOARD);
        }
        else if (newConfig.hardKeyboardHidden == Configuration.HARDKEYBOARDHIDDEN_YES) {
            dismissDialog(DIALOG_HIDE_KEYBOARD);
        }
    }
    
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        switch (keyCode) {
        case KeyEvent.KEYCODE_FOCUS:
            mFocusRect.setImageDrawable(getResources().getDrawable(R.drawable.frame_unfocused_64x48));
            mLensIsFocused = false;
            break;
        
        case KeyEvent.KEYCODE_CAMERA:
        case KeyEvent.KEYCODE_DPAD_CENTER:
            mPictureTaken = false;
            break;
        }
        return super.onKeyUp(keyCode, event);        
    }
    
    public boolean onKeyDown(int keyCode, KeyEvent event) {        
        switch (keyCode) {
        case KeyEvent.KEYCODE_FOCUS:
            if (!mLensIsFocused) {
                mFocusRect.setImageDrawable(getResources().getDrawable(R.drawable.frame_unfocused_64x48));
                mLensIsFocused = true;
                this.focusLens(false);
            }
            break;
        case KeyEvent.KEYCODE_CAMERA:
        case KeyEvent.KEYCODE_DPAD_CENTER:
            if (!mPictureTaken) {
                mPictureTaken = true;
                if (!mLensIsFocused) {
                    mFocusRect.setImageDrawable(getResources().getDrawable(R.drawable.frame_unfocused_64x48));
                    mLensIsFocused = true;
                    this.focusLens(true);
                } else {
                    this.takePicture();
                }
            }
            // Return here after catching camera keycode so we don't launch the built-in camera app
            return true;
        }    
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected Dialog onCreateDialog(int id) {
        switch(id) {
        case DIALOG_SAVE_PROGRESS:
            mSaveProgressDialog = new ProgressDialog(this);
            mSaveProgressDialog.setMessage(getResources().getString(R.string.camera_saving));
            mSaveProgressDialog.setIndeterminate(true);
            mSaveProgressDialog.setCancelable(false);
            return mSaveProgressDialog;
        
        case DIALOG_HIDE_KEYBOARD:
            mHideKeyboardDialog = new Dialog(this);
            mHideKeyboardDialog.setTitle(getResources().getString(R.string.camera_hide_keyboard));
            return mHideKeyboardDialog;
            
        default:
            break;
        }
        return null;
    }
    
    // Camera preview surface methods
    public void surfaceChanged(SurfaceHolder holder, int format, int w, int h) {
        Camera.Parameters parameters = mCamera.getParameters();
        
        int wDiff = w;
        int hDiff = h;
        
        int realWidth = w, realHeight = h;
        
        List<Camera.Size> sizes = Reflect.CameraParameters.getSupportedPreviewSizes(parameters);
        if (sizes != null) {
            for(Camera.Size size : sizes) {
                int tempWDiff = Math.abs(size.width - w);
                int tempHDiff = Math.abs(size.height - h);
        	
                if ((tempWDiff + tempHDiff) < (wDiff + hDiff)) {
                    wDiff = tempWDiff;
                    hDiff = tempHDiff;
                    realWidth = size.width;
                    realHeight = size.height;
                }
            }
        }
        
        parameters.setPreviewSize(realWidth, realHeight);
        parameters.setPictureFormat(PixelFormat.JPEG);
        mCamera.setParameters(parameters);
        mCamera.startPreview();
    }

    public void surfaceCreated(SurfaceHolder holder) {
        mCamera = Camera.open();
        try {
            mCamera.setPreviewDisplay(holder);
        } catch (IOException e) {
            Log.e(GeoCamMobile.DEBUG_ID, "mCamera.setPreviewDisplay threw an IOException: " + e);
        }
    }

    public void surfaceDestroyed(SurfaceHolder arg0) {
        mCamera.stopPreview();
        mCamera.release();
        mCamera = null;
    }
    
    // Camera methods
    public void focusLens(final boolean takePicture) {
        mCamera.autoFocus(new Camera.AutoFocusCallback() {
            public void onAutoFocus(boolean success, Camera camera) {
                if (success) {    
                    mFocusRect.setImageDrawable(getResources().getDrawable(R.drawable.frame_focused_64x48));
                }
                else {
                    mFocusRect.setImageDrawable(getResources().getDrawable(R.drawable.frame_unfocused_64x48));
                }
                if (takePicture)
                    CameraActivity.this.takePicture();
            }
        });
    }
    
    public void takePicture() {
        mCamera.takePicture(new Camera.ShutterCallback() {
            public void onShutter() {
                return;
            }
        },
        null,
        new Camera.PictureCallback() {
            public void onPictureTaken(byte[] data, Camera camera) {
                showDialog(DIALOG_SAVE_PROGRESS);
                
                mImageBytes = data;
                Thread t = new Thread() {
                    public void run() {
                        saveImage();
                    }
                };
                t.start();
            }
        });

        if (mServiceBound) {
            try {
                mService.increaseLocationUpdateRate();
            }
            catch (RemoteException e) {
                Log.e(GeoCamMobile.DEBUG_ID, "Error increasing location update rate after takePicture");
            }
        }
    }

    private void saveImage() {
    	Log.d(GeoCamMobile.DEBUG_ID, "Saving orientation: " + mOrientation[2] + "," + mOrientation[1] + "," + mOrientation[0]);
    	// Store orientation data in description field of mediastore using JSON encoding
        JSONObject imageData = new JSONObject();
        try {
            imageData.put("rpy", GeoCamMobile.rpySerialize(mOrientation[2], mOrientation[1], mOrientation[0]));
            imageData.put("yawRef", GeoCamMobile.YAW_MAGNETIC);
            imageData.put("uuid", UUID.randomUUID());
            Log.d(GeoCamMobile.DEBUG_ID, "Saving image with data: " + imageData.toString());
        }
        catch (JSONException e) {
            Log.d(GeoCamMobile.DEBUG_ID, "Error while adding JSON data to image");            
        }
        mImageData = imageData;

        double lat, lon;
        if (mLocation == null) {
            lat = 0.0;
            lon = 0.0;
        } else {
            lat = mLocation.getLatitude();
            lon = mLocation.getLongitude();
        }

        // Add some parameters to the image that will be stored in the Image ContentProvider
        ContentValues values = new ContentValues();
        String name = String.valueOf(System.currentTimeMillis());
        values.put(MediaStore.Images.Media.DISPLAY_NAME, name + ".jpg");
        values.put(MediaStore.Images.Media.TITLE, name);
        values.put(MediaStore.Images.Media.DESCRIPTION, mImageData.toString());
        values.put(MediaStore.Images.Media.DATE_TAKEN, System.currentTimeMillis());
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
        values.put(MediaStore.Images.Media.SIZE, mImageBytes.length);
        values.put(MediaStore.Images.Media.LATITUDE, lat);
        values.put(MediaStore.Images.Media.LONGITUDE, lon);

        // There appears to be a bug where saveImage() sometimes fails to actually create an entry 
        // in the db so we do one retry 
        int initNumEntries = getMediaStoreNumEntries();
        mImageUri = saveImage(values);
        if (getMediaStoreNumEntries() <= initNumEntries) {
            Log.d(GeoCamMobile.DEBUG_ID, "Retrying save");
            mImageUri = saveImage(values);
        }
        
        mImageBytes = null;
        Log.d(GeoCamMobile.DEBUG_ID, "Trying to force a GC");
        System.gc();

        dismissDialog(DIALOG_SAVE_PROGRESS);
        
        // Start camera preview activity
        Intent i = new Intent(Intent.ACTION_VIEW, mImageUri);
        i.setClass(getApplication(), CameraPreviewActivity.class);
        i.putExtra("data", mImageData.toString());
        startActivity(i);
    }
    
    private Uri saveImage(ContentValues values) {
        Log.d(GeoCamMobile.DEBUG_ID, "About to insert image into mediastore");
        // Inserting the image meta data inside the content provider
        Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
        Log.d(GeoCamMobile.DEBUG_ID, "Inserted metadata into mediastore");
        // Filling the real data returned by the picture callback function into the content provider
        try {
            OutputStream outStream = getContentResolver().openOutputStream(uri);
            outStream.write(mImageBytes);
            outStream.close();
            Log.d(GeoCamMobile.DEBUG_ID, "Saved image data into mediastore");
        }
        catch (Exception e) {
            Log.d(GeoCamMobile.DEBUG_ID, "Exception while writing image: ", e);
        }
        
        return uri;
    }
    
    private int getMediaStoreNumEntries() {
        Cursor cur = managedQuery(GeoCamMobile.MEDIA_URI, null, null, null, null);
        cur.moveToFirst();
        Log.d(GeoCamMobile.DEBUG_ID, "Retrieving list of photos");
        int count = 0;
        while (cur.moveToNext()) {
            count++;
        }
        Log.d(GeoCamMobile.DEBUG_ID, "Found " + count + " photos");
        return count;
    }
    
    class LocationReceiver extends BroadcastReceiver {
    	
    	@Override
    	public void onReceive(Context context, Intent intent) {
            mLocation = intent.getParcelableExtra(GeoCamMobile.LOCATION_EXTRA);
            mLatText.setText("Lat: " + mLocationFormatter.format(mLocation.getLatitude()) + "\u00b0");
            mLonText.setText("Lon: " + mLocationFormatter.format(mLocation.getLongitude()) + "\u00b0");
            Log.d(GeoCamMobile.DEBUG_ID, "CameraActivity::LocationReceiver.onReceive");
        }
    }
}
