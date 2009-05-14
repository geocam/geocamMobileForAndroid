package gov.nasa.arc.geocam;

import java.io.FileNotFoundException;
import java.io.IOException;

import org.json.JSONException;
import org.json.JSONObject;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ContentValues;
import android.content.DialogInterface;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Log;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ImageView.ScaleType;

public class CameraPreviewActivity extends Activity {

	private static final int DIALOG_DELETE_PHOTO = 1;

	private Uri mImageUri;
	private JSONObject mImageData;
	private String mImageNote;

	private Bitmap mBitmap;
	
	// upload queue data structure
	private JsonQueueFileStore<String> mQueue;
	
	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		
		// Window and view properties
		requestWindowFeature(Window.FEATURE_NO_TITLE);
		getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
		setContentView(R.layout.camera_preview);

		// Load bitmap from intent data and display in imageview
		mImageUri = getIntent().getData();
		try {
			mImageData = new JSONObject(getIntent().getExtras().getString("data"));
		} catch (JSONException e1) {
			Log.d(GeoCamMobile.DEBUG_ID, "Error unserializing JSON data from intent");
			mImageData = new JSONObject();
		}

		try {
			mBitmap = MediaStore.Images.Media.getBitmap(getContentResolver(), mImageUri);
			ImageView imageView = (ImageView)findViewById(R.id.camera_preview_imageview);
			imageView.setAdjustViewBounds(true);
			imageView.setScaleType(ScaleType.CENTER_INSIDE);
			imageView.setImageBitmap(mBitmap);

		} catch (FileNotFoundException e) {
			Log.d(GeoCamMobile.DEBUG_ID, "Error loading bitmap in CameraPreviewActivity");
		} catch (IOException e) {
			Log.d(GeoCamMobile.DEBUG_ID, "Error loading bitmap in CameraPreviewActivity");
		}

		// Buttons		
		final ImageButton deleteButton = (ImageButton)findViewById(R.id.camera_preview_delete_button);
		deleteButton.setImageDrawable(getResources().getDrawable(R.drawable.delete_64x64));
		deleteButton.setOnClickListener(new View.OnClickListener() {
			public void onClick(View v) {
				CameraPreviewActivity.this.showDialog(DIALOG_DELETE_PHOTO);
			}			
		});
		
		final ImageButton saveButton = (ImageButton)findViewById(R.id.camera_preview_save_button);
		saveButton.setImageDrawable(getResources().getDrawable(R.drawable.save_64x64));
		saveButton.setOnClickListener(new View.OnClickListener() {
			public void onClick(View v) {
            	mImageNote = ((EditText)findViewById(R.id.camera_preview_edittext)).getText().toString();
            	Log.d(GeoCamMobile.DEBUG_ID, "Setting image note to: " + mImageNote);
            	saveWithAnnotation();
			}			
		});

	}
	
	@Override
	public void onDestroy() {
		super.onDestroy();
		if (mBitmap != null) {
			mBitmap.recycle();
		}
	}
	
	@Override
	public void onPause() {
		super.onPause();
		mQueue.saveToFile();
	}

	@Override
	public void onResume() {
		super.onResume();		
		mQueue = new JsonQueueFileStore<String>(this, GeoCamMobile.UPLOAD_QUEUE_FILENAME);
		mQueue.loadFromFile();
	}

	// Capture hardware keyboard show/hide
	// Purpose is to prevent activity destroy/create on keyboard change
	// to avoid image corruption during save
	@Override
	public void onConfigurationChanged(Configuration newConfig) {
		super.onConfigurationChanged(newConfig);
	}

	@Override
	protected Dialog onCreateDialog(int id) {
		switch(id) {
		case DIALOG_DELETE_PHOTO:
			return new AlertDialog.Builder(this)
			.setTitle(R.string.camera_delete_dialog_title)
			.setPositiveButton(R.string.camera_dialog_ok, new DialogInterface.OnClickListener() {
				public void onClick(DialogInterface dialog, int whichButton) {
	            	deletePhoto();
	            	CameraPreviewActivity.this.finish();
				}
			})
			.setNegativeButton(R.string.camera_dialog_cancel, new DialogInterface.OnClickListener() {
				public void onClick(DialogInterface dialog, int whichButton) {
				}
			})
			.create();
			
			default:
				break;
		}
		return null;
	}

	private void saveWithAnnotation() {
		try {
			mImageData.put("note", mImageNote);
			Log.d(GeoCamMobile.DEBUG_ID, "Saving image with data: " + mImageData.toString());
		}
		catch (JSONException e) {
			Log.d(GeoCamMobile.DEBUG_ID, "Error while adding annotation to image");
		}

		ContentValues values = new ContentValues();
		values.put(MediaStore.Images.Media.DESCRIPTION, mImageData.toString());
		getContentResolver().update(mImageUri, values, null, null);
		Log.d(GeoCamMobile.DEBUG_ID, "Updating " + mImageUri.toString() + " with values " + values.toString());

		// Add image URI to upload queue
		mQueue.add(mImageUri.toString());
		
		this.finish();
	}
	
	private void deletePhoto() {
		Log.d(GeoCamMobile.DEBUG_ID, "Deleting photo with Uri: " + mImageUri.toString());		
		getContentResolver().delete(mImageUri, null, null);
	}
}

