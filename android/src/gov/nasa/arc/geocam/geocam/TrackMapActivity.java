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
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;
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
	
	
	/*
	private static final class GeoBounds {
		private int mUpperLeftLat = -1;
		private int mUpperLeftLon = -1;
		private int mLowerRightLat = -1;
		private int mLowerRightLon = -1;
		
		public GeoBounds() { }
		
		public GeoBounds(GeoPoint center, int latSpan, int lonSpan) {
			setBounds(center, latSpan, lonSpan);
		}
		
		public void setBounds(GeoPoint center, int latSpan, int lonSpan) {
			mUpperLeftLat = center.getLatitudeE6() - (latSpan / 2);
			mUpperLeftLon = center.getLongitudeE6() - (lonSpan / 2);
			mLowerRightLat = center.getLatitudeE6() + (latSpan / 2);
			mLowerRightLon = center.getLongitudeE6() + (lonSpan / 2);			
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
	}
	*/
	
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
				
				currSegment.addPoint(new GeoPoint((int) (points.getFloat(latIndex) * 1000000),
									              (int) (points.getFloat(lonIndex) * 1000000)));
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
	}
	
	private class LocationOverlay extends Overlay implements SensorEventListener {		
		private SensorManager mSensorManager;
		
		private GeoPoint mCurrentLocation = null;
		private float mCurrentHeading = 0;
		
		private boolean mLocationEnabled = false;
		private boolean mOrientationEnabled = false;
		
		private BroadcastReceiver mLocationReceiver = new BroadcastReceiver() {
			@Override
			public void onReceive(Context context, Intent intent) {
				if (!intent.getAction().equals(GeoCamMobile.LOCATION_CHANGED))
					return;

				Log.d(TAG, "Got a new location from the service.");
				Location l = (Location) intent.getExtras().get(GeoCamMobile.LOCATION_EXTRA);
				updateLocation(l);
			}
		};
		
		public LocationOverlay() {
			mDrawable = TrackMapActivity.this.getResources().getDrawable(R.drawable.heading);
			mDrawableWidth = mDrawable.getIntrinsicWidth() - 7;
			mDrawableHeight = mDrawable.getIntrinsicHeight();
			
			mDrawable.setBounds(0, 0, mDrawableWidth, mDrawableHeight);
		}

		public void enableLocation() {
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
			mMap.invalidate();
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
			//mCurrentHeading = location.getBearing();
			Log.d(TAG, "heading: " + mCurrentHeading);
			mMap.invalidate();
		}

		// SensorEventListener Methods
		public void onSensorChanged(SensorEvent event) {
			if (event == null) return;
			
			mCurrentHeading = event.values[0];
			mMap.invalidate();
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
		Log.d(TAG, "TrackMapActivity pausing");
		
		mLocationOverlay.disableLocation();
		
		if (mLocationReceiver != null)
			unregisterReceiver(mLocationReceiver);
		
		if (mServiceBound) {
			unbindService(mServiceConn);
		}
		
		mForeground.background();
		
		super.onPause();
	}

	@Override
	protected void onResume() {
		super.onResume();
	
		Log.d(TAG, "TrackMapActivity resuming");
		
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
			Log.d(TAG, "TrackMapActivity::LocationReceiver.onReceive");
			if (intent.getBooleanExtra(GeoCamMobile.LOCATION_TRACKED, false))
				TrackMapActivity.this.updateLocation((Location)intent.getParcelableExtra(GeoCamMobile.LOCATION_EXTRA));
		}
    }
}
