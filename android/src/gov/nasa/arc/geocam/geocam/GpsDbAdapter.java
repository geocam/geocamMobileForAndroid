// __BEGIN_LICENSE__
// Copyright (C) 2008-2010 United States Government as represented by
// the Administrator of the National Aeronautics and Space Administration.
// All Rights Reserved.
// __END_LICENSE__

package gov.nasa.arc.geocam.geocam;

import java.io.UnsupportedEncodingException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.SQLException;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.location.Location;
import android.util.Log;

public class GpsDbAdapter {

	
	public static final String KEY_URI = "uri";
	public static final String KEY_UPLOADED = "is_uploaded";
	
	private static final String TAG = "GpsDbAdapter";
	private DatabaseHelper mDbHelper;
	private SQLiteDatabase mDb;
	
	private static final String DATABASE_NAME = "gps.db";
	private static final String TABLE_POINTS = "points";
	private static final String TABLE_WAYPOINTS = "waypoints";
	private static final String TABLE_TRACKS = "tracks";
	
	public static final String KEY_ROWID = "_id";
	
	// Point table
	public static final String KEY_POINT_ROWID = KEY_ROWID;
	public static final String KEY_POINT_LATITUDE = "latitude";
	public static final String KEY_POINT_LONGITUDE = "longitude";
	public static final String KEY_POINT_ALTITUDE = "altitude";
	public static final String KEY_POINT_ORIENTATION = "orientation";
	public static final String KEY_POINT_ACQUIRED = "acquired";
	public static final String KEY_POINT_PROVIDER = "provider";
	public static final String KEY_POINT_TRACK_ID = "track_id";
	public static final String KEY_POINT_TRACK_SEGMENT = "track_segment";
	
	// Waypoint table
	public static final String KEY_WAYPOINT_ROWID = KEY_ROWID;
	public static final String KEY_WAYPOINT_UID = "uid";
	public static final String KEY_WAYPOINT_NOTES = "notes";
	public static final String KEY_WAYPOINT_ICON = "icon";
	public static final String KEY_WAYPOINT_CREATED = "created";
	public static final String KEY_WAYPOINT_POINT_ID = "point_id";
	
	// Track table
	public static final String KEY_TRACK_ROWID = KEY_ROWID;
	public static final String KEY_TRACK_UID = "uid";
	public static final String KEY_TRACK_NOTES = "notes";
	public static final String KEY_TRACK_COLOR = "color";
	public static final String KEY_TRACK_ICON = "icon";
	public static final String KEY_TRACK_DASHED = "is_dashed";
	public static final String KEY_TRACK_STARTED = "start_date";
	public static final String KEY_TRACK_STOPPED = "stop_date";
	
	private static final int DATABASE_VERSION = 2;

	private final Context mCtx;
	
	private static class DatabaseHelper extends SQLiteOpenHelper {

		public DatabaseHelper(Context context) {
			super(context, DATABASE_NAME, null, DATABASE_VERSION);
		}

		// All times are in milliseconds from the epoch (UTC)
		// as returned by System.currentTimeMillis();
		
		@Override
		public void onCreate(SQLiteDatabase db) {
			final String CREATE_POINTS =
				"create table " + TABLE_POINTS + " ("
					+ KEY_POINT_ROWID + " integer primary key autoincrement, "
					+ KEY_POINT_LATITUDE + " real not null, "
					+ KEY_POINT_LONGITUDE + " real not null, "
					+ KEY_POINT_ALTITUDE + " real not null, "
					+ KEY_POINT_ORIENTATION + " real, " // not used yet
					+ KEY_POINT_ACQUIRED + " integer not null, "
					+ KEY_POINT_PROVIDER + " text not null, "
					+ KEY_POINT_TRACK_ID + " integer default null, "
					+ KEY_POINT_TRACK_SEGMENT + " integer default 0)";
			db.execSQL(CREATE_POINTS);
			
			final String CREATE_WAYPOINTS =
				"create table " + TABLE_WAYPOINTS + " ("
					+ KEY_WAYPOINT_ROWID + " integer primary key autoincrement, "
					+ KEY_WAYPOINT_UID + " text not null, "
					+ KEY_WAYPOINT_NOTES + " text, "
					+ KEY_WAYPOINT_ICON + " icon, "
					+ KEY_WAYPOINT_POINT_ID + " integer not null, "
					+ KEY_WAYPOINT_CREATED + " integer not null)";
			db.execSQL(CREATE_WAYPOINTS);
			
			final String CREATE_TRACKS =
				"create table " + TABLE_TRACKS + " ("
					+ KEY_TRACK_ROWID + " integer primary key autoincrement, "
					+ KEY_TRACK_UID + " text not null, "
					+ KEY_TRACK_NOTES + " text default \"\", "
					+ KEY_TRACK_ICON + " text not null default \"track\", "
					+ KEY_TRACK_COLOR + " integer, "
					+ KEY_TRACK_DASHED + " integer not null default 0, "
					+ KEY_TRACK_STARTED + " intger not null, "
					+ KEY_TRACK_STOPPED + " integer)";
			db.execSQL(CREATE_TRACKS);
		}

