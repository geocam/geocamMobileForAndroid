// __BEGIN_LICENSE__
// Copyright (C) 2008-2010 United States Government as represented by
// the Administrator of the National Aeronautics and Space Administration.
// All Rights Reserved.
// __END_LICENSE__

package gov.nasa.arc.geocam.geocam;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.SQLException;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Log;

public class GeoCamDbAdapter {

	// Generic queue columns
	public static final String KEY_ROWID = "_id";
	public static final String KEY_TYPE = "type";
	public static final String KEY_FID = "fid";
	public static final String KEY_URI = "uri";
	public static final String KEY_CREATED = "created";
	public static final String KEY_UPLOADED = "is_uploaded";
	public static final String KEY_PRIORITY = "priority";
	
	// Image-specific columns
	public static final String KEY_DOWNSAMPLE = "downsample";
	
	// Queue types
	public static final String TYPE_IMAGE = "image";
	public static final String TYPE_TRACK = "track";
	public static final String TYPE_WAYPOINT = "waypoint"; // Future
	
	private static final String TAG = "GeoCamDbAdapter";
	private DatabaseHelper mDbHelper;
	private SQLiteDatabase mDb;
	
	private static final String DATABASE_NAME = "geocam";
	private static final String DATABASE_TABLE = "upload_queue";
	private static final int DATABASE_VERSION = 5;

	private static final String DATABASE_CREATE =
		"create table " + DATABASE_TABLE + " ("
			+ KEY_ROWID + " integer primary key autoincrement, "
			+ KEY_TYPE + " text not null, "
			+ KEY_FID + " integer, "
			+ KEY_URI + " text, "
			+ KEY_DOWNSAMPLE + " integer, "
			+ KEY_PRIORITY + " integer not null, "
			+ KEY_CREATED + " integer not null, "
			+ KEY_UPLOADED + " integer not null"
			+ ")";
	
	private final Context mCtx;
	
	private static class DatabaseHelper extends SQLiteOpenHelper {

		public DatabaseHelper(Context context) {
			super(context, DATABASE_NAME, null, DATABASE_VERSION);
		}

		@Override
		public void onCreate(SQLiteDatabase db) {
			db.execSQL(DATABASE_CREATE);			
		}

