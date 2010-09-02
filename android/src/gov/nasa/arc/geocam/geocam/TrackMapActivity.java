// __BEGIN_LICENSE__
// Copyright (C) 2008-2010 United States Government as represented by
// the Administrator of the National Aeronautics and Space Administration.
// All Rights Reserved.
// __END_LICENSE__

package gov.nasa.arc.geocam.geocam;

import gov.nasa.arc.geocam.geocam.util.ForegroundTracker;

import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.database.Cursor;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Point;
import android.graphics.drawable.Drawable;
import android.hardware.GeomagneticField;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.Toast;

import com.google.android.maps.GeoPoint;
import com.google.android.maps.MapActivity;
import com.google.android.maps.MapView;
import com.google.android.maps.Overlay;
import com.google.android.maps.Projection;

public class TrackMapActivity extends MapActivity {
	private static final String TAG = "TrackMapActivity";
	
	private static final int DIALOG_SAVE_ID = 1;
	private static final int DIALOG_TRACK_EMPTY_ID = 2;
	
	private static final int MENU_LOCATION_ID = 1;
	private static final int MENU_ZOOM_TO_TRACK_ID = 2;
	
	private static final class GeoBounds {
		//private static final int LAT_OFFSET = 90000000;
		//private static final int LON_OFFSET = 180000000;
		
		private int mUpperLeftLat = 0;
		private int mUpperLeftLon = 0;
		private int mLowerRightLat = 0;
		private int mLowerRightLon = 0;
		
		public GeoBounds() { 			
		}
		
		public GeoBounds(GeoPoint center, int latSpan, int lonSpan) {
			setBounds(center, latSpan, lonSpan);
		}
		
		public void setBounds(GeoPoint center, int latSpan, int lonSpan) {
			mUpperLeftLat = (center.getLatitudeE6() - (latSpan / 2));
			mUpperLeftLon = (center.getLongitudeE6() - (lonSpan / 2));
			mLowerRightLat = (center.getLatitudeE6() + (latSpan / 2));
			mLowerRightLon = (center.getLongitudeE6() + (lonSpan / 2));
		}
		
		// Add a Geopoint to the current bounds, expanding if need be
		public void add(GeoPoint point) {
			int translatedLat = point.getLatitudeE6();
			int translatedLon = point.getLongitudeE6();
			
			if ((translatedLat > mUpperLeftLat) || (mUpperLeftLat == 0))
				mUpperLeftLat = translatedLat;
			
			if ((translatedLat < mLowerRightLat) || (mLowerRightLat == 0))
				mLowerRightLat = translatedLat;
			
			if ((translatedLon > mLowerRightLon) || (mLowerRightLon == 0))
				mLowerRightLon = translatedLon;
			
			if ((translatedLon < mUpperLeftLon) || (mUpperLeftLon == 0))
				mUpperLeftLon = translatedLon;
		}
		
		public boolean contains(int lat, int lon) {
			if (lat > mLowerRightLat || mUpperLeftLat > lat)
				return false;
			
			if (lon > mLowerRightLon || mUpperLeftLon > lon)
				return false;
			
			return true;		
		}
		
		public boolean contains(GeoPoint point) {
			return contains(point.getLatitudeE6(), point.getLongitudeE6());
		}
		
		public boolean isEmpty() {
			return (mUpperLeftLat == 0 && 
					mUpperLeftLon == 0 && 
					mLowerRightLon == 0 &&
					mLowerRightLat == 0);
		}
		
		public int getLatSpan() {
			Log.d(TAG, "Lat span: " + (mUpperLeftLat - mLowerRightLat));
			return Math.abs(mUpperLeftLat - mLowerRightLat);
		}
		public int getLonSpan() {
			int span;
			if (mUpperLeftLon > mLowerRightLon) {
				span = (mLowerRightLon + 360000000) - mUpperLeftLon;
			} else {
				span = mLowerRightLon - mUpperLeftLon;
			}
			
			Log.d(TAG, "Longitude Span: " + span);
			return span;
		}
		
