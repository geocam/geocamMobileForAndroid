package gov.nasa.arc.geocam.geocam;

import java.util.LinkedList;
import java.util.ListIterator;

import android.content.Context;
import android.database.Cursor;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Point;
import android.os.Bundle;

import com.google.android.maps.GeoPoint;
import com.google.android.maps.MapActivity;
import com.google.android.maps.MapView;
import com.google.android.maps.Overlay;
import com.google.android.maps.Projection;

public class TrackMapActivity extends MapActivity {

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
	
	protected static class PolyLineOverlay extends Overlay {
		private Paint mPaint;
		protected LinkedList<GeoPoint> mPoints;
		
		private Point mPoint = new Point();
		private Point mPrevPoint = new Point();
		private GeoBounds mGeoBounds = new GeoBounds();
		
		public void draw(Canvas canvas, MapView mapView, boolean shadow) {
			if (shadow == true) return;
			
			if (mPoints.size() == 0) return;
			
			// Speed-up accessors .. See Dev Guide
			Paint paint = mPaint;
			GeoBounds geoBounds = mGeoBounds;
			Point point = mPoint;
			Point prevPoint = mPrevPoint;
			
			Projection projection = mapView.getProjection();
			
			// Set new bounds
			geoBounds.setBounds(mapView.getMapCenter(), mapView.getLatitudeSpan(), mapView.getLongitudeSpan());
			
			boolean firstPoint = true;
			ListIterator<GeoPoint> iterator = mPoints.listIterator();
			while (iterator.hasNext()) {
				GeoPoint geoPoint = iterator.next();
				//if (!geoBounds.contains(geoPoint))
				//	continue;
				
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
		public TrackOverlay(Context ctx) {
			super();
			
			GpsDbAdapter pointsDb = new GpsDbAdapter(ctx);
			pointsDb.open();
			Cursor points = pointsDb.getPoints();
			
			int latIndex = points.getColumnIndex(GpsDbAdapter.KEY_POINT_LATITUDE);
			int lonIndex = points.getColumnIndex(GpsDbAdapter.KEY_POINT_LONGITUDE);
			
			points.moveToFirst();
			do {
				mPoints.add(new GeoPoint((int) (points.getFloat(latIndex) * 1000000),
										 (int) (points.getFloat(lonIndex) * 1000000)));
			} while(points.moveToNext());
		}
	}
	
	TrackOverlay mOverlay = null;
	MapView mMap = null;
	
	@Override
	protected void onCreate(Bundle icicle) {
		super.onCreate(icicle);
		
		setContentView(R.layout.track_map);
		
		if (mOverlay == null) {
			mOverlay = new TrackOverlay(this);
		}
		
		if (mMap == null) {
			mMap = (MapView) findViewById(R.id.track_map);
		}
		
		mMap.setBuiltInZoomControls(true);
		mMap.getOverlays().add(mOverlay);
	}
	
	protected void onDestroy() {
		mMap.getOverlays().remove(mOverlay);
		mMap = null;
		
		mOverlay = null;
		
		super.onDestroy();
	}

	@Override
	protected boolean isRouteDisplayed() {
		return false;
	}

}
