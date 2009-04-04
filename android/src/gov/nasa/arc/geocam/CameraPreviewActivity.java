package gov.nasa.arc.geocam;

import java.io.OutputStream;

import android.app.Activity;
import android.app.Dialog;
import android.app.ProgressDialog;
import android.content.ContentValues;
import android.content.Context;
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
import android.view.Window;
import android.view.WindowManager;
import android.widget.TextView;
import android.graphics.PixelFormat;

public class CameraPreviewActivity extends Activity implements SurfaceHolder.Callback {

	private static final int DIALOG_SAVE_PROGRESS = 1;
	
	// UI elements
	private ProgressDialog mSaveProgressDialog;
	private TextView mFocusText;
	private TextView mLocationText;
	
	// Camera preview surface
	private SurfaceView mCameraPreview;
	SurfaceHolder mHolder;

	// Camera 
	Camera mCamera;
	private boolean mLensIsFocused = false;
	private boolean mPhotoTaken = false;
	private byte mImageData[];
	
	// Location
	private LocationManager mLocationManager;
	private Location mLocation;
	private String mProvider;
	private LocationListener mLocationListener = new LocationListener() {
		
		public void onLocationChanged(Location location) {
			CameraPreviewActivity.this.updateLocation(location);
		}

		public void onProviderDisabled(String provider) {
			mLocationText.setText("\tLoc: Disabled");
		}

		public void onProviderEnabled(String provider) {
			mLocationText.setText("\tLoc: Enabled");
		}

		public void onStatusChanged(String provider, int status, Bundle extras) {
			switch (status) {
			case LocationProvider.AVAILABLE:
				mLocationText.setText("\tLoc: OK");
				break;
			case LocationProvider.OUT_OF_SERVICE:
				mLocationText.setText("\tLoc: No svc");
				break;
			case LocationProvider.TEMPORARILY_UNAVAILABLE:
				mLocationText.setText("\tLoc: Searching...");
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
            //Log.d(DEBUG_ID, "Orientation: " + angles[0] + ", " + angles[1] + "," + angles[2]);
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
		setContentView(R.layout.camera_preview);

		// Location
        mLocationManager = (LocationManager)getSystemService(Context.LOCATION_SERVICE);
        Criteria criteria = new Criteria();
        criteria.setAccuracy(Criteria.NO_REQUIREMENT);
        mProvider = mLocationManager.getBestProvider(criteria, false);

        // Orientation
        mSensorManager = (SensorManager)getSystemService(Context.SENSOR_SERVICE);

		// Camera
		mCameraPreview = (SurfaceView)findViewById(R.id.camera_preview_surfaceview);
		
		mHolder = mCameraPreview.getHolder();
		mHolder.addCallback(this);
		mHolder.setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);

		// UI elements
		mFocusText = (TextView)findViewById(R.id.camera_preview_textview_focus);
		mFocusText.setText("Focus:   ");				
		
		mLocationText = (TextView)findViewById(R.id.camera_preview_textview_location);
		mLocationText.setText("\tLoc: None");
	}

	@Override
	public void onPause() {
		super.onPause();
		mLocationManager.removeUpdates(mLocationListener);
        mSensorManager.unregisterListener(mSensorListener);
	}

	@Override
	public void onResume() {
		super.onResume();
		mLocationManager.requestLocationUpdates(mProvider, 60000, 10, mLocationListener);
        mSensorManager.registerListener(mSensorListener, 
        		SensorManager.SENSOR_ORIENTATION,
        		SensorManager.SENSOR_DELAY_FASTEST);	}

	public boolean onKeyUp(int keyCode, KeyEvent event) {
		switch (keyCode) {
		case KeyEvent.KEYCODE_FOCUS:
		case KeyEvent.KEYCODE_DPAD_CENTER:
			mFocusText.setText("Focus:   ");				
			mLensIsFocused = false;
			break;
			
		// We intentionally don't unset the photoTaken flag so focus() remains disabled
		// after a photo is taken
		}
		return super.onKeyUp(keyCode, event);		
	}
	
	public boolean onKeyDown(int keyCode, KeyEvent event) {		
		switch (keyCode) {
		case KeyEvent.KEYCODE_FOCUS:
			if (!mLensIsFocused && !mPhotoTaken) {
				this.focusLens();
				mLensIsFocused = true;
			}
			break;
		case KeyEvent.KEYCODE_CAMERA:
		case KeyEvent.KEYCODE_DPAD_CENTER:
			if (!mPhotoTaken) {
				this.takePicture();
				mPhotoTaken = true;
			}
			// Catch camera keycode so we don't inadvertedly launch the built-in camera app
			return true;
		}	
		return super.onKeyDown(keyCode, event);
	}

	@Override
	protected Dialog onCreateDialog(int id) {
		switch(id) {
		case DIALOG_SAVE_PROGRESS:
			mSaveProgressDialog = new ProgressDialog(this);
			mSaveProgressDialog.setMessage(getResources().getString(R.string.camera_preview_saving));
			mSaveProgressDialog.setIndeterminate(true);
			mSaveProgressDialog.setCancelable(false);
			return mSaveProgressDialog;
			
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
					mFocusText.setText("Focus: OK");		
				}
				else {
					mFocusText.setText("Focus:   ");
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
				
				mImageData = data;
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
		double[] angles = GeoCamMobile.rpyTransform(mSensorData[0], mSensorData[1], mSensorData[2]);
		
		// Add some parameters to the image that will be stored in the Image ContentProvider
		ContentValues values = new ContentValues();
		String name = String.valueOf(System.currentTimeMillis());
		values.put(MediaStore.Images.Media.DISPLAY_NAME, name + ".jpg");
		values.put(MediaStore.Images.Media.TITLE, name);
		values.put(MediaStore.Images.Media.DESCRIPTION, GeoCamMobile.rpySerialize(angles[0], angles[1], angles[2])); 
		values.put(MediaStore.Images.Media.BUCKET_DISPLAY_NAME, GeoCamMobile.GEOCAM_BUCKET_NAME);
		values.put(MediaStore.Images.Media.BUCKET_ID, GeoCamMobile.GEOCAM_BUCKET_ID);
		values.put(MediaStore.Images.Media.DATE_TAKEN, System.currentTimeMillis());
		values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
		values.put(MediaStore.Images.Media.SIZE, mImageData.length);
		values.put(MediaStore.Images.Media.LATITUDE, mLocation != null ? mLocation.getLatitude() : 0.0);
		values.put(MediaStore.Images.Media.LONGITUDE, mLocation != null ? mLocation.getLongitude() : 0.0);

		int initNumEntries = getMediaStoreNumEntries();
		Uri uri = saveImage(values);
		if (getMediaStoreNumEntries() <= initNumEntries) {
			Log.d(GeoCamMobile.DEBUG_ID, "Retrying save then deleting original");
			saveImage(values);
		}

		dismissDialog(DIALOG_SAVE_PROGRESS);
	}
	
	private Uri saveImage(ContentValues values) {
		Log.d(GeoCamMobile.DEBUG_ID, "About to insert image into mediastore");
		// Inserting the image meta data inside the content provider
		Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
		Log.d(GeoCamMobile.DEBUG_ID, "Inserted metadata into mediastore with bucket id: " + GeoCamMobile.GEOCAM_BUCKET_ID);
		// Filling the real data returned by the picture callback function into the content provider
		try {
			OutputStream outStream = getContentResolver().openOutputStream(uri);
			outStream.write(mImageData);
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
    	mLocationText.setText("\tLoc: " + String.valueOf(mLocation.getLatitude()) + ", "
    			+ String.valueOf(mLocation.getLongitude()));
    }
	
	private int getMediaStoreNumEntries() {
		String[] projection = new String[] {
				MediaStore.Images.ImageColumns._ID,
				MediaStore.Images.ImageColumns.TITLE,
				MediaStore.Images.ImageColumns.DISPLAY_NAME,
				MediaStore.Images.ImageColumns.BUCKET_ID,
				MediaStore.Images.ImageColumns.BUCKET_DISPLAY_NAME,
				MediaStore.Images.ImageColumns.SIZE,
		};

		Cursor cur = managedQuery(GeoCamMobile.MEDIA_URI, projection, MediaStore.Images.ImageColumns.BUCKET_ID
				+ '=' + GeoCamMobile.GEOCAM_BUCKET_ID, null, null);
		cur.moveToFirst();
		Log.d(GeoCamMobile.DEBUG_ID, "Retrieving list of photos with bucket id: " + GeoCamMobile.GEOCAM_BUCKET_ID);
		int count = 0;
		while (cur.moveToNext()) {
			count++;
		}
		Log.d(GeoCamMobile.DEBUG_ID, "Found " + count + " photos for bucket id: " + GeoCamMobile.GEOCAM_BUCKET_ID);
		return count;
	}
}