		public GeoPoint getCenter() {
			int latitude = (mLowerRightLat + mUpperLeftLat) / 2;;
			int longitude = (mUpperLeftLon + (getLonSpan() / 2));
			
			Log.d(TAG, "GeoPoint center: " + latitude + ", " + longitude);
			
			return new GeoPoint(latitude, longitude);
		}
	}
	
	protected static class PolyLineOverlay extends Overlay {
		private Paint mPaint;
		protected LinkedList<GeoPoint> mPoints;
		
		private Point mPoint = new Point();
		private Point mPrevPoint = new Point();
		
		public void draw(Canvas canvas, MapView mapView, boolean shadow) {
			if (shadow == true) return;
			
			if (mPoints.size() == 0) return;
			
			// Speed-up accessors .. See Dev Guide
			Paint paint = mPaint;
			Point point = mPoint;
			Point prevPoint = mPrevPoint;
			
			Projection projection = mapView.getProjection();
						
			boolean firstPoint = true;
			ListIterator<GeoPoint> iterator = mPoints.listIterator();
			while (iterator.hasNext()) {
				GeoPoint geoPoint = iterator.next();
				
				projection.toPixels(geoPoint, point);
				
				if (point == prevPoint)
					continue;
				
				if (firstPoint) {
					canvas.drawPoint(point.x, point.y, paint);
					firstPoint = false;
				} else {
					canvas.drawLine(prevPoint.x, prevPoint.y, point.x, point.y, paint);
				}
				
				prevPoint.set(point.x, point.y);
			}

		}
		
		public PolyLineOverlay() { 
			mPaint = new Paint();
			mPaint.setColor(0x99ff0000);
			mPaint.setStrokeCap(Paint.Cap.ROUND);
			mPaint.setStyle(Paint.Style.STROKE);
			mPaint.setStrokeWidth(10);
			
			mPoints = new LinkedList<GeoPoint>();
		}
		
		public Paint getPaint() { return mPaint; }
		public void setPaint(Paint paint) {
			if (paint == null)
				return;
			mPaint = paint;
		}
		
		public void clearPoints() {
			mPoints.clear();
		}
		
		public void addPoint(GeoPoint point) {
			mPoints.add(point);
		}
	}
	
	public class TrackOverlay extends Overlay {
		GpsDbAdapter mDb;
		
		private LinkedList<PolyLineOverlay> mSegments;
		private long mTrackId;
		
		private GeoBounds mBounds;
		
		public TrackOverlay(long trackId) {
			mTrackId = trackId;
			mDb = new GpsDbAdapter(TrackMapActivity.this);
			
			mSegments = new LinkedList<PolyLineOverlay>();
			
			refresh();
		}
		
		public void draw(Canvas canvas, MapView mapView, boolean shadow) {
			ListIterator<PolyLineOverlay> iterator = mSegments.listIterator();
			while (iterator.hasNext()) {
				PolyLineOverlay segment = iterator.next();
				segment.draw(canvas, mapView, shadow);
			}
		}
		
		public void refresh() {
			mSegments.clear();
			mBounds = new GeoBounds();
			
			mDb.open();
			
			if (mTrackId == -1) {
				mTrackId = mDb.getLatestTrackId();
				if (mTrackId == -1) {
					return;
				}
			}
			
			Cursor points = mDb.getTrackPoints(mTrackId);
			
			Log.d(TAG, "displaying track " + mTrackId + " with " + points.getCount() + " points");
			
			if (points.getCount() == 0) {
				points.close();
				mDb.close();
				return;
			}
			
			int latIndex = points.getColumnIndex(GpsDbAdapter.KEY_POINT_LATITUDE);
			int lonIndex = points.getColumnIndex(GpsDbAdapter.KEY_POINT_LONGITUDE);
			int segIndex = points.getColumnIndex(GpsDbAdapter.KEY_POINT_TRACK_SEGMENT);
			
			long prevSegment = -1;
			PolyLineOverlay currSegment = null;
			
			points.moveToFirst();
			do {
				long segment = points.getLong(segIndex);
				if (segment != prevSegment) {
					prevSegment = segment;
					currSegment = new PolyLineOverlay();
					mSegments.add(currSegment);
				}
				
				GeoPoint currPoint = new GeoPoint((int) (points.getFloat(latIndex) * 1000000),
			              						  (int) (points.getFloat(lonIndex) * 1000000));
				currSegment.addPoint(currPoint);
				mBounds.add(currPoint);
			} while(points.moveToNext());
			
			points.close();
			mDb.close();
		}
		
