package gov.nasa.arc.geocam.geocam;

import java.util.LinkedList;
import java.util.ListIterator;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.database.Cursor;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Point;
import android.location.Location;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.Toast;

import com.google.android.maps.MyLocationOverlay;
import com.google.android.maps.GeoPoint;
import com.google.android.maps.MapActivity;
import com.google.android.maps.MapView;
import com.google.android.maps.Overlay;
import com.google.android.maps.Projection;

public class TrackMapActivity extends MapActivity {
	private static final String TAG = "TrackMapActivity";
	
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
		//private GeoBounds mGeoBounds = new GeoBounds();
		
		public void draw(Canvas canvas, MapView mapView, boolean shadow) {
			if (shadow == true) return;
			
			if (mPoints.size() == 0) return;
			
			// Speed-up accessors .. See Dev Guide
			Paint paint = mPaint;
			//GeoBounds geoBounds = mGeoBounds;
			Point point = mPoint;
			Point prevPoint = mPrevPoint;
			
			Projection projection = mapView.getProjection();
			
			// Set new bounds
			//geoBounds.setBounds(mapView.getMapCenter(), mapView.getLatitudeSpan(), mapView.getLongitudeSpan());
			
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
	}
	
	public static class TrackOverlay extends PolyLineOverlay {
		GpsDbAdapter mDb;
		
		public TrackOverlay(Context ctx) {
			super();
			
			mDb = new GpsDbAdapter(ctx);
			refresh();
		}
		
		public void refresh() {
			mPoints.clear();
			
			mDb.open();
			
			long trackId = mDb.getLatestTrackId();
			if (trackId < 0) {
				mDb.close();
				return;
			}
			
			Cursor points = mDb.getTrackPoints(trackId);
			
			Log.d(TAG, "displaying track " + trackId + " with " + points.getCount() + " points");
			
			if (points.getCount() == 0) {
				points.close();
				mDb.close();
				return;
			}
			
			int latIndex = points.getColumnIndex(GpsDbAdapter.KEY_POINT_LATITUDE);
			int lonIndex = points.getColumnIndex(GpsDbAdapter.KEY_POINT_LONGITUDE);
			
			points.moveToFirst();
			do {
				mPoints.add(new GeoPoint((int) (points.getFloat(latIndex) * 1000000),
										 (int) (points.getFloat(lonIndex) * 1000000)));
			} while(points.moveToNext());
			
			points.close();
			mDb.close();
		}
		
		public void addPoint(int lat, int lon) {
			mPoints.add(new GeoPoint(lat, lon));
		}
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
	MyLocationOverlay mLocationOverlay = null;
	
	// Receiver
	private LocationReceiver mLocationReceiver;
	
	// UI
	private Button mStateButton = null;
	private Button mSaveButton = null;
	
	@Override
	protected void onCreate(Bundle icicle) {
		super.onCreate(icicle);
		
		setContentView(R.layout.track_map);
		
		//ImageButton saveButton = (ImageButton) findViewById(R.id.track_save);
		
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
						
						mOverlay.refresh();
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
					
					long trackId = mService.currentTrackId();
					mService.stopRecordingTrack();
					
					saveTrack(trackId);
				} catch (RemoteException e) {
					Log.e(TAG, "Error talking to service");
					return;
				}
				
				button.setVisibility(Button.INVISIBLE);
				mStateButton.setText("Start");
			}
		});
		
		if (mOverlay == null) {
			mOverlay = new TrackOverlay(this);
		}
		
		if (mMap == null) {
			mMap = (MapView) findViewById(R.id.track_map);
		}
		
		if (mLocationOverlay == null) {
			mLocationOverlay = new MyLocationOverlay(this, mMap);
		}
		
		mMap.setBuiltInZoomControls(true);
		//mMap.displayZoomControls(false);
		mMap.getOverlays().add(mLocationOverlay);
		mMap.getOverlays().add(mOverlay);
	}
	
	private void saveTrack(long trackId) {
		Log.d(TAG, "Saving track to upload " + Long.toString(trackId));

		if(mServiceBound) {
			try {
				mService.addTrackToUploadQueue(trackId);
			} catch(RemoteException e) {
				Log.e(TAG, "Error adding track to upload queue. Service error.");
			}
		}
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
		
		mLocationOverlay.disableCompass();
		//mLocationOverlay.disableMyLocation();
		
		if (mLocationReceiver != null)
			unregisterReceiver(mLocationReceiver);
		
		if (mServiceBound)
			unbindService(mServiceConn);
		
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

		IntentFilter filter = new IntentFilter(GeoCamMobile.LOCATION_CHANGED);
        this.registerReceiver(mLocationReceiver, filter);
		
		//mLocationOverlay.enableMyLocation();
		mLocationOverlay.enableCompass();
		
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
			
			Toast.makeText(this, "Added track point", Toast.LENGTH_SHORT).show();
		} catch (RemoteException e) {
			Log.e(TAG, "Caught exception from service: " + e);
		}
	}
	
    class LocationReceiver extends BroadcastReceiver {
		@Override
		public void onReceive(Context context, Intent intent) {
			Log.d(TAG, "TrackMapActivity::LocationReceiver.onReceive");
			TrackMapActivity.this.updateLocation((Location)intent.getParcelableExtra(GeoCamMobile.LOCATION_EXTRA));
		}
    }
}
