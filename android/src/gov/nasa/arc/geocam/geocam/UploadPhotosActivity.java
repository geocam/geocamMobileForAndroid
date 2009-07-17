package gov.nasa.arc.geocam.geocam;

import java.util.List;

import android.app.Activity;
import android.app.ListActivity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.database.Cursor;
import android.database.CursorIndexOutOfBoundsException;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.RemoteException;
import android.provider.MediaStore;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;
import android.widget.SimpleCursorAdapter;
import android.widget.TextView;


public class UploadPhotosActivity extends Activity { //ListActivity
	
	private SimpleCursorAdapter mAdapter;

	private TextView mStatusTextView;
		
	private Thread mUpdateViewThread;
	private Handler mHandler;
	
	// Variables for upload service
	private IGeoCamService mService;
	private boolean mServiceBound = false;

	private ServiceConnection mServiceConn = new ServiceConnection() {

		public void onServiceConnected(ComponentName name, IBinder service) {
			Log.d(GeoCamMobile.DEBUG_ID, "GeoCamMobile - UploadPhotosActivity connected to GeoCam Service");
			mService = IGeoCamService.Stub.asInterface(service);
			mServiceBound = true;
		}

		public void onServiceDisconnected(ComponentName name) {
			Log.d(GeoCamMobile.DEBUG_ID, "GeoCamMobile - UploadPhotosActivity disconnected from GeoCam Service");
			mService = null;
			mServiceBound = false;
		}		
	};

	private Runnable updateViewTask = new Runnable() {

		public void run() {
			Thread thisThread = Thread.currentThread();
			while (thisThread == mUpdateViewThread) {
				try {
					if (mServiceBound) {
						boolean isUploading = mService.isUploading();
						mHandler.sendMessage(Message.obtain(mHandler, 0, (isUploading ? 1 : 0), mService.getUploadQueue().size()));
					}
					Thread.sleep(1000);
				} 
				// mService calls
				catch (RemoteException e) {
				}
				// Thread.sleep()
				catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
			}
		}
	};
	
	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.upload_photos);
		mStatusTextView = (TextView)findViewById(R.id.upload_photos_status_text);

		mUpdateViewThread = new Thread(null, updateViewTask, "UpdateViewThread");
		mUpdateViewThread.start();
		
		mHandler = new Handler() {
			public void handleMessage(Message msg) {
				mStatusTextView.setText("\nBackground upload status: " 
						+ (msg.arg1 == 1 ? "uploading" : "idle") 
						+ "\n\n"
						+ String.valueOf(msg.arg2) + " image(s) in queue");
			}
		};

		//initListAdapter();
	}

	@Override
	public void onDestroy() {
		super.onDestroy();
		mUpdateViewThread = null;
	}
	
	@Override
	public void onPause() {
		super.onPause();
		if (mServiceBound) {
			unbindService(mServiceConn);
		}
	}
	
	@Override
	public void onResume() {
		super.onResume();
		mServiceBound = bindService(new Intent(this, GeoCamService.class), mServiceConn, Context.BIND_AUTO_CREATE);
	}
	

	/*
	@Override
	public void onConfigurationChanged(Configuration newConfig) {
		super.onConfigurationChanged(newConfig);
	}
	*/

	/*
	private void initListAdapter() {
		String[] projection = new String[] {
				MediaStore.Images.Thumbnails.DATA,
				MediaStore.Images.Thumbnails._ID,
		};
		//Cursor cur = managedQuery(MediaStore.Images.Thumbnails.EXTERNAL_CONTENT_URI, projection, null, null, null);
		
		Log.d(GeoCamMobile.DEBUG_ID, "Thumbnails URI: " + MediaStore.Images.Thumbnails.EXTERNAL_CONTENT_URI.toString());
		Log.d(GeoCamMobile.DEBUG_ID, "Media URI: " + MediaStore.Images.Media.EXTERNAL_CONTENT_URI.toString());
		
		Cursor cur = MediaStore.Images.Thumbnails.queryMiniThumbnails(getContentResolver(), MediaStore.Images.Thumbnails.EXTERNAL_CONTENT_URI, 
				MediaStore.Images.Thumbnails.MICRO_KIND, projection);
		
		int[] to = {R.id.upload_photos_list_item_image, R.id.upload_photos_list_item_label};
		
		// Set up list adapter
		mAdapter = new SimpleCursorAdapter(this, R.layout.upload_photos_list_item, cur, projection, to);
		mAdapter.setViewBinder(new PhotoViewBinder());
		getListView().setEmptyView(findViewById(R.id.empty));
		setListAdapter(mAdapter);
	}
	
	public class PhotoViewBinder implements SimpleCursorAdapter.ViewBinder {

		public boolean setViewValue(View view, Cursor cursor, int columnIndex) {
			int imageIndex = cursor.getColumnIndex(MediaStore.Images.Thumbnails.DATA);
			if (imageIndex == columnIndex) {
				String id = cursor.getString(cursor.getColumnIndex(MediaStore.Images.Thumbnails._ID));
				ImageView v = (ImageView)view;
				Uri uri = Uri.withAppendedPath(MediaStore.Images.Thumbnails.EXTERNAL_CONTENT_URI, id);
				Log.d(GeoCamMobile.DEBUG_ID, "MediaStore URI: " + uri.toString());
				v.setImageURI(uri);
				v.setScaleType(ImageView.ScaleType.CENTER);
				return true;
			}
			return false;
		}
	}
	*/
}