		public void addPoint(int lat, int lon) {
			if (mSegments.size() == 0)
				mSegments.add(new PolyLineOverlay());
			
			PolyLineOverlay overlay = mSegments.getLast();
			overlay.addPoint(new GeoPoint(lat, lon));
		}
		
		public GeoBounds getBounds() {
			return mBounds;
		}
	}
	
	private class LocationOverlay extends Overlay implements SensorEventListener {		
		private SensorManager mSensorManager;
		
		private GeomagneticField mMagField = null;
		private GeoPoint mCurrentLocation = null;
		private float mCurrentHeading = 0;
		
		private boolean mLocationEnabled = false;
		private boolean mOrientationEnabled = false;
		
		private MapView mmMap = null;
		
		private BroadcastReceiver mLocationReceiver = new BroadcastReceiver() {
			@Override
			public void onReceive(Context context, Intent intent) {
				if (!intent.getAction().equals(GeoCamMobile.LOCATION_CHANGED))
					return;

				//Log.d(TAG, "Got a new location from the service.");
				Location l = (Location) intent.getExtras().get(GeoCamMobile.LOCATION_EXTRA);
				updateLocation(l);
			}
		};
		
		public LocationOverlay() {
			Log.d(TAG, "LocationOverlay: Constructor");
			mDrawable = TrackMapActivity.this.getResources().getDrawable(R.drawable.heading);
			mDrawableWidth = mDrawable.getIntrinsicWidth() - 7;
			mDrawableHeight = mDrawable.getIntrinsicHeight();
			
			mDrawable.setBounds(0, 0, mDrawableWidth, mDrawableHeight);
			
			mmMap = (MapView) TrackMapActivity.this.findViewById(R.id.track_map);
		}

		public void enableLocation() {
			Log.d(TAG, "LocationOverlay: Enabling location");
			
			// Location (use the service)
			IntentFilter filter = new IntentFilter();
			filter.addAction(GeoCamMobile.LOCATION_CHANGED);
			registerReceiver(mLocationReceiver, filter);
			mLocationEnabled = true;
	        
	        // Sensor Manager (orientation)
	        mSensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
	        
	        List<Sensor> sensors = mSensorManager.getSensorList(Sensor.TYPE_ORIENTATION);
	        if (sensors.size() > 0) {
	        	Sensor s = sensors.get(0);
	        	mOrientationEnabled = mSensorManager.registerListener(this, s, SensorManager.SENSOR_DELAY_UI);	
	        }
		}
		
		public void disableLocation() {
			Log.d(TAG, "LocationOverlay: Disabling location");
			
			if (mLocationEnabled) {
				unregisterReceiver(mLocationReceiver);
				mLocationEnabled = false;
			}
			
			if (mOrientationEnabled) {
				mSensorManager.unregisterListener(this);
				mOrientationEnabled = false;
			}
			
			mCurrentLocation = null;
			mCurrentHeading = 0;
			mmMap.invalidate();
		}
		
		private Point mPoint = new Point();
		
		private Drawable mDrawable;
		private int mDrawableWidth;
		private int mDrawableHeight;
		
		public void draw(Canvas canvas, MapView mapView, boolean shadow) {
			if (shadow) return;
			
			if (mCurrentLocation == null) return;

			Point point = mPoint;
			
			Projection projection;
			projection = mapView.getProjection();
			projection.toPixels(mCurrentLocation, point);

			canvas.save();
			canvas.translate(point.x - (mDrawableWidth / 2), point.y - (mDrawableHeight / 2) );
			canvas.rotate(mCurrentHeading, mDrawableWidth / 2, mDrawableHeight / 2);

			mDrawable.draw(canvas);
			
			canvas.restore();
		}

