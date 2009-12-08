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
	public static final String KEY_UPLOADED = "is_uploaded";
	
	private static final String TAG = "GeoCamDbAdapter";
	private DatabaseHelper mDbHelper;
	private SQLiteDatabase mDb;
	
	private static final String DATABASE_NAME = "geocam";
	private static final String DATABASE_TABLE = "upload_queue";
	private static final int DATABASE_VERSION = 1;

	private static final String DATABASE_CREATE =
		"create table " + DATABASE_TABLE + " (_id integer primary key autoincrement, "
			+ "uri text not null, is_uploaded integer not null);";
	
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
			db.execSQL("DROP TABLE IF EXISTS " + DATABASE_NAME);
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
	
	public long addToQueue(String uri) {
		ContentValues initialValues = new ContentValues();
		initialValues.put(KEY_URI, uri);
		initialValues.put(KEY_UPLOADED, 0);
		return mDb.insert(DATABASE_TABLE, null, initialValues);
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
	
	public Cursor getQueue() {
		return mDb.query(DATABASE_TABLE, new String[] {KEY_ROWID, KEY_URI, KEY_UPLOADED}, 
				KEY_UPLOADED + "=0", null, null, null, null);
	}
	 
	public int size() {
		return this.getQueue().getCount();
	}
}
