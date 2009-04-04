package gov.nasa.arc.geocam;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.GregorianCalendar;

import android.app.Activity;
import android.app.Dialog;
import android.app.ProgressDialog;
import android.content.ContentUris;
import android.content.ContentValues;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.provider.MediaStore;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;


public class UploadPhotosActivity extends Activity implements HttpPostProgress {

	private static final String DEBUG_ID = "UploadPhotos";

	private final static int DIALOG_UPLOAD_PROGRESS = 1;
	
	private final static int MESSAGE_UPLOAD_PROGRESS = 1;
	private final static int MESSAGE_UPLOAD_COMPLETE = 2;
	private final static int MESSAGE_UPLOAD_ERROR 	 = 3;
	
	private ProgressDialog mUploadProgressDialog;
	private TextView mTextView;
	private String mOutputText;
	
	private int mCurrentUploadPhotoNum;
	private String mCurrentUploadPhotoName;
	
	private int mNumPhotosToUpload = 0;
	
	private Handler mHandler;
	
	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.upload_photos);

		mTextView = (TextView)findViewById(R.id.post_text);

		mOutputText = "";
		mTextView.setText(mOutputText);

		Button uploadButton = (Button)findViewById(R.id.upload_photos_upload_button);
		uploadButton.setOnClickListener(new View.OnClickListener() {
			public void onClick(View v) {
				uploadPhotos();
			}        	
		});

		mHandler = new Handler() {
			public void handleMessage(Message msg) {
				switch (msg.what) {
				case MESSAGE_UPLOAD_PROGRESS:
					updateUploadProgress(msg.arg1, msg.arg2, (String)msg.obj);
					break;

				case MESSAGE_UPLOAD_COMPLETE:
					dismissDialog(DIALOG_UPLOAD_PROGRESS);
					mOutputText = mOutputText.concat("\nSuccess!");
					mTextView.setText(mOutputText);
					break;

				case MESSAGE_UPLOAD_ERROR:
					dismissDialog(DIALOG_UPLOAD_PROGRESS);
					mOutputText = mOutputText.concat("\nError while uploading file " + (String)msg.obj);
					mTextView.setText(mOutputText);
					break;
				}	
			}
		};
		
		mNumPhotosToUpload = getNumPhotosToUpload();
	}

	private int getNumPhotosToUpload() {
		String[] projection = new String[] {
				MediaStore.Images.ImageColumns._ID,
				MediaStore.Images.ImageColumns.TITLE,
				MediaStore.Images.ImageColumns.DISPLAY_NAME,
				MediaStore.Images.ImageColumns.BUCKET_ID,
				MediaStore.Images.ImageColumns.BUCKET_DISPLAY_NAME,
				MediaStore.Images.ImageColumns.SIZE,
		};

		Cursor cur = managedQuery(GeoCamMobile.MEDIA_URI, projection, MediaStore.Images.ImageColumns.BUCKET_ID
				+ '=' + GeoCamMobile.GEOCAM_BUCKET_ID, null, null);
		cur.moveToFirst();
		Log.d(GeoCamMobile.DEBUG_ID, "Retrieving list of photos with bucket id: " + GeoCamMobile.GEOCAM_BUCKET_ID);
		int count = 0;
		while (cur.moveToNext()) {
			String id, title, name, bucket_id, bucket_name, size;
			id = cur.getString(cur.getColumnIndex(MediaStore.Images.ImageColumns._ID));
			title = cur.getString(cur.getColumnIndex(MediaStore.Images.ImageColumns.TITLE));
			name = cur.getString(cur.getColumnIndex(MediaStore.Images.ImageColumns.DISPLAY_NAME));
			bucket_id = cur.getString(cur.getColumnIndex(MediaStore.Images.ImageColumns.BUCKET_ID));
			bucket_name = cur.getString(cur.getColumnIndex(MediaStore.Images.ImageColumns.BUCKET_DISPLAY_NAME));
			size = String.valueOf(cur.getInt(cur.getColumnIndex(MediaStore.Images.ImageColumns.SIZE)));
			mOutputText = mOutputText.concat(id + " (" + bucket_id + " " + bucket_name + "):\t" + name + "(" + size + "bytes)\n");
			count++;
		}

		mOutputText = mOutputText.concat("\n" + String.valueOf(count) + " photos available for upload.\n");
		mTextView.setText(mOutputText);

		return count;
	}

	@Override
	protected Dialog onCreateDialog(int id) {
		switch(id) {
		case DIALOG_UPLOAD_PROGRESS:
			mUploadProgressDialog = new ProgressDialog(this);
			mUploadProgressDialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
			mUploadProgressDialog.setCancelable(true);
			mUploadProgressDialog.setIndeterminate(false);
			mUploadProgressDialog.setMax(100);
			return mUploadProgressDialog;
			
		default:
			break;
		}
		return null;
	}
	
	public void updateUploadProgress(int photoNum, int photoPct, String name) {
		mUploadProgressDialog.setProgress(photoPct);
		mUploadProgressDialog.setSecondaryProgress((int)((photoNum*100.0)/mNumPhotosToUpload));
		mUploadProgressDialog.setMessage("hi");
	}
	
	// Spawn background upload thread
	public void uploadPhotos() {
		showDialog(DIALOG_UPLOAD_PROGRESS);
		
		Thread t = new Thread() {
			public void run() {
				backgroundUpload();
			}
		};
		t.start();
	}
	
	// Query db for available images and call uploadImage() to post to remote server
	public void backgroundUpload() {
		String[] projection = new String[] {
				MediaStore.Images.ImageColumns._ID,
				MediaStore.Images.ImageColumns.DISPLAY_NAME,
				MediaStore.Images.ImageColumns.DATE_TAKEN,
				MediaStore.Images.ImageColumns.LATITUDE,
				MediaStore.Images.ImageColumns.LONGITUDE,
				MediaStore.Images.ImageColumns.DESCRIPTION,
				MediaStore.Images.ImageColumns.SIZE,
		};
		Cursor cur = managedQuery(GeoCamMobile.MEDIA_URI, projection, MediaStore.Images.ImageColumns.BUCKET_ID
				+ '=' + GeoCamMobile.GEOCAM_BUCKET_ID, null, null);
		cur.moveToFirst();

		mCurrentUploadPhotoNum = 1;
		while (cur.moveToNext()) {
			long id = cur.getLong(cur.getColumnIndex(MediaStore.Images.ImageColumns._ID));
			mCurrentUploadPhotoName = cur.getString(cur.getColumnIndex(MediaStore.Images.ImageColumns.DISPLAY_NAME));
			long dateTakenMillis = cur.getLong(cur.getColumnIndex(MediaStore.Images.ImageColumns.DATE_TAKEN));
			double latitude = cur.getDouble(cur.getColumnIndex(MediaStore.Images.ImageColumns.LATITUDE));
			double longitude = cur.getDouble(cur.getColumnIndex(MediaStore.Images.ImageColumns.LONGITUDE));
			String description = cur.getString(cur.getColumnIndex(MediaStore.Images.ImageColumns.DESCRIPTION));

			try {
				Thread.sleep(500);
			} 
			catch (InterruptedException e) {
				Log.e(DEBUG_ID, "InterruptedException: " + e);
			}

			// Upload image
//			if (mCurrentUploadPhotoNum == mNumPhotosToUpload) {
			if (true) {
				double[] angles = GeoCamMobile.rpyUnSerialize(description);
				boolean success = uploadImage(id, mCurrentUploadPhotoName, dateTakenMillis, latitude, longitude, angles);
				if (success) {
					ContentValues values = new ContentValues();
					values.put(MediaStore.Images.ImageColumns.BUCKET_ID, GeoCamMobile.GEOCAM_UPLOADED_BUCKET_ID);
					values.put(MediaStore.Images.ImageColumns.BUCKET_DISPLAY_NAME, GeoCamMobile.GEOCAM_UPLOADED_BUCKET_NAME);

					getContentResolver().update(Uri.withAppendedPath(GeoCamMobile.MEDIA_URI, String.valueOf(id)), values, null, null);
				}
				else {
					Message msg = Message.obtain(mHandler, MESSAGE_UPLOAD_ERROR, mCurrentUploadPhotoName);
					mHandler.sendMessage(msg);
					return;
				}
			}			
			mCurrentUploadPhotoNum++;
		}
		Message msg = Message.obtain(mHandler, MESSAGE_UPLOAD_COMPLETE);
		mHandler.sendMessage(msg);
	}
		
	public boolean uploadImage(long id, String name, long dateTakenMillis, double latitude, double longitude, double[] angles) {
		Log.i(DEBUG_ID, "Uploading image #" + String.valueOf(id));
		try {
			Uri imageUri = ContentUris.withAppendedId(GeoCamMobile.MEDIA_URI, id);

			Bitmap bitmap = MediaStore.Images.Media.getBitmap(getContentResolver(), imageUri);
			ByteArrayOutputStream bytes = new ByteArrayOutputStream();
			bitmap.compress(Bitmap.CompressFormat.JPEG, 90, bytes);
			ByteArrayInputStream stream = new ByteArrayInputStream(bytes.toByteArray());

			SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
			String cameraTime = df.format(new Date(dateTakenMillis));
			
			Log.i(DEBUG_ID, "\tReady to POST with cameraTime " + cameraTime);
			
			Map<String,String> vars = new HashMap<String,String>();
			vars.put("cameraTime", cameraTime);
			vars.put("latitude", String.valueOf(latitude));
			vars.put("longitude", String.valueOf(longitude));
			vars.put("roll", String.valueOf(angles[0]));
			vars.put("pitch", String.valueOf(angles[1]));
			vars.put("yaw", String.valueOf(angles[2]));

			HttpPost post = new HttpPost();
			String out = post.post(this, "https://pepe.arc.nasa.gov/geocam/13f350c721168522/upload/jeztek/9-d972/", true, vars, "photo", String.valueOf(id) + ".jpg", stream);
			Log.d(DEBUG_ID, out);

			bitmap.recycle();
			
			return true;
		} 
		catch (FileNotFoundException e) {
			Log.e(DEBUG_ID, "FileNotFoundException: " + e);
			return false;
		} 
		catch (IOException e) {
			Log.e(DEBUG_ID, "IOException: " + e);
			return false;
		}
	}

	public void httpPostProgressUpdate(int percentComplete) {
		Log.d(DEBUG_ID, "httpPostProgressUpdate: " + String.valueOf(percentComplete));
		Message msg = Message.obtain(mHandler, MESSAGE_UPLOAD_PROGRESS, mCurrentUploadPhotoNum, percentComplete, mCurrentUploadPhotoName);
		mHandler.sendMessage(msg);
	}
}