		public void updateLocation(Location location) {
			mCurrentLocation = new GeoPoint((int) (location.getLatitude() * 1000000),
						                    (int) (location.getLongitude() * 1000000));
			
			if (mMagField == null) {
				mMagField = new GeomagneticField((float) location.getLatitude(), 
												 (float) location.getLongitude(), 
												 (float) location.getAltitude(), 
												 System.currentTimeMillis());
			}
			//mCurrentHeading = location.getBearing();
			//Log.d(TAG, "heading: " + mCurrentHeading);
			mmMap.invalidate();
		}

		// SensorEventListener Methods
		public void onSensorChanged(SensorEvent event) {
			if (event == null) return;
			
			if (mMagField != null)
				event.values[0] -= mMagField.getDeclination();
			
			mCurrentHeading = event.values[0];
			mmMap.invalidate();
		}
		
		public void onAccuracyChanged(Sensor sensor, int accuracy) { }

	}
	
    // Location/Track Service
    private IGeoCamService mService;
    private boolean mServiceBound = false;
    private ServiceConnection mServiceConn = new ServiceConnection() {
    	
    	public void onServiceConnected(ComponentName name, IBinder service) {
    		mService = IGeoCamService.Stub.asInterface(service);
    		mServiceBound = true;
    		
            try {            	
            	if(mService.isRecordingTrack()) {
            		mSaveButton.setVisibility(Button.VISIBLE);
            		if(mService.isTrackPaused()) {
            			mStateButton.setText("Resume");
            		} else {
            			mStateButton.setText("Pause");
            		}
            	} else {
            		mStateButton.setText("Start");
            		mSaveButton.setVisibility(Button.INVISIBLE);
            	}
            }
            catch (RemoteException e) {
            	Log.e(TAG, "GeoCamMobile::onServiceConnected - error getting location from service");
            }
    	}
    	
    	public void onServiceDisconnected(ComponentName name) {
    		mService = null;
    		mServiceBound = false;
    	}
    };
    
    // Overlays
	TrackOverlay mOverlay = null;
	MapView mMap = null;
	LocationOverlay mLocationOverlay = null;
	
	// Receiver
	private LocationReceiver mLocationReceiver;
	private Location mLocation = null;
	
	private ForegroundTracker mForeground;
	
	// UI
	private Button mStateButton = null;
	private Button mSaveButton = null;
	
	@Override
	protected void onCreate(Bundle icicle) {
		super.onCreate(icicle);
		
		setContentView(R.layout.track_map);
		
		//ImageButton saveButton = (ImageButton) findViewById(R.id.track_save);
		//saveButton.setImageResource(android.R.drawable.ic_menu_save);
		
		if (mLocationReceiver == null)
			mLocationReceiver = new LocationReceiver();
		
		mStateButton = (Button) findViewById(R.id.track_record);
		mStateButton.setOnClickListener(new Button.OnClickListener() {
			public void onClick(View view) {
				Button button = (Button) view;
				
				if (!mServiceBound) {
					Log.e(TAG, "Trying to start/stop/pause/resume track, but service isn't connected. WTF");
					return;
				}
				
				try {
					if (!mService.isRecordingTrack()) {
						mService.startRecordingTrack();
						
						TrackMapActivity.this.updateToLatestTrack();
						mMap.invalidate();
						
						button.setText("Pause");
						mSaveButton.setVisibility(Button.VISIBLE);
					} else {
						if (mService.isTrackPaused()) {
							button.setText("Pause");
							mService.resumeTrack();
						} else {
							button.setText("Resume");
							mService.pauseTrack();
						}
					}
				} catch (RemoteException e) {
					Log.e(TAG, "Error talking to service");
					return;
				}
			}
		});
		
		mSaveButton = (Button) findViewById(R.id.track_save);
		mSaveButton.setOnClickListener(new Button.OnClickListener() {
			public void onClick(View view) {
				if (!mServiceBound) {
					Log.e(TAG, "Trying to stop/save a track without a service.");
					return;
				}
				
				Button button = (Button) view;
				
				try {
					if (!mService.isRecordingTrack()) {
						Log.w(TAG, "Trying to stop a track, but none recording");
						button.setVisibility(Button.INVISIBLE);
						return;
					}
					
					if (isTrackEmpty()) {
						showDialog(DIALOG_TRACK_EMPTY_ID);
						return;
					}
					
					showDialog(DIALOG_SAVE_ID);
				} catch (RemoteException e) {
					Log.e(TAG, "Error talking to service");
					return;
				}
			}
		});
				
		if (mMap == null) {
			mMap = (MapView) findViewById(R.id.track_map);
		}
		
		updateToLatestTrack();
		
		if (mLocationOverlay == null) {
			mLocationOverlay = new LocationOverlay();
		}
		
		mMap.setBuiltInZoomControls(true);
		//mMap.displayZoomControls(false);
		mMap.getOverlays().add(mLocationOverlay);
		
		mForeground = new ForegroundTracker(this);
	}
	
