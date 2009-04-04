// TODO: Make icon set
// TODO: Show image thumbnails on upload progress dialog
// NOTE: Use netcat to list to http request:
//		 nc -l -p 9999 | tee somerequest.http

package gov.nasa.arc.geocam;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.location.Criteria;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.provider.MediaStore;

public class GeoCamMobile extends Activity {

	public static final String DEBUG_ID = "GeoCamMobile";
	
	public static final int GEOCAM_BUCKET_ID = 630683;
	public static final int GEOCAM_UPLOADED_BUCKET_ID = 630684;
	public static final String GEOCAM_BUCKET_NAME = "geocam";
	public static final String GEOCAM_UPLOADED_BUCKET_NAME = "geocam_uploaded";
	
	public static final Uri MEDIA_URI = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
	
	private static final int ABOUT_ID = Menu.FIRST;

	private LocationManager mLocationManager;
	private Location mLocation;
	private String mProvider;
	private LocationListener mLocationListener = new LocationListener() {
		
		public void onLocationChanged(Location location) {
			GeoCamMobile.this.updateLocation(location);			
		}

		public void onProviderDisabled(String provider) {
		}

		public void onProviderEnabled(String provider) {
		}

		public void onStatusChanged(String provider, int status, Bundle extras) {
		}
	};
	
	public static double[] rpyTransform(double roll, double pitch, double yaw) {
		double[] result = new double[3];

		result[0] = pitch;
		result[1] = roll;
		result[2] = -yaw;

		return result;
	}

	public static String rpySerialize(double roll, double pitch, double yaw) {
		String result =  "roll[" + String.valueOf(roll) + "] pitch[" + String.valueOf(pitch) + "] yaw[" + String.valueOf(yaw) + "]";
		return result;
	}
	
	public static double[] rpyUnSerialize(String data) {
		double[] result = new double[3];
		result[0] = result[1] = result[2] = 0.0;

		if (data.equals("")) {
			return result;
		}

		try {
			int rollIdx = data.indexOf("roll[");
			int pitchIdx = data.indexOf("pitch[");
			int yawIdx = data.indexOf("yaw[");
			
			String rollStr = data.substring(rollIdx+6, data.indexOf("]", rollIdx+5));
			String pitchStr = data.substring(pitchIdx+7, data.indexOf("]", pitchIdx+6));
			String yawStr = data.substring(yawIdx+5, data.indexOf("]", yawIdx+4));

			result[0] = new Double(rollStr);
			result[1] = new Double(pitchStr);
			result[2] = new Double(yawStr);
		}
		catch (IndexOutOfBoundsException e) {
			result[0] = result[1] = result[2] = 0.0;
		}
		return result;
	}
	
    /** Called when the activity is first created. */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);
        
        final Button takePhotoButton = (Button)findViewById(R.id.main_take_photo_button);
        takePhotoButton.setOnClickListener(new View.OnClickListener() {
        	public void onClick(View v) {
        		takePhoto();
        	}
        });
        
        final Button browsePhotosButton = (Button)findViewById(R.id.main_browse_photos_button);
        browsePhotosButton.setOnClickListener(new View.OnClickListener() {
        	public void onClick(View v) {
        		browsePhotos();
        	}
        });

        final Button uploadPhotosButton = (Button)findViewById(R.id.main_upload_photos_button);
        uploadPhotosButton.setOnClickListener(new View.OnClickListener() {
        	public void onClick(View v) {
        		uploadPhotos();
        	}
        });
        
    	TextView latText = (TextView)findViewById(R.id.main_latitude_textview);
    	latText.setText("Latitude:\t\t0.00");
    	
    	TextView longText = (TextView)findViewById(R.id.main_longitude_textview);
    	longText.setText("Longitude:\t0.00");

        mLocationManager = (LocationManager)getSystemService(Context.LOCATION_SERVICE);
        Criteria criteria = new Criteria();
        criteria.setAccuracy(Criteria.NO_REQUIREMENT);
        mProvider = mLocationManager.getBestProvider(criteria, false);
    }
    
    @Override
    public void onPause() {
    	super.onPause();
    	mLocationManager.removeUpdates(mLocationListener);
    }
    
    @Override
    public void onResume() {
    	super.onResume();
    	mLocationManager.requestLocationUpdates(mProvider, 60000, 10, mLocationListener);
    }
    
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
    	super.onCreateOptionsMenu(menu);
    	MenuItem aboutItem = menu.add(0, ABOUT_ID, 0, R.string.main_menu_about);
    	aboutItem.setIcon(getResources().getDrawable(R.drawable.icon));
    	return true;
    }

    @Override
    public boolean onMenuItemSelected(int featureId, MenuItem item) {
    	switch(item.getItemId()) {
    	case ABOUT_ID:
    		showDialog(ABOUT_ID);
    		break;
    	}
    	    	
    	return super.onMenuItemSelected(featureId, item);
    }
    
    @Override
    protected Dialog onCreateDialog(int id) {
    	switch (id) {
    	case ABOUT_ID:
    		return new AlertDialog.Builder(this)
    			.setTitle(R.string.main_about_dialog_title)
    			.setPositiveButton(R.string.main_about_dialog_ok, new DialogInterface.OnClickListener() {
    				public void onClick(DialogInterface dialog, int whichButton) {    					
    				}
    			})
    			.setMessage(R.string.main_about_dialog_message)
    			.create();
    	}
    	return null;
    }
    
    public void takePhoto() {
    	Intent i = new Intent(this, CameraPreviewActivity.class);
    	//Intent i = new Intent("android.media.action.IMAGE_CAPTURE");
    	startActivity(i);
    }
    
    public void browsePhotos() {
    	Intent i = new Intent(Intent.ACTION_VIEW, MEDIA_URI);
    	startActivity(i);
    }

    public void uploadPhotos() {
    	Intent i = new Intent(this, UploadPhotosActivity.class);
    	startActivity(i);
    }
    
    private void updateLocation(Location location) {
    	mLocation = location;
    	
    	TextView latText = (TextView)findViewById(R.id.main_latitude_textview);
    	latText.setText("Latitude:\t\t" + String.valueOf(mLocation.getLatitude()));
    	
    	TextView longText = (TextView)findViewById(R.id.main_longitude_textview);
    	longText.setText("Longitude:\t" + String.valueOf(mLocation.getLongitude()));
    }
}
