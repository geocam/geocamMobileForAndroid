// __BEGIN_LICENSE__
// Copyright (C) 2008-2010 United States Government as represented by
// the Administrator of the National Aeronautics and Space Administration.
// All Rights Reserved.
// __END_LICENSE__

// TODO: Show image thumbnails on upload progress dialog
// NOTE: Use netcat to list to http request:
//         nc -l -p 9999 | tee somerequest.http

package gov.nasa.arc.geocam.geocam;

import gov.nasa.arc.geocam.geocam.util.ForegroundTracker;

import java.text.NumberFormat;
import java.util.HashMap;

import org.json.JSONArray;
import org.json.JSONException;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.PackageInfo;
import android.content.res.Resources;
import android.location.Location;
import android.location.LocationManager;
import android.location.LocationProvider;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.ImageButton;
import android.widget.TextView;
import android.preference.PreferenceManager;
import android.provider.MediaStore;
import android.provider.Settings;
import android.util.Log;

public class GeoCamMobile extends Activity {
    public static final String VERSION_DATE = "2010-10-14";
    public static final String PACKAGE_NAME = "gov.nasa.arc.geocam.geocam";
	
    public static final String DEBUG_ID = "GeoCamMobile";
    public static final String DEGREE_SYMBOL = "\u00b0";
    protected static final Uri MEDIA_URI = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;

    public static final long POS_UPDATE_MSECS_SLOW = 30000; // normal rate
    public static final long POS_UPDATE_MSECS_ACTIVE = 1000;  // rate when app is in foreground
    public static final long POS_UPDATE_MSECS_RECORDING = 1000;  // rate when recording a track
    public static final long POS_UPDATE_MSECS_FAST = 500;	// this value should always be larger than POS_UPDATE_MSECS_SLOW
    public static final long POS_UPDATE_FAST_EXPIRATION_MSECS = 120000;
    public static final long LOCATION_STALE_MSECS = 120000;	// this value should always be larger than POS_UPDATE_MSECS_SLOW

    public static final long PHOTO_BRACKET_INTERVAL_MSECS = 10000;
    public static final long PHOTO_BRACKET_THRESHOLD_MSECS = 60000;
    
    public static final int[] PHOTO_DOWNSAMPLE_FACTORS = { 4, 2, 1 }; // higher downsample factors should come first
    
    public static final boolean TRACK_ENABLED = false;

    // Conversion between photo downsample factors and their respective priorities
    public static final HashMap<Integer, Integer> PHOTO_PRIORITIES = new HashMap<Integer, Integer>(3);
    static {
    	PHOTO_PRIORITIES.put(4, 15);
    	PHOTO_PRIORITIES.put(2, 10);
    	PHOTO_PRIORITIES.put(1, 5);
    }
    
    public static final int TRACK_PRIORITY = 15;

    // Yaw references
    public static final String YAW_MAGNETIC = "M"; 
    public static final String YAW_TRUE = "T"; 
    
    // Intent actions
    public static final String ACTION_PREFIX = "gov.nasa.arc.geocam.geocam.";
    public static final String ACTION_MAP_TRACK = ACTION_PREFIX + "ACTION_TRACK";
    
    // Intent keys
    public static final String LOCATION_CHANGED = "location_changed";
    public static final String LOCATION_EXTRA = "location_extra";
    public static final String LOCATION_TRACKED = "location_tracked";

    // Settings constants
    protected static final String SETTINGS_SERVER_UPLOAD_ENABLED = "settings_server_upload_enabled";

    protected static final String SETTINGS_SERVER_URL_KEY = "settings_server_url";
    
    protected static final String SETTINGS_SERVER_USERNAME_KEY = "settings_server_username";

    protected static final String SETTINGS_SERVER_PASSWORD_KEY = "settings_server_password";

    protected static final String SETTINGS_SERVER_INBOX_KEY = "settings_server_inbox";
    protected static final String SETTINGS_DEFAULT_NOTES_KEY = "settings_default_notes";
    public static final String SETTINGS_TRACKING_FREQ_KEY = "settings_tracking_freq";

    public static final String SETTINGS_UNIQUE_ID = "settings_other_uid";

    //protected static final String SETTINGS_SERVER_INBOX_DEFAULT = "inbox";
    
    protected static final String SETTINGS_BETA_TEST_KEY = "settings_beta_test";

    // correct value for beta test secret key.  set to "" to disable checking
    protected static final String SETTINGS_BETA_TEST_CORRECT = "photomap";
    
    protected static final String SETTINGS_RESET_KEY = "settings_reset";
    
    // Menu constants
    private static final int SETTINGS_ID = Menu.FIRST;
    private static final int ABOUT_ID = Menu.FIRST + 1;
    private static final int EXIT_ID = Menu.FIRST + 2;
    private static final int TRACK_ID = Menu.FIRST + 3;
        