	private boolean isTrackEmpty() {
		GpsDbAdapter db = new GpsDbAdapter(this);
		db.open();
		
		boolean isEmpty = true;
		if (mServiceBound) {
			try {
				isEmpty = (db.getNumTrackPoints(mService.currentTrackId()) == 0);
			} catch (RemoteException e) {
				Log.e(TAG, "Error Communicationg with service: " + e);
			}
		}
		
		db.close();
		
		return isEmpty;
	}
	
	private void updateToLatestTrack() {
		long trackId = -1;
		if (mServiceBound) {
			try {
				trackId = mService.currentTrackId();
			} catch (RemoteException e) {
				Log.e(TAG, "Error communicating with service: " + e);
			}
		}
		
		if (mOverlay != null) {
			mMap.getOverlays().remove(mOverlay);
		}
		
		mOverlay = new TrackOverlay(trackId);
		mMap.getOverlays().add(mOverlay);
	}
	
	private void saveTrack() {
		if (!mServiceBound) {
			Log.w(TAG, "No service bound. How to save?");
			return;
		}
		
		long trackId = 0;
		try {
			trackId = mService.currentTrackId();
		} catch (RemoteException e) {
			Log.e(TAG, "Error getting current track: " + e);
			return;
		}
		
		Log.d(TAG, "Saving track to upload " + Long.toString(trackId));

        Intent i = new Intent(this, TrackSaveActivity.class);
        i.putExtra(TrackSaveActivity.TRACK_ID_EXTRA, trackId);
        startActivity(i);
		
        /*
		if(mServiceBound) {
			try {
				mService.addTrackToUploadQueue(trackId);
			} catch(RemoteException e) {
				Log.e(TAG, "Error adding track to upload queue. Service error.");
			}
		}
		*/
	}
	
	public boolean onCreateOptionsMenu(Menu menu) {
        super.onCreateOptionsMenu(menu);
        MenuItem locationItem = menu.add(0, MENU_LOCATION_ID, 0, R.string.map_menu_location);
        locationItem.setIcon(getResources().getDrawable(android.R.drawable.ic_menu_mylocation));
		
        MenuItem zoomToTrackItem = menu.add(Menu.NONE, MENU_ZOOM_TO_TRACK_ID, 0, R.string.map_menu_track_zoom);
        zoomToTrackItem.setIcon(getResources().getDrawable(android.R.drawable.ic_menu_zoom));
        
		return true;
	}
	
    public boolean onMenuItemSelected(int featureId, MenuItem item) {
    	switch(item.getItemId()) {
    	case MENU_LOCATION_ID:
    		if (mLocation != null) {
    			Log.d(TAG, "Animating to new point");
    			GeoPoint point = new GeoPoint((int) (mLocation.getLatitude() * 1000000), 
    										  (int) (mLocation.getLongitude() * 1000000));
    			mMap.getController().animateTo(point);
    		}
    		return true;
    	case MENU_ZOOM_TO_TRACK_ID:
    		if (mOverlay != null) {
    			GeoBounds bounds = mOverlay.getBounds();
    			if (!bounds.isEmpty()) {
    				mMap.getController().setCenter(bounds.getCenter());
    				mMap.getController().zoomToSpan(bounds.getLatSpan(), bounds.getLonSpan());
    			}
    		}
    		return true;
    	}
    	
    	return false;
    }
	