		@Override
		public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
			Log.w(TAG, "Upgrading database from version " + oldVersion + " to " 
					+ newVersion + ", which will destroy all old data");
			db.execSQL("DROP TABLE IF EXISTS " + DATABASE_TABLE);
			onCreate(db);
		}
	}
	
	public GeoCamDbAdapter(Context ctx) {
		this.mCtx = ctx;
	}
	
	public GeoCamDbAdapter open() throws SQLException {
		mDbHelper = new DatabaseHelper(mCtx);
		mDb = mDbHelper.getWritableDatabase();
		return this;
	}
	
	public void close() {
		mDbHelper.close();
	}
	
	public ContentValues seedNewRow() {
		ContentValues initialValues = new ContentValues();
		initialValues.put(KEY_UPLOADED, 0);
		initialValues.put(KEY_CREATED, System.currentTimeMillis());
		
		return initialValues;
	}
	
	// Add a track
	public UploadQueueRow addTrackToQueue(long trackId) {
		ContentValues initialValues = seedNewRow();
		initialValues.put(KEY_TYPE, TYPE_TRACK);
		initialValues.put(KEY_PRIORITY, GeoCamMobile.TRACK_PRIORITY);
		initialValues.put(KEY_FID, trackId);
		
		Log.d(TAG, "Inserting new track into upload queue: " + initialValues.toString());
		
		long rowId = mDb.insert(DATABASE_TABLE, null, initialValues);
		return new TrackRow(rowId, trackId);
	}
	
	public UploadQueueRow addToQueue(String uri, int downsample) {
		ContentValues initialValues = seedNewRow();
		initialValues.put(KEY_TYPE, TYPE_IMAGE);
		
		if (GeoCamMobile.PHOTO_PRIORITIES.containsKey(downsample)) {
			initialValues.put(KEY_PRIORITY, GeoCamMobile.PHOTO_PRIORITIES.get(downsample));
		} else {
			initialValues.put(KEY_PRIORITY, 0);
		}
		
		initialValues.put(KEY_URI, uri);
		initialValues.put(KEY_DOWNSAMPLE, downsample);
		
		Log.d(TAG, "Inserting into upload queue: " + initialValues.toString());
		
		long rowId = mDb.insert(DATABASE_TABLE, null, initialValues);
		return new ImageRow(rowId, uri, downsample);
	}
	
	public void clearQueue() {
		mDb.delete(DATABASE_TABLE, null, null);
	}
	
	public boolean setAsUploaded(UploadQueueRow row) {
		ContentValues newValues = new ContentValues();
		newValues.put(KEY_UPLOADED, 1);
		return mDb.update(DATABASE_TABLE, newValues, KEY_ROWID + "=" + row.rowId, null) == 1;
	}

	public UploadQueueRow getRow(long rowId) {
		Cursor cursor = mDb.query(DATABASE_TABLE, new String[] {KEY_URI, KEY_DOWNSAMPLE}, KEY_ROWID + "=" + rowId, 
				null, null, null, null);
		String uri = null;
		int downsample = 0;

		UploadQueueRow result = null;
		if (cursor != null && cursor.moveToFirst()) {
			uri = cursor.getString(0);
			downsample = cursor.getInt(1);
			result = new UploadQueueRow(rowId, uri, downsample);
		}
		cursor.close();
		return result;
	}
	
	public UploadQueueRow getNextFromQueue() {
		final String QUERY = 
			"SELECT "
				+ KEY_ROWID + ", "
				+ KEY_TYPE + ", "
				+ KEY_FID + ", "
				+ KEY_URI + ", "
				+ KEY_UPLOADED + ", "
				+ KEY_PRIORITY + ", "
				+ KEY_DOWNSAMPLE
			+ " FROM " + DATABASE_TABLE
			+ " WHERE "
				+ KEY_UPLOADED + "=0"
			+ " ORDER BY "
				+ KEY_PRIORITY + " DESC, "
				+ KEY_CREATED + " ASC"
			+ " LIMIT 1";
				
		// Return first row with the highest priority that hasn't been uploaded yet
		Cursor cursor = mDb.rawQuery(QUERY, null);
		
		UploadQueueRow result = null;
		if (cursor != null && cursor.moveToFirst()) {

			
			String type = cursor.getString(cursor.getColumnIndex(KEY_TYPE));
			if (type.equals(TYPE_IMAGE)) {
				long rowId = cursor.getLong(cursor.getColumnIndex(KEY_ROWID));
				String url = cursor.getString(cursor.getColumnIndex(KEY_URI));
				int downsample = cursor.getInt(cursor.getColumnIndex(KEY_DOWNSAMPLE));
				
				Log.d(TAG, "GeoCamDbAdapter::getNextFromQueue - " + url + " [" + downsample + "]");
				
				result = new ImageRow(rowId, url, downsample);
			} else if (type.equals(TYPE_TRACK)) {
				//return new TrackRow(rowId, )
				long rowId = cursor.getLong(cursor.getColumnIndex(KEY_ROWID));
				long trackId = cursor.getLong(cursor.getColumnIndex(KEY_FID));
				
				Log.d(TAG, "GeoCamDbAdapter::getNextFromQueue - track" + trackId);

				result = new TrackRow(rowId, trackId);
			}
		
		}
		if (cursor != null) 
			cursor.close();
		return result;
	}
	
	/*
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
	*/
	
	public int size() {
		Cursor cursor =  mDb.query(DATABASE_TABLE, new String[] {KEY_ROWID}, 
				KEY_UPLOADED + "=0", null, null, null, null);
		int count = cursor.getCount();
		cursor.close();
		return count;
	}
	
	public class UploadQueueRow {
		public long rowId = 0;
		public long priority = 0;
		public String type = null;

		public UploadQueueRow(long rowId, String type, long priority) {
			this.rowId = rowId;
			this.type = type;
			this.priority = priority;
		}
		
		public UploadQueueRow(long rowId) {
			this.rowId = rowId;
		}
		
		public UploadQueueRow(String type) {
			this.type = type;
		}
	}
	
	public class ImageRow extends UploadQueueRow {
		public static final String CONTENT_TYPE = "image/jpeg";
		
		public String uri;
		public int downsample;

		public ImageRow(long rowId, String uri, int downsample) {
			super(rowId, TYPE_IMAGE, 0);
			this.uri = uri;
			this.downsample = downsample;
		}
		
		public ImageRow(String uri, int downsample) {
			super(TYPE_IMAGE);
			this.uri = uri;
			this.downsample = downsample;
		}
		
		public String toString() {
			return String.valueOf(rowId);
		}
	}
	
	public class TrackRow extends UploadQueueRow {
		public static final String CONTENT_TYPE = "text/xml";
		
		public long trackId = 0;
		
		public TrackRow(long rowId, long trackId) {
			super(rowId, TYPE_TRACK, 0);
			this.trackId = trackId;
		}
		
		public TrackRow(long trackId) {
			super(TYPE_TRACK);
			this.trackId = trackId;
		}
	}
	
}
