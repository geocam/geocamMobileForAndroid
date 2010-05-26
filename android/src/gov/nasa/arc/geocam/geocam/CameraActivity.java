package gov.nasa.arc.geocam.geocam;

import gov.nasa.arc.geocam.geocam.util.ForegroundTracker;

import java.io.OutputStream;
import java.io.IOException;
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
    private TextView mFocusText;
    private TextView mLocationText;
    private ImageView mFocusLed;
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
    private float[] mSensorData;
    private final SensorEventListener mSensorListener = new SensorEventListener() {

        public void onAccuracyChanged(Sensor sensor, int accuracy) {
        }
    
        public void onSensorChanged(SensorEvent event) {
            mSensorData = event.values;
        }
    };
    
    // Location
    private LocationReceiver mLocationReceiver;
    private Location mLocation;
    
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
            		mLocationText.setText("Position: " + mLocation.getProvider());
            		Log.d(GeoCamMobile.DEBUG_ID, "CameraActivity::onServiceConnected");
            	}
            	else {
            		mLocationText.setText("Position: none");
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
        
        // Orientation
        mSensorManager = (SensorManager)getSystemService(Context.SENSOR_SERVICE);
        mSensorManager.registerListener(mSensorListener, 
                mSensorManager.getDefaultSensor(Sensor.TYPE_ORIENTATION),
                SensorManager.SENSOR_DELAY_FASTEST);    

        // Camera
        mCameraPreview = (SurfaceView)findViewById(R.id.camera_surfaceview);

        mHolder = mCameraPreview.getHolder();
        mHolder.addCallback(this);
        mHolder.setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);

        // UI elements
        mFocusText = (TextView)findViewById(R.id.camera_textview_focus);
        mFocusText.setText(R.string.camera_focus_instructions);
        
        mFocusLed = (ImageView)findViewById(R.id.camera_imageview_focus);
        mFocusLed.setImageDrawable(getResources().getDrawable(R.drawable.arrow_up_16x16));
        
        mFocusRect = (ImageView)findViewById(R.id.camera_focus_imageview);
        mFocusRect.setImageDrawable(getResources().getDrawable(R.drawable.frame_unfocused_64x48));

        mLocationText = (TextView)findViewById(R.id.camera_textview_location);
        mLocationText.setText("Position: none");

        final ImageButton backButton = (ImageButton)findViewById(R.id.camera_back_button);
        backButton.setImageDrawable(getResources().getDrawable(R.drawable.arrow_back_48x48));
        backButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                CameraActivity.this.finish();
            }            
        });
        
        mForeground = new ForegroundTracker(this);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        mSensorManager.unregisterListener(mSensorListener);
    }
    
    @Override
    public void onPause() {
        super.onPause();
        
        mForeground.background();
        
        if (mServiceBound) 
        	unbindService(mServiceConn);
        this.unregisterReceiver(mLocationReceiver);
    }

    @Override
    public void onResume() {
        super.onResume();

        mServiceBound = bindService(new Intent(this, GeoCamService.class), mServiceConn, Context.BIND_AUTO_CREATE);
        if (!mServiceBound) {
        	Log.e(GeoCamMobile.DEBUG_ID, "CameraActivity::onResume - error binding to service");
        }

        mForeground.foreground();
        
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
            mFocusLed.setImageDrawable(getResources().getDrawable(R.drawable.led_red_16x16));
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
            mFocusText.setText(R.string.camera_focus);

            if (!mLensIsFocused) {
                mFocusLed.setImageDrawable(getResources().getDrawable(R.drawable.led_red_16x16));
                mFocusRect.setImageDrawable(getResources().getDrawable(R.drawable.frame_unfocused_64x48));
                mLensIsFocused = true;
                this.focusLens();
            }
            break;
        case KeyEvent.KEYCODE_CAMERA:
        case KeyEvent.KEYCODE_DPAD_CENTER:
            if (!mPictureTaken) {
                mPictureTaken = true;
                this.takePicture();
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
        parameters.setPreviewSize(w, h);
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
    public void focusLens() {
        mCamera.autoFocus(new Camera.AutoFocusCallback() {
            public void onAutoFocus(boolean success, Camera camera) {
                if (success) {    
                    mFocusLed.setImageDrawable(getResources().getDrawable(R.drawable.led_green_16x16));
                    mFocusRect.setImageDrawable(getResources().getDrawable(R.drawable.frame_focused_64x48));
                }
                else {
                    mFocusLed.setImageDrawable(getResources().getDrawable(R.drawable.led_red_16x16));
                    mFocusRect.setImageDrawable(getResources().getDrawable(R.drawable.frame_unfocused_64x48));
                }
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
        // Store orientation data in description field of mediastore using JSON encoding
        JSONObject imageData = new JSONObject();
        try {
            double[] angles = GeoCamMobile.rpyTransform(mSensorData[0], mSensorData[1], mSensorData[2]);
            imageData.put("rpy", GeoCamMobile.rpySerialize(angles[0], angles[1], angles[2]));
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
    		mLocationText.setText("Position: " + mLocation.getProvider());
    		Log.d(GeoCamMobile.DEBUG_ID, "CameraActivity::LocationReceiver.onReceive");
		}
    }
}
