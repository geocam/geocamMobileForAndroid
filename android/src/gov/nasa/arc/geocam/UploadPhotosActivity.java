package gov.nasa.arc.geocam;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import org.json.JSONException;
import org.json.JSONObject;

import android.app.Activity;
import android.app.Dialog;
import android.app.ProgressDialog;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.preference.PreferenceManager;
import android.provider.MediaStore;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;


public class UploadPhotosActivity extends Activity {

	private final static int DIALOG_UPLOAD_PROGRESS = 1;
	
	private final static int MESSAGE_UPLOAD_PROGRESS = 1;
	private final static int MESSAGE_UPLOAD_COMPLETE = 2;
	private final static int MESSAGE_UPLOAD_ERROR 	 = 3;
	
	private Button mUploadButton;	
	private TextView mTextView;
	private String mOutputText;
	
	private ProgressDialog mUploadProgressDialog;

	private JsonQueueFileStore<String> mQueue;
	
	private int mNumPhotosToUpload = 0;
	private String mUploadErrorMessage;
	
	private Handler mHandler;
	
	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.upload_photos);

		mTextView = (TextView)findViewById(R.id.post_text);

		mOutputText = "";
		mTextView.setText(mOutputText);

		mUploadButton = (Button)findViewById(R.id.upload_photos_upload_button);
		mUploadButton.setOnClickListener(new View.OnClickListener() {
			public void onClick(View v) {
				uploadPhotos();
			}        	
		});

		mHandler = new Handler() {
			public void handleMessage(Message msg) {
				switch (msg.what) {
				case MESSAGE_UPLOAD_PROGRESS:
					updateUploadProgress(msg.arg1, msg.arg2);
					break;

				case MESSAGE_UPLOAD_COMPLETE:
					dismissDialog(DIALOG_UPLOAD_PROGRESS);
					mOutputText = "\nUpload successful!\n";
					mTextView.setText(mOutputText);
					mNumPhotosToUpload = getNumPhotosToUpload();
					if (mNumPhotosToUpload <= 0) {
						mUploadButton.setEnabled(false);
					}
					break;

				case MESSAGE_UPLOAD_ERROR:
					dismissDialog(DIALOG_UPLOAD_PROGRESS);
					mOutputText = mOutputText.concat("\nError while uploading image " + String.valueOf(msg.arg1) 
							+ " of " + String.valueOf(msg.arg2) + ": " + (String)msg.obj);
					mTextView.setText(mOutputText);
					break;
				}	
			}
		};		
	}

	@Override
	public void onPause() {
		super.onPause();
		synchronized(this) {
			mQueue.saveToFile();
		}
	}
	
	@Override
	public void onResume() {
		super.onResume();
		synchronized(this) {
			if (mQueue == null)
				mQueue = new JsonQueueFileStore<String>(this, GeoCamMobile.UPLOAD_QUEUE_FILENAME);
			mQueue.loadFromFile();

			for (String s : mQueue) {
				Log.d(GeoCamMobile.DEBUG_ID, "Entry: " + s);
			}
		}
		
		mNumPhotosToUpload = getNumPhotosToUpload();
		if (mNumPhotosToUpload <= 0) {
			mUploadButton.setEnabled(false);
		}
	}
	
	private synchronized int getNumPhotosToUpload() {
		String[] projection = new String[] {
				MediaStore.Images.ImageColumns._ID,
				MediaStore.Images.ImageColumns.TITLE,
				MediaStore.Images.ImageColumns.DISPLAY_NAME,
				MediaStore.Images.ImageColumns.BUCKET_ID,
				MediaStore.Images.ImageColumns.BUCKET_DISPLAY_NAME,
				MediaStore.Images.ImageColumns.SIZE,
		};

		mOutputText = "";
		for (String s : mQueue) {
			Uri uri = Uri.parse(s);
			Cursor cur = managedQuery(uri, projection, null, null, null);
			cur.moveToFirst();

			String id, title, name, bucket_id, bucket_name, size;
			id = cur.getString(cur.getColumnIndex(MediaStore.Images.ImageColumns._ID));
			title = cur.getString(cur.getColumnIndex(MediaStore.Images.ImageColumns.TITLE));
			name = cur.getString(cur.getColumnIndex(MediaStore.Images.ImageColumns.DISPLAY_NAME));
			bucket_id = cur.getString(cur.getColumnIndex(MediaStore.Images.ImageColumns.BUCKET_ID));
			bucket_name = cur.getString(cur.getColumnIndex(MediaStore.Images.ImageColumns.BUCKET_DISPLAY_NAME));
			size = String.valueOf(cur.getInt(cur.getColumnIndex(MediaStore.Images.ImageColumns.SIZE)));
			mOutputText = mOutputText.concat(id + ".jpg (" + size + " bytes)\n");
		}

		mOutputText = mOutputText.concat("\n" + String.valueOf(mQueue.size()) + " photo(s) available for upload.\n");
		mTextView.setText(mOutputText);

		return mQueue.size();
	}

	@Override
	protected Dialog onCreateDialog(int id) {
		switch(id) {
		case DIALOG_UPLOAD_PROGRESS:
			mUploadProgressDialog = new ProgressDialog(this);
			mUploadProgressDialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
			mUploadProgressDialog.setCancelable(true);
			mUploadProgressDialog.setIndeterminate(false);
			mUploadProgressDialog.setMax(mNumPhotosToUpload);
			return mUploadProgressDialog;
			
		default:
			break;
		}
		return null;
	}
	
	public void updateUploadProgress(int photoNum, int numPhotos) {
		mUploadProgressDialog.setProgress((int)photoNum);
		mUploadProgressDialog.setMessage(String.valueOf(numPhotos) + " total photos");
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
				MediaStore.Images.ImageColumns.DATE_TAKEN,
				MediaStore.Images.ImageColumns.LATITUDE,
				MediaStore.Images.ImageColumns.LONGITUDE,
				MediaStore.Images.ImageColumns.DESCRIPTION,
				MediaStore.Images.ImageColumns.SIZE,
		};

		int photoNum = 1;
		int queueSize;
		synchronized(this) {
			queueSize = mQueue.size();
		}
		while(queueSize > 0) {
			String uriStr;
			synchronized(this) {
				uriStr = mQueue.element();
			}
			Uri uri = Uri.parse(uriStr);
			Cursor cur = managedQuery(uri, projection, null, null, null);
			cur.moveToFirst();

			long id = cur.getLong(cur.getColumnIndex(MediaStore.Images.ImageColumns._ID));
			long dateTakenMillis = cur.getLong(cur.getColumnIndex(MediaStore.Images.ImageColumns.DATE_TAKEN));
			double latitude = cur.getDouble(cur.getColumnIndex(MediaStore.Images.ImageColumns.LATITUDE));
			double longitude = cur.getDouble(cur.getColumnIndex(MediaStore.Images.ImageColumns.LONGITUDE));
			String description = cur.getString(cur.getColumnIndex(MediaStore.Images.ImageColumns.DESCRIPTION));

			double[] angles = new double[3];
			String note;
			try {
				JSONObject imageData = new JSONObject(description);
				angles = GeoCamMobile.rpyUnSerialize(imageData.getString("rpy"));
				note = imageData.getString("note");
			}
			catch (JSONException e) {
				angles[0] = angles[1] = angles[2] = 0.0;
				note = "";
			}
			boolean success = uploadImage(uri, id, dateTakenMillis, latitude, longitude, angles, note);
			if (success) {
				synchronized(this) {
					mQueue.remove();
					mQueue.saveToFile();
				}
				Message msg = Message.obtain(mHandler, MESSAGE_UPLOAD_PROGRESS, photoNum, mNumPhotosToUpload);
				mHandler.sendMessage(msg);
			}
			else {
				Message msg = Message.obtain(mHandler, MESSAGE_UPLOAD_ERROR, photoNum, mNumPhotosToUpload, mUploadErrorMessage);
				mHandler.sendMessage(msg);
				return;
			}
			photoNum++;
			synchronized(this) {
				queueSize = mQueue.size();
			}
		}
		Message msg = Message.obtain(mHandler, MESSAGE_UPLOAD_COMPLETE);
		mHandler.sendMessage(msg);
	}
		
	public boolean uploadImage(Uri uri, long id, long dateTakenMillis, double latitude, double longitude, double[] angles, String note) {
        SharedPreferences settings = PreferenceManager.getDefaultSharedPreferences(this);
        String serverUrl = settings.getString(GeoCamMobile.SETTINGS_SERVER_URL_KEY, GeoCamMobile.SETTINGS_SERVER_URL_DEFAULT);
        String serverUsername = settings.getString(GeoCamMobile.SETTINGS_SERVER_USERNAME_KEY, GeoCamMobile.SETTINGS_SERVER_USERNAME_DEFAULT);
        //String serverInbox = settings.getString(GeoCamMobile.SETTINGS_SERVER_INBOX_KEY, GeoCamMobile.SETTINGS_SERVER_INBOX_DEFAULT);
        
		Log.i(GeoCamMobile.DEBUG_ID, "Uploading image #" + String.valueOf(id));
		try {
			Bitmap bitmap = MediaStore.Images.Media.getBitmap(getContentResolver(), uri);
			ByteArrayOutputStream bytes = new ByteArrayOutputStream();
			bitmap.compress(Bitmap.CompressFormat.JPEG, 90, bytes);
			ByteArrayInputStream stream = new ByteArrayInputStream(bytes.toByteArray());

			SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
			String cameraTime = df.format(new Date(dateTakenMillis));
			
			Log.i(GeoCamMobile.DEBUG_ID, "\tReady to POST with cameraTime " + cameraTime);
			
			Map<String,String> vars = new HashMap<String,String>();
			vars.put("cameraTime", cameraTime);
			vars.put("latitude", String.valueOf(latitude));
			vars.put("longitude", String.valueOf(longitude));
			vars.put("roll", String.valueOf(angles[0]));
			vars.put("pitch", String.valueOf(angles[1]));
			vars.put("yaw", String.valueOf(angles[2]));
			vars.put("notes", note);

			HttpPost post = new HttpPost();
			String postUrl = serverUrl + "/upload/" + serverUsername + "/";
			Log.d(GeoCamMobile.DEBUG_ID, "Posting to URL " + postUrl);
			String out = post.post(postUrl, true, vars, "photo", String.valueOf(id) + ".jpg", stream);
//			String out = post.post(this, serverUrl + "/upload/" + serverUsername + "/" + serverInbox + "/", true, vars, "photo", String.valueOf(id) + ".jpg", stream);
			Log.d(GeoCamMobile.DEBUG_ID, "POST response: " + out);

			bitmap.recycle();
			
			return true;
		} 
		catch (FileNotFoundException e) {
			Log.e(GeoCamMobile.DEBUG_ID, "FileNotFoundException: " + e);
			mUploadErrorMessage = "Invalid URL";
			return false;
		} 
		catch (IOException e) {
			Log.e(GeoCamMobile.DEBUG_ID, "IOException: " + e);
			mUploadErrorMessage = "Unable to connect to server";
			return false;
		}
		catch (NullPointerException e) {
			Log.e(GeoCamMobile.DEBUG_ID, "NullPointerException: " + e);
			mUploadErrorMessage = "Invalid photo";
			return false;
		}
	}
}
