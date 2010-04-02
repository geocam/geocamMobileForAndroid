package gov.nasa.arc.geocam.geocam;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.SQLException;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Log;

public class GeoCamDbAdapter {

	public static final String KEY_ROWID = "_id";
	public static final String KEY_URI = "uri";
	public static final String KEY_DOWNSAMPLE = "downsample";
	public static final String KEY_UPLOADED = "is_uploaded";
	
	private static final String TAG = "GeoCamDbAdapter";
	private DatabaseHelper mDbHelper;
	private SQLiteDatabase mDb;
	
	private static final String DATABASE_NAME = "geocam";
	private static final String DATABASE_TABLE = "upload_queue";
	private static final int DATABASE_VERSION = 2;

	private static final String DATABASE_CREATE =
		"create table " + DATABASE_TABLE + " (_id integer primary key autoincrement, "
			+ "uri text not null, downsample integer not null, is_uploaded integer not null);";
	
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
	
	public UploadQueueRow addToQueue(String uri, int downsample) {
		ContentValues initialValues = new ContentValues();
		initialValues.put(KEY_URI, uri);
		initialValues.put(KEY_DOWNSAMPLE, downsample);
		initialValues.put(KEY_UPLOADED, 0);
		long rowId = mDb.insert(DATABASE_TABLE, null, initialValues);
		return new UploadQueueRow(rowId, uri, downsample);
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
		Cursor cursor = null;

		// Return lowest rowId prioritized by downsample factor
		for (int factor : GeoCamMobile.PHOTO_DOWNSAMPLE_FACTORS) {
			cursor = mDb.rawQuery("SELECT " + KEY_ROWID + ", " + KEY_URI + ", " + KEY_DOWNSAMPLE + ", " + KEY_UPLOADED + 
				" FROM " + DATABASE_TABLE + " WHERE " + KEY_ROWID + "=(SELECT MIN(" + KEY_ROWID + ") FROM " +
				DATABASE_TABLE + " WHERE " + KEY_UPLOADED + "=0 AND " + KEY_DOWNSAMPLE + "=" + factor + ");", null);
			if (cursor.getCount() > 0) 
				break;
		}
		
		UploadQueueRow result = null;
		if (cursor != null && cursor.moveToFirst()) {
			long rowId = cursor.getLong(0);
			String url = cursor.getString(1);
			int downsample = cursor.getInt(2);
			Log.d(GeoCamMobile.DEBUG_ID, "GeoCamDbAdapter::getNextFromQueue - " + url + " [" + downsample + "]");
			result = new UploadQueueRow(rowId, url, downsample);
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
		public String uri;
		public int downsample;

		public UploadQueueRow(long rowId, String uri, int downsample) {
			this.rowId = rowId;
			this.uri = uri;
			this.downsample = downsample;
		}
		
		public UploadQueueRow(String uri, int downsample) {
			this.uri = uri;
			this.downsample = downsample;
		}
		
		public String toString() {
			return String.valueOf(rowId);
		}
	}
}
