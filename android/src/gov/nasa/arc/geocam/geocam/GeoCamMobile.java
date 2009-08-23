// TODO: Show image thumbnails on upload progress dialog
// NOTE: Use netcat to list to http request:
//		 nc -l -p 9999 | tee somerequest.http

package gov.nasa.arc.geocam.geocam;

import java.text.NumberFormat;

import org.json.JSONArray;
import org.json.JSONException;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
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
import android.preference.PreferenceManager;
import android.provider.MediaStore;
import android.util.Log;

public class GeoCamMobile extends Activity {

	public static final String DEBUG_ID = "GeoCamMobile";
	protected static final Uri MEDIA_URI = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
	protected static final String UPLOAD_QUEUE_FILENAME = "geocam_upload.json";

	// Settings constants
	protected static final String SETTINGS_SERVER_URL_KEY = "settings_server_url";
	//protected static final String SETTINGS_SERVER_URL_DEFAULT = "https://pepe.arc.nasa.gov/geocam/13f350c721168522";
	//protected static final String SETTINGS_SERVER_URL_DEFAULT = "https://alderaan.arc.nasa.gov/geocam/9c8f3742cfe68a85";
	protected static final String SETTINGS_SERVER_URL_DEFAULT = "https://alderaan.arc.nasa.govx/geocam/9c8f3742cfe68";
	
	protected static final String SETTINGS_SERVER_USERNAME_KEY = "settings_server_username";
	//protected static final String SETTINGS_SERVER_USERNAME_DEFAULT = "jeztek";
	protected static final String SETTINGS_SERVER_USERNAME_DEFAULT = "calfire";

	protected static final String SETTINGS_SERVER_INBOX_KEY = "settings_server_inbox";
	//protected static final String SETTINGS_SERVER_INBOX_DEFAULT = "9-d972";	// pepe
	//protected static final String SETTINGS_SERVER_INBOX_DEFAULT = "4-b015";		// alderaan
	//protected static final String SETTINGS_SERVER_INBOX_DEFAULT = "inbox";
	
	protected static final String SETTINGS_BETA_TEST_KEY = "settings_beta_test";
	protected static final String SETTINGS_BETA_TEST_DEFAULT = "";
	// correct value for beta test secret key.  set to "" to disable checking
	protected static final String SETTINGS_BETA_TEST_CORRECT = "photomap";
	
	protected static final String SETTINGS_RESET_KEY = "settings_reset";

	// Menu constants
	private static final int SETTINGS_ID = Menu.FIRST;
	private static final int ABOUT_ID = Menu.FIRST + 1;
	private static final int EXIT_ID = Menu.FIRST + 2;
		
	public static final int DIALOG_AUTHORIZE_USER = 991;
	public static final int DIALOG_AUTHORIZE_USER_ERROR = 992;

	private LocationManager mLocationManager;
	private Location mLocation;
	private String mProvider;
	private LocationListener mLocationListener = new LocationListener() {
		
		public void onLocationChanged(Location location) {
			GeoCamMobile.this.updateLocation(location);			
		}

		public void onProviderDisabled(String provider) {
			((TextView)findViewById(R.id.main_location_status_textview)).setText(provider + " disabled");
			TextView locationProviderText = ((TextView)findViewById(R.id.main_location_provider_textview));
			locationProviderText.setText(mProvider);
		}

		public void onProviderEnabled(String provider) {
			mProvider = provider;
			((TextView)findViewById(R.id.main_location_status_textview)).setText(provider + " enabled");
			TextView locationProviderText = ((TextView)findViewById(R.id.main_location_provider_textview));
			locationProviderText.setText(mProvider);
		}

		public void onStatusChanged(String provider, int status, Bundle extras) {
			mProvider = provider;
			TextView locationProviderText = ((TextView)findViewById(R.id.main_location_provider_textview));
			locationProviderText.setText(mProvider);

			TextView locationStatusText = ((TextView)findViewById(R.id.main_location_status_textview));
			switch (status) {
			case LocationProvider.AVAILABLE:
				locationStatusText.setText("available");
				break;
			case LocationProvider.TEMPORARILY_UNAVAILABLE:
				locationStatusText.setText("unavailable");
				break;
			case LocationProvider.OUT_OF_SERVICE:
				locationStatusText.setText("no service");
				break;
			default:
				locationStatusText.setText("unknown");
				break;
			}
		}
	};
	
