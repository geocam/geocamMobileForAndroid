package gov.nasa.arc.geocam.geocam;

import java.util.ArrayList;
import java.util.List;

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
	
	private static final String TAG = "GeoCamDbAdapter";
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
	public static final String KEY_POINT_CREATED = "created";
	public static final String KEY_POINT_TRACK_ID = "track_id";
	
	// Waypoint table
	public static final String KEY_WAYPOINT_ROWID = KEY_ROWID;
	public static final String KEY_WAYPOINT_NAME = "name";
	public static final String KEY_WAYPOINT_CREATED = "created";
	public static final String KEY_WAYPOINT_POINT_ID = "point_id";
	
	// Track table
	public static final String KEY_TRACK_ROWID = KEY_ROWID;
	public static final String KEY_TRACK_NOTES = "notes";
	public static final String KEY_TRACK_STYLE = "style";
	public static final String KEY_TRACK_CREATED = "created";
	
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
					+ KEY_POINT_TRACK_ID + " integer default null, "
					+ KEY_POINT_CREATED + " text not null default CURRENT_TIMESTAMP);";
			db.execSQL(CREATE_POINTS);
			
			final String CREATE_WAYPOINTS =
				"create table " + TABLE_WAYPOINTS + " ("
					+ KEY_WAYPOINT_ROWID + " integer primary key autoincrement, "
					+ KEY_WAYPOINT_NAME + " text, "
					+ KEY_WAYPOINT_POINT_ID + " integer, "
					+ KEY_WAYPOINT_CREATED + " text not null default CURRENT_TIMESTAMP);";
			db.execSQL(CREATE_WAYPOINTS);
			
			final String CREATE_TRACKS =
				"create table " + TABLE_TRACKS + " ("
					+ KEY_TRACK_ROWID + " integer primary key autoincrement, "
					+ KEY_TRACK_NOTES + " text, "
					+ KEY_TRACK_STYLE + " text, "
					+ KEY_TRACK_CREATED + " text not null default CURRENT_TIMESTAMP);";
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
	
	public long addPoint(Location location) {
		return addPoint(location.getLatitude(), location.getLongitude());
	}
	
	public long addPoint(double latitude, double longitude) {
		ContentValues initialValues = new ContentValues();
		initialValues.put(KEY_POINT_LATITUDE, latitude);
		initialValues.put(KEY_POINT_LONGITUDE, longitude);
		return mDb.insert(TABLE_POINTS, null, initialValues);
	}
	
	public Cursor getPoints() {
		final String QUERY = 
			"select "
				+ KEY_POINT_ROWID + ", "
				+ KEY_POINT_LATITUDE + ", "
				+ KEY_POINT_LONGITUDE 
				+ " from " + TABLE_POINTS 
				+ " order by created asc;"; 
		return mDb.rawQuery(QUERY, null);
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
