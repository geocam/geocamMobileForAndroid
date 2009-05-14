package gov.nasa.arc.geocam;

import java.io.OutputStream;

import org.json.JSONException;
import org.json.JSONObject;

import android.app.Activity;
import android.app.Dialog;
import android.app.ProgressDialog;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.database.Cursor;
import android.hardware.Camera;
import android.hardware.SensorListener;
import android.hardware.SensorManager;
import android.location.Criteria;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.location.LocationProvider;
import android.net.Uri;
import android.os.Bundle;
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

	private static final int DIALOG_SAVE_PROGRESS = 1;
	private static final int DIALOG_HIDE_KEYBOARD = 2;
	
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

	// Location
	private LocationManager mLocationManager;
	private Location mLocation;
	private String mProvider;
	private LocationListener mLocationListener = new LocationListener() {
		
		public void onLocationChanged(Location location) {
			CameraActivity.this.updateLocation(location);
		}

		public void onProviderDisabled(String provider) {
			mLocationText.setText("Position: " + provider + " disabled");
		}

		public void onProviderEnabled(String provider) {
			mProvider = provider;
			mLocationText.setText("Position: " + mProvider + " enabled");
		}

		public void onStatusChanged(String provider, int status, Bundle extras) {
			mProvider = provider;
			switch (status) {
			case LocationProvider.AVAILABLE:
				mLocationText.setText("Position: " + mProvider + " available");
				break;
			case LocationProvider.OUT_OF_SERVICE:
				mLocationText.setText("Position: " + mProvider + " no service");
				break;
			case LocationProvider.TEMPORARILY_UNAVAILABLE:
				mLocationText.setText("Position: " + mProvider + " unavailable");
				break;
			}
		}		
	};

	// Orientation
	private SensorManager mSensorManager;
    private float[] mSensorData;
    private final SensorListener mSensorListener = new SensorListener() {
    
        public void onSensorChanged(int sensor, float[] values) {
            mSensorData = values;

            //double[] angles = GeoCamMobile.rpyTransform(mSensorData[0], mSensorData[1], mSensorData[2]);
            //Log.d(GeoCamMobile.DEBUG_ID, "Orientation: " + angles[0] + ", " + angles[1] + "," + angles[2]);
        }

        public void onAccuracyChanged(int sensor, int accuracy) {            
        }
    };
	
	// Activity methods
	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
				
		// Window and view properties
		requestWindowFeature(Window.FEATURE_NO_TITLE);
		getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
		setContentView(R.layout.camera);

		// Location
        mLocationManager = (LocationManager)getSystemService(Context.LOCATION_SERVICE);
        Criteria criteria = new Criteria();
        criteria.setAccuracy(Criteria.ACCURACY_FINE);
        mProvider = mLocationManager.getBestProvider(criteria, true);
        /*
        if (!mProvider.equals("gps")) {
        	criteria = new Criteria();
        	criteria.setAccuracy(Criteria.ACCURACY_COARSE);
            mProvider = mLocationManager.getBestProvider(criteria, true);
        }
        */
        if (mProvider != null) {
        	mLocationManager.requestLocationUpdates(mProvider, 60000, 10, mLocationListener);
        }
        
        // Orientation
        mSensorManager = (SensorManager)getSystemService(Context.SENSOR_SERVICE);
        mSensorManager.registerListener(mSensorListener, 
        		SensorManager.SENSOR_ORIENTATION,
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
	}

	@Override
	public void onDestroy() {
		super.onDestroy();
		mLocationManager.removeUpdates(mLocationListener);
        mSensorManager.unregisterListener(mSensorListener);
	}
	
	@Override
	public void onPause() {
		super.onPause();
	}

	@Override
	public void onResume() {
		super.onResume();

		/* This call changed in SDK 1.5 so we disable it for now 
		if (getResources().getConfiguration().keyboardHidden == Configuration.KEYBOARDHIDDEN_NO) {
			showDialog(DIALOG_HIDE_KEYBOARD);
		}
		*/
		
		// Unset focus and picture status flags when returning from another activity
		mLensIsFocused = false;
		mPictureTaken = false;
		
		mLocation = mLocationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
		if (mLocation == null) {
			mLocation = mLocationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);			
		}
	}

	// Show hide keyboard dialog if keyboard is open in this activity
	// This overriding this method with the appropriate entry in the manifest
	// prevents the activity from restarting when the hardware keyboard is opened
	@Override
	public void onConfigurationChanged(Configuration newConfig) {
		super.onConfigurationChanged(newConfig);

		/*
		Log.d(GeoCamMobile.DEBUG_ID, "Keyboard hidden: " + String.valueOf(newConfig.keyboardHidden));
		if (newConfig.keyboardHidden == Configuration.KEYBOARDHIDDEN_NO) {
			showDialog(DIALOG_HIDE_KEYBOARD);
		}
		else if (newConfig.keyboardHidden == Configuration.KEYBOARDHIDDEN_YES) {
			dismissDialog(DIALOG_HIDE_KEYBOARD);
		}
		*/
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
		parameters.setPictureSize(640, 480);
		mCamera.setParameters(parameters);
		mCamera.startPreview();
	}

	public void surfaceCreated(SurfaceHolder holder) {
		mCamera = Camera.open();
		mCamera.setPreviewDisplay(holder);		
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
	}

	private void saveImage() {
		// Store orientation data in description field of mediastore using JSON encoding
		JSONObject imageData = new JSONObject();
		try {
			double[] angles = GeoCamMobile.rpyTransform(mSensorData[0], mSensorData[1], mSensorData[2]);
			imageData.put("rpy", GeoCamMobile.rpySerialize(angles[0], angles[1], angles[2]));
			Log.d(GeoCamMobile.DEBUG_ID, "Saving image with data: " + imageData.toString());
		}
		catch (JSONException e) {
			Log.d(GeoCamMobile.DEBUG_ID, "Error while adding JSON data to image");			
		}
		mImageData = imageData;

		// Add some parameters to the image that will be stored in the Image ContentProvider
		ContentValues values = new ContentValues();
		String name = String.valueOf(System.currentTimeMillis());
		values.put(MediaStore.Images.Media.DISPLAY_NAME, name + ".jpg");
		values.put(MediaStore.Images.Media.TITLE, name);
		values.put(MediaStore.Images.Media.DESCRIPTION, mImageData.toString());
		//values.put(MediaStore.Images.Media.BUCKET_DISPLAY_NAME, GeoCamMobile.GEOCAM_BUCKET_NAME);
		//values.put(MediaStore.Images.Media.BUCKET_ID, GeoCamMobile.GEOCAM_BUCKET_ID);
		values.put(MediaStore.Images.Media.DATE_TAKEN, System.currentTimeMillis());
		values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
		values.put(MediaStore.Images.Media.SIZE, mImageBytes.length);
		values.put(MediaStore.Images.Media.LATITUDE, mLocation != null ? mLocation.getLatitude() : 0.0);
		values.put(MediaStore.Images.Media.LONGITUDE, mLocation != null ? mLocation.getLongitude() : 0.0);

		// There appears to be a bug where saveImage() sometimes fails to actually create an entry 
		// in the db so we do one retry 
		int initNumEntries = getMediaStoreNumEntries();
		mImageUri = saveImage(values);
		if (getMediaStoreNumEntries() <= initNumEntries) {
			Log.d(GeoCamMobile.DEBUG_ID, "Retrying save");
			mImageUri = saveImage(values);
		}

		dismissDialog(DIALOG_SAVE_PROGRESS);
		
		// Start camera preview activity
		Intent i = new Intent(Intent.ACTION_VIEW, mImageUri);
		i.setClass(CameraActivity.this, CameraPreviewActivity.class);
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
	
	private void updateLocation(Location location) {
    	mLocation = location;
    	mLocationText.setText("Position: " + mProvider);
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
}