	protected Dialog onCreateDialog(int id) {
		Dialog dialog;
		switch (id) {
		case DIALOG_SAVE_ID: {
			AlertDialog.Builder builder = new AlertDialog.Builder(this);
			builder.setMessage("Are you sure you stop recording and upload this track?")
			       .setCancelable(false)
			       .setPositiveButton("Yes", new DialogInterface.OnClickListener() {
			           public void onClick(DialogInterface dialog, int id) {
			                saveTrack();
			           }
			       })
			       .setNegativeButton("No", new DialogInterface.OnClickListener() {
			           public void onClick(DialogInterface dialog, int id) {
			                dialog.cancel();
			           }
			       });
			dialog = builder.create();
			break;
		} case DIALOG_TRACK_EMPTY_ID: {
			AlertDialog.Builder builder = new AlertDialog.Builder(this);
			builder.setMessage("Sorry, you are not allowed to save this track yet: The track has no points in it.")
					.setTitle("Unable to save")
					.setIcon(android.R.drawable.ic_dialog_alert)
					.setCancelable(false)
					.setNegativeButton("Ok", new DialogInterface.OnClickListener() {
						public void onClick(DialogInterface dialog, int id) {
							dialog.cancel();
						}
					});
			dialog = builder.create();
			break;
		} default:
			dialog = null;
		}
		return dialog;
	}
	
	protected void onDestroy() {
		mMap.getOverlays().remove(mOverlay);
		mMap = null;
		
		mOverlay = null;
		mLocationOverlay = null;
		mLocationReceiver = null;
		
		super.onDestroy();
	}

	@Override
	protected void onPause() {
		super.onPause();
		
		Log.d(TAG, "TrackMapActivity pausing");
		
		mLocationOverlay.disableLocation();
		
		if (mLocationReceiver != null)
			unregisterReceiver(mLocationReceiver);
		
		if (mServiceBound) {
			unbindService(mServiceConn);
		}
		
		mForeground.background();
	}

	@Override
	protected void onResume() {
		super.onResume();
	
		Log.d(TAG, "TrackMapActivity resuming");
		
		if (mMap == null)
			mMap = (MapView) findViewById(R.id.track_map);
		
		mServiceBound = bindService(new Intent(this, GeoCamService.class), mServiceConn, Context.BIND_AUTO_CREATE);
		if (!mServiceBound) {
			Log.e(TAG, "GeoCamMobile::onResume - error binding to service");
	    }
		
		mForeground.foreground();

		IntentFilter filter = new IntentFilter(GeoCamMobile.LOCATION_CHANGED);
        this.registerReceiver(mLocationReceiver, filter);
		
		mLocationOverlay.enableLocation();
		
	}

	@Override
	protected boolean isRouteDisplayed() {
		return false;
	}
	
	@Override
	protected boolean isLocationDisplayed() {
		return true;
	}

	private void updateLocation(Location location) {
		if (!mServiceBound) {
			Log.w(TAG, "Service not bound!");
			return;
		}
		
		try {			
			if (!mService.isRecordingTrack())
				return;
			
			if (mService.isTrackPaused())
				return;
			
			mOverlay.addPoint((int) (location.getLatitude() * 1000000), 
						      (int) (location.getLongitude() * 1000000));
			mMap.invalidate();
			
			//Toast.makeText(this, "Added track point", Toast.LENGTH_SHORT).show();
		} catch (RemoteException e) {
			Log.e(TAG, "Caught exception from service: " + e);
		}
	}
	
    class LocationReceiver extends BroadcastReceiver {
		@Override
		public void onReceive(Context context, Intent intent) {
			//Log.d(TAG, "TrackMapActivity::LocationReceiver.onReceive");
			mLocation = (Location)intent.getParcelableExtra(GeoCamMobile.LOCATION_EXTRA);
			
			if (intent.getBooleanExtra(GeoCamMobile.LOCATION_TRACKED, false))
				TrackMapActivity.this.updateLocation((Location)intent.getParcelableExtra(GeoCamMobile.LOCATION_EXTRA));
		}
    }
}
