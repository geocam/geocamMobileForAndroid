// TODO: Make icon set
// TODO: Show image thumbnails on upload progress dialog
// NOTE: Use netcat to list to http request:
//		 nc -l -p 9999 | tee somerequest.http

package gov.nasa.arc.geocam;

import org.json.JSONArray;
import org.json.JSONException;

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
import android.location.LocationProvider;
import android.net.Uri;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.ImageButton;
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
			TextView locationProviderText = ((TextView)findViewById(R.id.main_location_provider_textview));
			locationProviderText.setText(mProvider);
			GeoCamMobile.this.updateLocation(location);			
		}

		public void onProviderDisabled(String provider) {
			((TextView)findViewById(R.id.main_location_status_textview)).setText("Location provider disabled");
			TextView locationProviderText = ((TextView)findViewById(R.id.main_location_provider_textview));
			locationProviderText.setText(mProvider);
		}

		public void onProviderEnabled(String provider) {
			((TextView)findViewById(R.id.main_location_status_textview)).setText("Location provider enabled");
			TextView locationProviderText = ((TextView)findViewById(R.id.main_location_provider_textview));
			locationProviderText.setText(mProvider);
		}

		public void onStatusChanged(String provider, int status, Bundle extras) {
			TextView locationProviderText = ((TextView)findViewById(R.id.main_location_provider_textview));
			locationProviderText.setText(mProvider);

			TextView locationStatusText = ((TextView)findViewById(R.id.main_location_status_textview));
			switch (status) {
			case LocationProvider.AVAILABLE:
				locationStatusText.setText("Location: Available via " + mProvider);
				break;
			case LocationProvider.TEMPORARILY_UNAVAILABLE:
				locationStatusText.setText("Location: Unavailable");
				break;
			case LocationProvider.OUT_OF_SERVICE:
				locationStatusText.setText("Location: No Service");
				break;
			default:
				locationStatusText.setText("Location: Unknown");
				break;
			}
		}
	};
	
	public static double[] rpyTransform(double roll, double pitch, double yaw) {
		double[] result = new double[3];

		result[0] = pitch;
		result[1] = roll;
		result[2] = -yaw;

		return result;
	}

	public static String rpySerialize(double roll, double pitch, double yaw) throws JSONException {
		JSONArray rpy = new JSONArray("[" + String.valueOf(roll) + "," + String.valueOf(pitch) + ","
				+ String.valueOf(yaw) + "]");
		return rpy.toString();
	}
	
	public static double[] rpyUnSerialize(String data) throws JSONException {
		double[] result = new double[3];

		JSONArray rpy = new JSONArray(data);
		result[0] = rpy.getDouble(0);
		result[1] = rpy.getDouble(1);
		result[2] = rpy.getDouble(2);
		
		return result;		
	}
	
    // Called when the activity is first created
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);
        
        final ImageButton takePhotoButton = (ImageButton)findViewById(R.id.main_take_photo_button);
        takePhotoButton.setImageDrawable(getResources().getDrawable(R.drawable.camera));
        takePhotoButton.setOnClickListener(new View.OnClickListener() {
        	public void onClick(View v) {
            	Intent i = new Intent(GeoCamMobile.this, CameraActivity.class);
            	//i.addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY);
            	startActivity(i);
        	}
        });
        final TextView takePhotoText = (TextView)findViewById(R.id.main_take_photo_textview);
        takePhotoText.setText(R.string.main_take_photo_button);
        
        final ImageButton browsePhotosButton = (ImageButton)findViewById(R.id.main_browse_photos_button);
        browsePhotosButton.setImageDrawable(getResources().getDrawable(R.drawable.browse));
        browsePhotosButton.setOnClickListener(new View.OnClickListener() {
        	public void onClick(View v) {
            	Intent i = new Intent(Intent.ACTION_VIEW, MEDIA_URI);
            	startActivity(i);
        	}
        });
        final TextView browsePhotosText = (TextView)findViewById(R.id.main_browse_photos_textview);
        browsePhotosText.setText(R.string.main_browse_photos_button);

        final ImageButton uploadPhotosButton = (ImageButton)findViewById(R.id.main_upload_photos_button);
        uploadPhotosButton.setImageDrawable(getResources().getDrawable(R.drawable.sync));
        uploadPhotosButton.setOnClickListener(new View.OnClickListener() {
        	public void onClick(View v) {
            	Intent i = new Intent(GeoCamMobile.this, UploadPhotosActivity.class);
            	startActivity(i);
        	}
        });
        final TextView uploadPhotosText = (TextView)findViewById(R.id.main_upload_photos_textview);
        uploadPhotosText.setText(R.string.main_upload_photos_button);
        
    	TextView latText = (TextView)findViewById(R.id.main_latitude_textview);
    	latText.setText("Latitude:\t\t0.00");
    	
    	TextView longText = (TextView)findViewById(R.id.main_longitude_textview);
    	longText.setText("Longitude:\t0.00");

        mLocationManager = (LocationManager)getSystemService(Context.LOCATION_SERVICE);
        Criteria criteria = new Criteria();
        criteria.setAccuracy(Criteria.ACCURACY_FINE);
        criteria.setAccuracy(Criteria.ACCURACY_COARSE);
        mProvider = mLocationManager.getBestProvider(criteria, true);
    	mLocationManager.requestLocationUpdates(mProvider, 60000, 10, mLocationListener);
		TextView locationProviderText = ((TextView)findViewById(R.id.main_location_provider_textview));
		locationProviderText.setText(mProvider);
    }
    
    @Override
    public void onDestroy() {
    	super.onDestroy();
    	mLocationManager.removeUpdates(mLocationListener);
    }
    
    @Override
    public void onPause() {
    	super.onPause();
    }
    
    @Override
    public void onResume() {
    	super.onResume();

		Location location = mLocationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
		if (location == null) {
			location = mLocationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);			
		}
    	this.updateLocation(location);
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
    
    private void updateLocation(Location location) {
    	mLocation = location;
    	
    	if (mLocation != null) {
	    	TextView latText = (TextView)findViewById(R.id.main_latitude_textview);
	    	latText.setText("Latitude:\t\t" + String.valueOf(mLocation.getLatitude()));
	    	
	    	TextView longText = (TextView)findViewById(R.id.main_longitude_textview);
	    	longText.setText("Longitude:\t" + String.valueOf(mLocation.getLongitude()));
    	}
    }
}