	public static double[] rpyTransform(double roll, double pitch, double yaw) {
		double[] result = new double[3];

//		result[0] = pitch;
//		result[1] = roll;
//		result[2] = -yaw;

		// TODO: Fix this! This is the wrong transform, but yaw appears to be correct
		result[0] = pitch;
		result[1] = -yaw;
		result[2] = roll;
		
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

        loadSettings();
        buildView();

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
        TextView locationProviderText = ((TextView)findViewById(R.id.main_location_provider_textview));
        if (mProvider == null) {
    		locationProviderText.setText("none");        	
        }
        else {
        	mLocationManager.requestLocationUpdates(mProvider, 60000, 10, mLocationListener);
    		locationProviderText.setText(mProvider);
        }
		TextView locationStatusText = ((TextView)findViewById(R.id.main_location_status_textview));
		locationStatusText.setText("ok");
		
		startGeoCamService();
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
    	MenuItem settingsItem = menu.add(0, SETTINGS_ID, 0, R.string.main_menu_settings);
    	settingsItem.setIcon(getResources().getDrawable(R.drawable.settings_48x48));

    	MenuItem aboutItem = menu.add(1, ABOUT_ID, 0, R.string.main_menu_about);
    	aboutItem.setIcon(getResources().getDrawable(R.drawable.nasa_48x48));
    	
    	MenuItem exitItem = menu.add(2, EXIT_ID, 0, R.string.main_menu_exit);
    	exitItem.setIcon(getResources().getDrawable(R.drawable.exit_48x48));

    	return true;
    }

    @Override
    public boolean onMenuItemSelected(int featureId, MenuItem item) {
    	switch(item.getItemId()) {
    	case SETTINGS_ID:
        	Intent i = new Intent(GeoCamMobile.this, SettingsActivity.class);
        	startActivity(i);
    		break;

    	case ABOUT_ID:
    		showDialog(ABOUT_ID);
    		break;

    	case EXIT_ID:
    		stopGeoCamService();
        	this.finish();
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
    	
    	NumberFormat nf = NumberFormat.getInstance();
    	nf.setMaximumFractionDigits(8);
    	if (mLocation != null) {
	    	TextView latText = (TextView)findViewById(R.id.main_latitude_textview);
	    	latText.setText(nf.format(mLocation.getLatitude()) + "\u00b0");
	    	
	    	TextView longText = (TextView)findViewById(R.id.main_longitude_textview);
	    	longText.setText(nf.format(mLocation.getLongitude()) + "\u00b0");
    	}
    }
    
    // called by onCreate()
    private void loadSettings() {
        // Load default preferences
        SharedPreferences settings = PreferenceManager.getDefaultSharedPreferences(this);        
        SharedPreferences.Editor editor = settings.edit();
        if (!settings.contains(SETTINGS_SERVER_URL_KEY)) {
        	editor.putString(SETTINGS_SERVER_URL_KEY, SETTINGS_SERVER_URL_DEFAULT);
        }
        if (!settings.contains(SETTINGS_SERVER_USERNAME_KEY)) {
            editor.putString(SETTINGS_SERVER_USERNAME_KEY, SETTINGS_SERVER_USERNAME_DEFAULT);
        }
        if (!settings.contains(SETTINGS_BETA_TEST_KEY)) {
            editor.putString(SETTINGS_BETA_TEST_KEY, SETTINGS_BETA_TEST_DEFAULT);
        }
        editor.commit();        	
    }
    
    // called by onCreate()
    private void buildView() {
        setContentView(R.layout.main);
        
        final ImageButton takePhotoButton = (ImageButton)findViewById(R.id.main_take_photo_button);
        takePhotoButton.setImageDrawable(getResources().getDrawable(R.drawable.camera_64x64));
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
        browsePhotosButton.setImageDrawable(getResources().getDrawable(R.drawable.browse_64x64));
        browsePhotosButton.setOnClickListener(new View.OnClickListener() {
        	public void onClick(View v) {
            	Intent i = new Intent(Intent.ACTION_VIEW, MEDIA_URI);
            	startActivity(i);
        	}
        });
        final TextView browsePhotosText = (TextView)findViewById(R.id.main_browse_photos_textview);
        browsePhotosText.setText(R.string.main_browse_photos_button);

        final ImageButton uploadPhotosButton = (ImageButton)findViewById(R.id.main_upload_photos_button);
        uploadPhotosButton.setImageDrawable(getResources().getDrawable(R.drawable.sync_64x64));
        uploadPhotosButton.setOnClickListener(new View.OnClickListener() {
        	public void onClick(View v) {
            	Intent i = new Intent(GeoCamMobile.this, UploadPhotosActivity.class);
            	startActivity(i);
        	}
        });

        final TextView uploadPhotosText = (TextView)findViewById(R.id.main_upload_photos_textview);
        uploadPhotosText.setText(R.string.main_upload_photos_button);
        
    	TextView latText = (TextView)findViewById(R.id.main_latitude_textview);
    	latText.setText("0.00\u00b0");
    	
    	TextView longText = (TextView)findViewById(R.id.main_longitude_textview);
    	longText.setText("0.00\u00b0");
    }

    private void startGeoCamService() {
   		startService(new Intent(this, GeoCamService.class));
    }
    
    private void stopGeoCamService() {
    	stopService(new Intent(this, GeoCamService.class));
    }
}