    public static final int DIALOG_AUTHORIZE_USER = 991;
    public static final int DIALOG_AUTHORIZE_USER_ERROR = 992;
    
    public static final int DIALOG_GPS_OFF_ID = 1000;

    // Location
    private LocationReceiver mLocationReceiver;
    private Location mLocation;
    private String mLocationProvider;
    
    // GeoCam Service
    private IGeoCamService mService;
    private boolean mServiceBound = false;
    private ServiceConnection mServiceConn = new ServiceConnection() {
    	
    	public void onServiceConnected(ComponentName name, IBinder service) {
    		mService = IGeoCamService.Stub.asInterface(service);
    		mServiceBound = true;
    		
            try {
            	mLocation = mService.getLocation();
            	GeoCamMobile.this.updateLocation(mLocation);
            	if (mLocation != null) {
                    Log.d(DEBUG_ID, "GeoCamMobile::onServiceConnected");
            	}
            	else {
            		Log.d(DEBUG_ID, "GeoCamMobile::onServiceConnected - no location");
            	}
            }
            catch (RemoteException e) {
            	Log.e(DEBUG_ID, "GeoCamMobile::onServiceConnected - error getting location from service");
            }
    	}
    	
    	public void onServiceDisconnected(ComponentName name) {
    		mService = null;
    		mServiceBound = false;
    	}
    };
    
    private ForegroundTracker mForeground;
    
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
    
    public GeoCamMobile() {
    	super();
    	Log.d(DEBUG_ID, "GeoCamMobile::GeoCamMobile called [Contructed]");
    }
    
    protected void finalize() throws Throwable {
    	Log.d(DEBUG_ID, "GeoCamMobile::finalize called [Destructed]");
    	super.finalize();
    }
    
    // Called when the activity is first created
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Log.d(DEBUG_ID, "GeoCamMobile::onCreate called");
        
        // This call initializes the default values for all the
        // preference fields.  It won't overwrite any changes that the
        // user made.  We make this call every time the app starts in
        // case the user has upgraded and there are new preference
        // fields that need to be initialized.
        PreferenceManager.setDefaultValues(this, R.xml.preferences, true);        

        // Generate a UID for this phone if there isn't one already
    	SharedPreferences settings = PreferenceManager.getDefaultSharedPreferences(this);
        if(settings.getString(SETTINGS_UNIQUE_ID, "").equals("")) {
            settings.edit().putString(SETTINGS_UNIQUE_ID, GpsDbAdapter.generateId("")).commit();
        }

        buildView();

        mLocationReceiver = new LocationReceiver();
        mLocationProvider = "unknown";
        
        mForeground = new ForegroundTracker(this);

        TextView locationProviderText = ((TextView)findViewById(R.id.main_location_provider_textview));
        locationProviderText.setText(mLocationProvider);

        TextView locationStatusText = ((TextView)findViewById(R.id.main_location_status_textview));
        locationStatusText.setText("unknown"); // can't set this properly until first status update
        