		@Override
		public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
			Log.w(TAG, "Upgrading database from version " + oldVersion + " to " 
					+ newVersion + ", which will destroy all old data");
			db.execSQL("DROP TABLE IF EXISTS " + TABLE_POINTS);
			db.execSQL("DROP TABLE IF EXISTS " + TABLE_WAYPOINTS);
			db.execSQL("DROP TABLE IF EXISTS " + TABLE_TRACKS);
			onCreate(db);
		}
	}
	
	public static String generateId(String salt) {
		/* IDs are the SHA-1 sum of the concatenation of the following:
		 * - the string representation of a newly generated UUID (type 2)
		 * - any other data given (could be the ID, or other device-specific data)
		 */
		
		// Generate a UUID
		String uuid = UUID.randomUUID().toString();
		
		// Create a hasher
		MessageDigest digest;
		
		try {
			digest = MessageDigest.getInstance("SHA1");
		} catch (NoSuchAlgorithmException e) {
			Log.w(TAG, "SHA-1 algorithm not available. Trying MD5");
			try {
				digest = MessageDigest.getInstance("MD5");
			} catch (NoSuchAlgorithmException e1) {
				Log.e(TAG, "MD5 algorithm not available either. Returning UUID instead. Get a real JRE, please.");
				return uuid;  // UUID will have to do since this implementation is a piece of crap.
			}
		}
		
		// Add the goods
		try {
			digest.update(uuid.getBytes("UTF-8"));
			
			if (salt != null)
				digest.update(salt.getBytes("UTF-8"));
		} catch (UnsupportedEncodingException e) {
			Log.e(TAG, "No UTF-8 encoding. FTS. Returning UUID.");
			return uuid;
		}
		
		// Get the hash digest
		byte[] digestBytes = digest.digest();
		
		// Make human-readable
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < digest.getDigestLength(); i++) {
			sb.append(Integer.toHexString(digestBytes[i] & 0xFF));
		}
		
		// Return the goodness
		return sb.toString();
	}
	
	public GpxWriter trackToGpx(long trackId) {
		final String TRACK_QUERY = 
			"select "
				+ KEY_TRACK_UID + ", "
				+ KEY_TRACK_NOTES + ", "
				+ KEY_TRACK_ICON + ", "
				+ KEY_TRACK_COLOR + ", "
				+ KEY_TRACK_DASHED
			+ " from " + TABLE_TRACKS
			+ " where " 
				+ KEY_TRACK_ROWID + "=" + Long.toString(trackId); 
		
		GpxWriter writer = new GpxWriter();
		
		Log.d(TAG, "Saving track " + Long.toString(trackId));
		
		Cursor track = mDb.rawQuery(TRACK_QUERY, null);
		
		// No such track
		if (track.getCount() <= 0) {
			return null;
		}
		
		track.moveToFirst();
		
		//int uidIndex = track.getColumnIndex(KEY_TRACK_UID);
		int notesIndex = track.getColumnIndex(KEY_TRACK_NOTES);
		int iconIndex = track.getColumnIndex(KEY_TRACK_ICON);
		int colorIndex = track.getColumnIndex(KEY_TRACK_COLOR);
		int dashedIndex = track.getColumnIndex(KEY_TRACK_DASHED);
		
		Cursor trackPoints = this.getTrackPoints(trackId);
		
		writer.startTrack(track.getString(notesIndex));
		
		if (trackPoints.getCount() > 0) {	
			
			int latIndex = trackPoints.getColumnIndex(KEY_POINT_LATITUDE);
			int lonIndex = trackPoints.getColumnIndex(KEY_POINT_LONGITUDE);
			int altIndex = trackPoints.getColumnIndex(KEY_POINT_ALTITUDE);
			int acqIndex = trackPoints.getColumnIndex(KEY_POINT_ACQUIRED);
			int trkSegIndex = trackPoints.getColumnIndex(KEY_POINT_TRACK_SEGMENT);

			long currentSegment = -1;
			
			trackPoints.moveToFirst();
			do {
				if (currentSegment != trackPoints.getLong(trkSegIndex)) {
					if (currentSegment != -1)
						writer.endSegment();
					writer.startSegment();
					currentSegment = trackPoints.getLong(trkSegIndex);
				}
				
				writer.append(trackPoints.getDouble(latIndex),
								 trackPoints.getDouble(lonIndex),
								 trackPoints.getDouble(altIndex),
								 trackPoints.getLong(acqIndex));
			} while (trackPoints.moveToNext());
			
			writer.endSegment();
		}
		
		trackPoints.close();
		
		writer.startTrackExtensions();
		//writer.appendUID(track.getString(uidIndex));
		writer.appendIcon(track.getString(iconIndex));
		writer.appendColor(track.getInt(colorIndex));
		writer.appendSolidDashed((track.getInt(dashedIndex) == 1));
		writer.endTrackExtensions();
		
		writer.endTrack();
		
		track.close();
		
		return writer;
	}
	
	public GpsDbAdapter(Context ctx) {
		this.mCtx = ctx;
	}
	
	public GpsDbAdapter open() throws SQLException {
		mDbHelper = new DatabaseHelper(mCtx);
		mDb = mDbHelper.getWritableDatabase();
		return this;
	}
	
	public void close() {
		mDbHelper.close();
	}
	
	private ContentValues populatePoint(Location location) {
		ContentValues initialValues = new ContentValues();
		initialValues.put(KEY_POINT_PROVIDER, location.getProvider());
		initialValues.put(KEY_POINT_LATITUDE, location.getLatitude());
		initialValues.put(KEY_POINT_LONGITUDE, location.getLongitude());
		initialValues.put(KEY_POINT_ALTITUDE, location.getAltitude());
		initialValues.put(KEY_POINT_ACQUIRED, location.getTime());
		return initialValues;
	}
	
	public long addPoint(Location location) {
		//Log.d(TAG, "Logging location");
		ContentValues initialValues = populatePoint(location);
		return mDb.insert(TABLE_POINTS, null, initialValues);
	}
	
	public long addPointToTrack(long trackId, long segment, Location location) {
		//Log.d(TAG, "Logging location to track " + Long.toString(trackId) + " : " + Long.toString(segment));
		
		ContentValues initialValues = populatePoint(location);
		initialValues.put(KEY_POINT_TRACK_ID, trackId);
		initialValues.put(KEY_POINT_TRACK_SEGMENT, segment);
		return mDb.insert(TABLE_POINTS, null, initialValues);		
	}
	
	public long startTrack() {
		Log.d(TAG, "Starting new track");
		
		ContentValues initialValues = new ContentValues();
		initialValues.put(KEY_TRACK_UID, generateId(null));
		initialValues.put(KEY_TRACK_ICON, "track");
		initialValues.put(KEY_TRACK_COLOR, 0xffff0000L); // ARGB (android color -- red)
		initialValues.put(KEY_TRACK_STARTED, System.currentTimeMillis());
		return mDb.insert(TABLE_TRACKS, null, initialValues);
	}
	
	public boolean stopTrack(long trackId) {
		Log.d(TAG, "Stopping track " + Long.toString(trackId));
		ContentValues newValues = new ContentValues();
		newValues.put(KEY_TRACK_STOPPED, System.currentTimeMillis());
		
		String[] whereArgs = new String[1];
		whereArgs[0] = Long.toString(trackId);
		
		return (mDb.update(TABLE_TRACKS, newValues, KEY_TRACK_ROWID+"=?", whereArgs) > 0);
	}
	
	public String getTrackUuid(long trackId) {
		final String QUERY =
			"select "
				+ KEY_TRACK_UID
			+ " from " + TABLE_TRACKS
			+ " where " + KEY_TRACK_ROWID + "=" + Long.toString(trackId);
		
		String uid = null;
		
		Cursor c = mDb.rawQuery(QUERY, null);
		if (c.getCount() > 0 && c.moveToFirst()) {
			uid = c.getString(c.getColumnIndex(KEY_TRACK_UID));
		}
		
		c.close();
		
		return uid;
	}
	
	public long getLatestTrackId() {
		final String QUERY =
			"select " 
				+ KEY_TRACK_ROWID
				+ " from " + TABLE_TRACKS
				+ " order by " + KEY_TRACK_STARTED + " desc"
				+ " limit 1";
		
		long trackId = -1;
		
		Cursor c = mDb.rawQuery(QUERY, null);
		if (c.getCount() > 0 && c.moveToFirst()) {
			trackId = c.getLong(c.getColumnIndex(KEY_TRACK_ROWID));
		}
		c.close();
		
		return trackId;
	}
	
	public boolean updateTrackNotes(long trackId, String notes) {
		ContentValues newValues = new ContentValues();
		newValues.put(KEY_TRACK_NOTES, notes);
		
		String[] whereArgs = new String[1];
		whereArgs[0] = Long.toString(trackId);
		
		return (mDb.update(TABLE_TRACKS, newValues, KEY_TRACK_ROWID+"=?", whereArgs) > 0);		
	}
	
	public boolean updateTrackStyle(long trackId, boolean dashed, int color) {
		ContentValues newValues = new ContentValues();
		newValues.put(KEY_TRACK_DASHED, dashed);
		newValues.put(KEY_TRACK_COLOR, color);
		
		String[] whereArgs = new String[1];
		whereArgs[0] = Long.toString(trackId);
		
		return (mDb.update(TABLE_TRACKS, newValues, KEY_TRACK_ROWID+"=?", whereArgs) > 0);				
	}
	
	public Cursor getTrackPoints(long trackId) {
		final String QUERY = 
			"select "
				+ KEY_POINT_ROWID + ", "
				+ KEY_POINT_LATITUDE + ", "
				+ KEY_POINT_LONGITUDE + ", "
				+ KEY_POINT_ALTITUDE + ", "
				+ KEY_POINT_ORIENTATION + ", "
				+ KEY_POINT_ACQUIRED + ", "
				+ KEY_POINT_TRACK_SEGMENT
				+ " from " + TABLE_POINTS
				+ " where " + KEY_POINT_TRACK_ID + "=" + Long.toString(trackId)
				+ " order by " + KEY_POINT_ACQUIRED + " asc";
		return mDb.rawQuery(QUERY, null);
	}
	
	public long getNumTrackPoints(long trackId) {
		long numPoints;
		
		Cursor c = getTrackPoints(trackId);
		numPoints = c.getCount();
		c.close();
		
		return numPoints;
	}
	
	public Cursor getPoints() {
		final String QUERY = 
			"select "
				+ KEY_POINT_ROWID + ", "
				+ KEY_POINT_LATITUDE + ", "
				+ KEY_POINT_LONGITUDE 
				+ " from " + TABLE_POINTS 
				+ " order by " + KEY_POINT_ACQUIRED + " asc"; 
		return mDb.rawQuery(QUERY, null);
	}
	
	public List<Location> getBoundingLocations(long time) {
		return getBoundingLocations(time, 1);
	}
	
	public List<Location> getBoundingLocations(long time, int bracket) {
		final String SELECT_QUERY =
			"select "
				+ KEY_POINT_LATITUDE + ", "
				+ KEY_POINT_LONGITUDE + ", "
				+ KEY_POINT_ALTITUDE + ", "
				+ KEY_POINT_ACQUIRED + ", "
				+ KEY_POINT_PROVIDER
				+ " from " + TABLE_POINTS;
		
		ArrayList<Location> points = new ArrayList<Location>((bracket * 2) + 1);
		int latIdx, lonIdx, altIdx, acqIdx, provIdx;
		
		String previousQuery =
			SELECT_QUERY
			+ " where " + KEY_POINT_ACQUIRED + " <= " + Long.toString(time)
			+ " order by " + KEY_POINT_ACQUIRED + " desc limit " + Integer.toString(bracket);
		Cursor previous = mDb.rawQuery(previousQuery, null);
		if (previous.getCount() > 0) {
			latIdx = previous.getColumnIndex(KEY_POINT_LATITUDE);
			lonIdx = previous.getColumnIndex(KEY_POINT_LONGITUDE);
			altIdx = previous.getColumnIndex(KEY_POINT_ALTITUDE);
			acqIdx = previous.getColumnIndex(KEY_POINT_ACQUIRED);
			provIdx = previous.getColumnIndex(KEY_POINT_PROVIDER);
			
			previous.moveToFirst();
			do {
				Location l = new Location(previous.getString(provIdx));
				l.setTime(previous.getLong(acqIdx));
				l.setLatitude(previous.getDouble(latIdx));
				l.setLongitude(previous.getDouble(lonIdx));
				l.setAltitude(previous.getDouble(altIdx));
				points.add(l);
			} while(previous.moveToNext());
		}
		previous.close();
		
		String nextQuery =
			SELECT_QUERY
			+ " where " + KEY_POINT_ACQUIRED + " > " + Long.toString(time)
			+ " order by " + KEY_POINT_ACQUIRED + " asc limit " + Integer.toString(bracket);
		Cursor next = mDb.rawQuery(nextQuery, null);
		if (next.getCount() > 0) {
			latIdx = next.getColumnIndex(KEY_POINT_LATITUDE);
			lonIdx = next.getColumnIndex(KEY_POINT_LONGITUDE);
			altIdx = next.getColumnIndex(KEY_POINT_ALTITUDE);
			acqIdx = next.getColumnIndex(KEY_POINT_ACQUIRED);
			provIdx = next.getColumnIndex(KEY_POINT_PROVIDER);
			
			next.moveToFirst();
			do {
				Location l = new Location(next.getString(provIdx));
				l.setTime(next.getLong(acqIdx));
				l.setLatitude(next.getDouble(latIdx));
				l.setLongitude(next.getDouble(lonIdx));
				l.setAltitude(next.getDouble(altIdx));
				points.add(l);
			} while(next.moveToNext());
		}
		next.close();
		
		return points;
	}
}
