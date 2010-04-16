package gov.nasa.arc.geocam.geocam;

import java.io.UnsupportedEncodingException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.TimeZone;
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
	public static final String KEY_POINT_ORIENTATION = "orientation";
	public static final String KEY_POINT_ACQUIRED = "acquired";
	public static final String KEY_POINT_PROVIDER = "provider";
	public static final String KEY_POINT_TRACK_ID = "track_id";
	public static final String KEY_POINT_TRACK_SEGMENT = "track_segment";
	
	// Waypoint table
	public static final String KEY_WAYPOINT_ROWID = KEY_ROWID;
	public static final String KEY_WAYPOINT_NOTES = "notes";
	public static final String KEY_WAYPOINT_ICON = "icon";
	public static final String KEY_WAYPOINT_CREATED = "created";
	public static final String KEY_WAYPOINT_POINT_ID = "point_id";
	
	// Track table
	public static final String KEY_TRACK_ROWID = KEY_ROWID;
	public static final String KEY_TRACK_UID = "uid";
	public static final String KEY_TRACK_NOTES = "notes";
	public static final String KEY_TRACK_COLOR = "color";
	public static final String KEY_TRACK_DASHED = "is_dashed";
	public static final String KEY_TRACK_STARTED = "start_date";
	public static final String KEY_TRACK_STOPPED = "stop_date";
	
	// Conversion from unix-timestamp with fractional seconds to a date/time format sqlite3
	// knows about.
	private static final String STRFTIME_DATE = "strftime('%Y-%m-%dT%H:%M:%f', ?, 'unixepoch')";
	
	private static final int DATABASE_VERSION = 1;

	private final Context mCtx;
	
	private static class DatabaseHelper extends SQLiteOpenHelper {

		public DatabaseHelper(Context context) {
			super(context, DATABASE_NAME, null, DATABASE_VERSION);
		}

		@Override
		public void onCreate(SQLiteDatabase db) {
			final String CREATE_POINTS =
				"create table " + TABLE_POINTS + " ("
					+ KEY_POINT_ROWID + " integer primary key autoincrement, "
					+ KEY_POINT_LATITUDE + " real not null, "
					+ KEY_POINT_LONGITUDE + " real not null, "
					+ KEY_POINT_ORIENTATION + " real, " // not used yet
					+ KEY_POINT_ACQUIRED + " integer not null, "
					+ KEY_POINT_PROVIDER + " text not null, "
					+ KEY_POINT_TRACK_ID + " integer default null, "
					+ KEY_POINT_TRACK_SEGMENT + " integer default 0)"; 
					
			db.execSQL(CREATE_POINTS);
			
			final String CREATE_WAYPOINTS =
				"create table " + TABLE_WAYPOINTS + " ("
					+ KEY_WAYPOINT_ROWID + " integer primary key autoincrement, "
					+ KEY_WAYPOINT_NOTES + " text, "
					+ KEY_WAYPOINT_ICON + " icon, "
					+ KEY_WAYPOINT_POINT_ID + " integer, "
					+ KEY_WAYPOINT_CREATED + " text not null default CURRENT_TIMESTAMP)";
			db.execSQL(CREATE_WAYPOINTS);
			
			final String CREATE_TRACKS =
				"create table " + TABLE_TRACKS + " ("
					+ KEY_TRACK_ROWID + " integer primary key autoincrement, "
					+ KEY_TRACK_UID + " text not null, "
					+ KEY_TRACK_NOTES + " text, "
					+ KEY_TRACK_COLOR + " color, "
					+ KEY_TRACK_DASHED + " int not null default 0, "
					+ KEY_TRACK_STARTED + " text not null default CURRENT_TIMESTAMP, "
					+ KEY_TRACK_STOPPED + " text)";
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
	
	// Convert a system-specified milliseconds timestamp to
	// it's string representation of fractional seconds for
	// sqlite3 crap
	private static String timestampString(long time) {
		StringBuilder sb = new StringBuilder();
		sb.append(time);
		sb.insert(sb.length() - 3, '.');
		return sb.toString();
	}
	
	private static final SimpleDateFormat SQLITE_DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.S");
	static {
		SQLITE_DATE_FORMAT.setTimeZone(TimeZone.getTimeZone("GMT"));
	}
	
	// Convert a system-given millisecond timestamp to
	// a string that I can dump into sqlite. (UTC)
	public static String dateToSqlite(long time) {
		return SQLITE_DATE_FORMAT.format(new Date(time));
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
		initialValues.put(KEY_POINT_ACQUIRED, location.getTime());
		return initialValues;
	}
	
	public long addPoint(Location location) {
		ContentValues initialValues = populatePoint(location);
		return mDb.insert(TABLE_POINTS, null, initialValues);
	}
	
	public long addPointToTrack(long trackId, Location location) {
		ContentValues initialValues = populatePoint(location);
		initialValues.put(KEY_POINT_TRACK_ID, trackId);
		return mDb.insert(TABLE_POINTS, null, initialValues);		
	}
	
	public long startTrack() {
		ContentValues initialValues = new ContentValues();
		initialValues.put(KEY_TRACK_UID, generateId(null));
		return mDb.insert(TABLE_TRACKS, null, initialValues);
	}
	
	public boolean stopTrack(long trackId) {
		ContentValues newValues = new ContentValues();
		return true;
	}
	
	public boolean updateTrackNotes(long trackId, String notes) {
		ContentValues newValues = new ContentValues();
		newValues.put(KEY_TRACK_NOTES, notes);
		
		String[] whereArgs = new String[1];
		whereArgs[0] = Long.toString(trackId);
		
		return (mDb.update(TABLE_TRACKS, newValues, KEY_TRACK_ROWID+"=?", whereArgs) > 0);		
	}
	
	/*public Cursor getTracks() {
		final String QUERY =
			"select "
				+ KEY_TRACK_ROWID + ", "
				+ KEY_TRACK_UID + ", "
				+ KEY_TRACK_NOTES + ", ";
	}*/
	
	public Cursor getTrackPoints(long trackId) {
		final String QUERY = 
			"select "
				+ KEY_POINT_ROWID + ", "
				+ KEY_POINT_LATITUDE + ", "
				+ KEY_POINT_LONGITUDE + ", "
				+ KEY_POINT_ORIENTATION
				+ " from " + TABLE_POINTS
				+ " where " + KEY_POINT_TRACK_ID + "=" + Long.toString(trackId)
				+ " order by " + KEY_POINT_ACQUIRED + " asc;";
		return mDb.rawQuery(QUERY, null);
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
				+ KEY_POINT_ACQUIRED + ", "
				+ KEY_POINT_PROVIDER
				+ " from " + TABLE_POINTS;
		
		ArrayList<Location> points = new ArrayList<Location>((bracket * 2) + 1);
		int latIdx, lonIdx, acqIdx, provIdx;
		
		String previousQuery =
			SELECT_QUERY
			+ " where " + KEY_POINT_ACQUIRED + " <= " + Long.toString(time)
			+ " order by " + KEY_POINT_ACQUIRED + " desc limit " + Integer.toString(bracket);
		Cursor previous = mDb.rawQuery(previousQuery, null);
		if (previous.getCount() > 0) {
			latIdx = previous.getColumnIndex(KEY_POINT_LATITUDE);
			lonIdx = previous.getColumnIndex(KEY_POINT_LONGITUDE);
			acqIdx = previous.getColumnIndex(KEY_POINT_ACQUIRED);
			provIdx = previous.getColumnIndex(KEY_POINT_PROVIDER);
			
			previous.moveToFirst();
			do {
				Location l = new Location(previous.getString(provIdx));
				l.setTime(previous.getLong(acqIdx));
				l.setLatitude(previous.getDouble(latIdx));
				l.setLongitude(previous.getDouble(lonIdx));
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
			acqIdx = next.getColumnIndex(KEY_POINT_ACQUIRED);
			provIdx = next.getColumnIndex(KEY_POINT_PROVIDER);
			
			next.moveToFirst();
			do {
				Location l = new Location(next.getString(provIdx));
				l.setTime(next.getLong(acqIdx));
				l.setLatitude(next.getDouble(latIdx));
				l.setLongitude(next.getDouble(lonIdx));
				points.add(l);
			} while(next.moveToNext());
		}
		next.close();
		
		return points;
	}
	
	/*
	public long addToQueue(String uri) {
		ContentValues initialValues = new ContentValues();
		initialValues.put(KEY_URI, uri);
		initialValues.put(KEY_UPLOADED, 0);
		return mDb.insert(DATABASE_TABLE, null, initialValues);
	}
	
	public void clearQueue() {
		mDb.delete(DATABASE_TABLE, null, null);
	}
	
	public boolean setAsUploaded(long rowId) {
		ContentValues newValues = new ContentValues();
		newValues.put(KEY_UPLOADED, 1);
		return mDb.update(DATABASE_TABLE, newValues, KEY_ROWID + "=" + rowId, null) == 1;
	}

	public String getUri(long rowId) {
		Cursor cursor = mDb.query(DATABASE_TABLE, new String[] {KEY_URI}, KEY_ROWID + "=" + rowId, 
				null, null, null, null);
		String uri = null;

		if (cursor != null && cursor.moveToFirst()) {
			uri = cursor.getString(0);
		}
		cursor.close();
		return uri;
	}
	
	public long getNextFromQueue() {
		Cursor cursor = mDb.rawQuery("SELECT " + KEY_ROWID + ", " + KEY_URI + ", " + KEY_UPLOADED + 
				" FROM " + DATABASE_TABLE + " WHERE " + KEY_ROWID + "=(SELECT MIN(" + KEY_ROWID + ") FROM " +
				DATABASE_TABLE + " WHERE " + KEY_UPLOADED + "=0);", null);
		long rowId = -1;

		if (cursor != null && cursor.moveToFirst()) {
			rowId = cursor.getLong(0);
		}
		cursor.close();
		return rowId;
	}
	
	public List<String> getQueueRowIds() {
    	List<String> queue = new ArrayList<String>();
		Cursor cursor =  mDb.query(DATABASE_TABLE, new String[] {KEY_ROWID},//, KEY_URI, KEY_UPLOADED}, 
				KEY_UPLOADED + "=0", null, null, null, null);
    	if (cursor.getCount() > 0) {
    		cursor.moveToFirst();
    		do {
    			queue.add(cursor.getString(0));
    		}
    		while (cursor.moveToNext());
    	}
    	cursor.close();
    	return queue;
	}
	 
	public int size() {
		Cursor cursor =  mDb.query(DATABASE_TABLE, new String[] {KEY_ROWID}, 
				KEY_UPLOADED + "=0", null, null, null, null);
		int count = cursor.getCount();
		cursor.close();
		return count;
	}
	*/
}