        startGeoCamService();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();        
        Log.d(DEBUG_ID, "GeoCamMobile::onDestroy called");
    }
    
    @Override
    public void onPause() {
        super.onPause();
        Log.d(DEBUG_ID, "GeoCamMobile::onPause called");
        
        mForeground.background();
        
        if (mServiceBound) {
        	unbindService(mServiceConn);
        }
        
        this.unregisterReceiver(mLocationReceiver);
    }
    
    @Override
    public void onResume() {
        super.onResume();
        Log.d(DEBUG_ID, "GeoCamMobile::onResume called");
        
        mForeground.foreground();
        
        mServiceBound = bindService(new Intent(this, GeoCamService.class), mServiceConn, Context.BIND_AUTO_CREATE);
        if (!mServiceBound) {
        	Log.e(DEBUG_ID, "GeoCamMobile::onResume - error binding to service");
        }
        
        IntentFilter filter = new IntentFilter(GeoCamMobile.LOCATION_CHANGED);
        this.registerReceiver(mLocationReceiver, filter);

        LocationManager locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        if(!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
        	showDialog(DIALOG_GPS_OFF_ID);
        }
    }
    
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        super.onCreateOptionsMenu(menu);
        MenuItem settingsItem = menu.add(0, SETTINGS_ID, 0, R.string.main_menu_settings);
        settingsItem.setIcon(getResources().getDrawable(android.R.drawable.ic_menu_preferences));

        MenuItem aboutItem = menu.add(1, ABOUT_ID, 0, R.string.main_menu_about);
        aboutItem.setIcon(getResources().getDrawable(android.R.drawable.ic_menu_info_details));
        
        MenuItem exitItem = menu.add(2, EXIT_ID, 0, R.string.main_menu_exit);
        exitItem.setIcon(getResources().getDrawable(android.R.drawable.ic_menu_close_clear_cancel));
        
        if (TRACK_ENABLED) {
            MenuItem trackItem = menu.add(3, TRACK_ID, 0, "Tracks");
            trackItem.setIcon(getResources().getDrawable(android.R.drawable.ic_menu_mapmode));
        }

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
            
        case TRACK_ID:
            Intent map = new Intent(GeoCamMobile.this, TrackMapActivity.class);
            startActivity(map);        	
        	break;
        }

        return super.onMenuItemSelected(featureId, item);
    }

    @Override
    protected Dialog onCreateDialog(int id) {
    	String versionName = "1.0.x";
    	String versionCode = "XXXX";
        
        String branch = "master";
        String commit = "unknown";
        String date = "XXXX-XX-XX";
    	
        switch (id) {
        case ABOUT_ID: {
            try {
                branch = getString(R.string.version_git_branch);
                commit = getString(R.string.version_git_commit);
                date = getString(R.string.version_date);
                Log.d(DEBUG_ID, "[branch=" + branch + ";commit=" + commit + ";date=" + date + "]");
            } catch (Resources.NotFoundException e) {
                Log.w(DEBUG_ID, "No version information. Does version.xml exist?");
            }

            try {
                PackageInfo info = getPackageManager().getPackageInfo(PACKAGE_NAME, PackageManager.GET_GIDS);
                versionName = info.versionName;
                versionCode = String.valueOf(info.versionCode);
            } catch (PackageManager.NameNotFoundException e) {
                Log.e(DEBUG_ID, "Unable to get version information");
            }
        	
            StringBuilder sb = new StringBuilder();
            sb.append(String.format(getString(R.string.main_about_dialog_message_version), date, branch, commit));
            sb.append("\n\n");
            sb.append(getString(R.string.main_about_dialog_message_contact));
            
            return new AlertDialog.Builder(this)
                .setTitle(String.format(getString(R.string.main_about_dialog_title), versionName))
                .setPositiveButton(R.string.main_about_dialog_ok, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int whichButton) {                        
                    }
                })
                .setMessage(sb.toString())
                .create();
        }
        case DIALOG_GPS_OFF_ID:
        	return new AlertDialog.Builder(this)
            	.setTitle("Warning")
        		.setIcon(android.R.drawable.ic_dialog_alert)
            	.setPositiveButton(R.string.main_gps_warning_ok, new DialogInterface.OnClickListener() {
            		public void onClick(DialogInterface dialog, int whichButton) {
            			startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
            		}
            	})
            	.setNegativeButton(R.string.main_gps_warning_cancel, new DialogInterface.OnClickListener() {
            		public void onClick(DialogInterface dialog, int whichButton) {
            			GeoCamMobile.this.stopGeoCamService();
            			GeoCamMobile.this.finish();
            		}
            	})
            	.setMessage(getString(R.string.main_gps_warning))
            	.create();
        }
        return null;
    }
    
    private void updateLocation(Location location) {
        double lat, lon;
        int status;
        
        if (location == null) {
            lat = 0.0;
            lon = 0.0;
            status = LocationProvider.TEMPORARILY_UNAVAILABLE;
        } else {
            mLocation = location;
            mLocationProvider = mLocation.getProvider();
            status = LocationProvider.AVAILABLE;
            lat = mLocation.getLatitude();
            lon = mLocation.getLongitude();
        }

        NumberFormat nf = NumberFormat.getInstance();
        nf.setMaximumFractionDigits(6);
        TextView latText = (TextView)findViewById(R.id.main_latitude_textview);
        TextView lonText = (TextView)findViewById(R.id.main_longitude_textview);
        latText.setText(nf.format(lat) + DEGREE_SYMBOL);
        lonText.setText(nf.format(lon) + DEGREE_SYMBOL);
		
        ((TextView)findViewById(R.id.main_location_provider_textview)).setText(mLocationProvider);
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
    
    // called by onCreate()
    private void buildView() {
        setContentView(R.layout.main);
        
        final ImageButton takePhotoButton = (ImageButton)findViewById(R.id.main_take_photo_button);
        takePhotoButton.setImageDrawable(getResources().getDrawable(R.drawable.camera_64x64));
        takePhotoButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                Intent i = new Intent(getApplication(), CameraActivity.class);
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
                Intent i = new Intent(getApplication(), UploadPhotosActivity.class);
                startActivity(i);
            }
        });

        final TextView uploadPhotosText = (TextView)findViewById(R.id.main_upload_photos_textview);
        uploadPhotosText.setText(R.string.main_upload_photos_button);
        
        updateLocation(null); // sets lat and lon initial display to 0.0
    }

    private void startGeoCamService() {
    	startService(new Intent(this, GeoCamService.class));
    }
    
    private void stopGeoCamService() {
        stopService(new Intent(this, GeoCamService.class));
    }
    
    class LocationReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            //Log.d(DEBUG_ID, "GeoCamMobile::LocationReceiver.onReceive");
            GeoCamMobile.this.updateLocation((Location)intent.getParcelableExtra(GeoCamMobile.LOCATION_EXTRA));
        }
    }
}
