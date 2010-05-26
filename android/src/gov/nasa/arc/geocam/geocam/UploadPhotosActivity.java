package gov.nasa.arc.geocam.geocam;

import gov.nasa.arc.geocam.geocam.util.ForegroundTracker;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.RemoteException;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.TextView;

public class UploadPhotosActivity extends Activity {
    private static final String IS_UPLOADING = "isUploading";
    private static final String UPLOAD_QUEUE_SIZE = "uploadQueueSize";
    private static final String LAST_STATUS = "lastStatus";
    
    private static final int CLEAR_ID = Menu.FIRST;
    
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
            mUpdateViewThread = new Thread(null, updateViewTask, "UpdateViewThread");
            mUpdateViewThread.start();
        }

        public void onServiceDisconnected(ComponentName name) {
            Log.d(GeoCamMobile.DEBUG_ID, "GeoCamMobile - UploadPhotosActivity disconnected from GeoCam Service");
            mService = null;
            mServiceBound = false;
        }        
    };
    
    private ForegroundTracker mForeground;

    private Runnable updateViewTask = new Runnable() {

        public void run() {
            Thread thisThread = Thread.currentThread();
            while (thisThread == mUpdateViewThread) {
                try {
                    if (mServiceBound) {
                        Message message = Message.obtain(mHandler);
                        
                        Bundle bundle = message.getData();
                        bundle.putBoolean(IS_UPLOADING, mService.isUploading());
                        bundle.putInt(UPLOAD_QUEUE_SIZE, mService.getQueueSize());
                        bundle.putInt(LAST_STATUS, mService.lastUploadStatus());
                        
                        mHandler.sendMessage(message);
                    }
                    Thread.sleep(1000);
                } 
                // mService calls
                catch (RemoteException e) {
                	Log.e(GeoCamMobile.DEBUG_ID, "UploadPhotos - remote exception");
                }
                // Thread.sleep()
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                catch (NullPointerException e) {
                	Log.e(GeoCamMobile.DEBUG_ID, "UploadPhotos - null pointer exception");                	
                }
            }
        }
    };
    
    public UploadPhotosActivity() {
    	super();
    	Log.d(GeoCamMobile.DEBUG_ID, "UploadPhotosActivity::constructor called [constructed]");
    }
    
    protected void finalize() throws Throwable {
    	Log.d(GeoCamMobile.DEBUG_ID, "UploadPhotosActivity::finalize called [destructed]");
    	super.finalize();
    }
    
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.upload_photos);
        
        Log.d(GeoCamMobile.DEBUG_ID, "UploadPhotosActivity::onCreate called");
        
        mStatusTextView = (TextView)findViewById(R.id.upload_photos_status_text);

        mHandler = new Handler() {
            public void handleMessage(Message msg) {
                Bundle data = msg.getData();
                
                mStatusTextView.setText("\nBackground upload status: " 
                        + (data.getBoolean(IS_UPLOADING) ? "uploading" : "idle") 
                        + "\n\n"
                        + String.valueOf(data.getInt(UPLOAD_QUEUE_SIZE)) + " image(s) in queue"
                        + "\n\n"
                        + "Last upload status: " + String.valueOf(data.getInt(LAST_STATUS)));
            }
        };
        
        mForeground = new ForegroundTracker(this);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        
        Log.d(GeoCamMobile.DEBUG_ID, "UploadPhotosActivity::onDestroy called");
    }
    
    @Override
    public void onPause() {
        super.onPause();
        mUpdateViewThread = null;

        mForeground.background();
        
        if (mServiceBound) {
            unbindService(mServiceConn);
        }
        Log.d(GeoCamMobile.DEBUG_ID, "UploadPhotosActivity::onPause called");
    }
    
    @Override
    public void onResume() {
        super.onResume();
        Log.d(GeoCamMobile.DEBUG_ID, "UploadPhotosActivity::onResume called");
        
        mForeground.foreground();
        
        mServiceBound = bindService(new Intent(this, GeoCamService.class), mServiceConn, Context.BIND_AUTO_CREATE);
    }
    
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
    	super.onCreateOptionsMenu(menu);
    	MenuItem clearItem = menu.add(0, CLEAR_ID, 0, R.string.upload_menu_clear);
    	clearItem.setIcon(getResources().getDrawable(android.R.drawable.ic_menu_delete));
    	
    	return true;
    }

    @Override
    public boolean onMenuItemSelected(int featureId, MenuItem item) {
    	switch(item.getItemId()) {
    	case CLEAR_ID:
    		showDialog(CLEAR_ID);
    		break;
    	}
    	return super.onMenuItemSelected(featureId, item);
    }
    
    @Override
    protected Dialog onCreateDialog(int id) {
    	switch (id) {
    	case CLEAR_ID:
    		return new AlertDialog.Builder(this)
    			.setTitle(R.string.upload_clear_dialog_title)
    			.setPositiveButton(R.string.upload_clear_dialog_ok, new DialogInterface.OnClickListener() {
					public void onClick(DialogInterface dialog, int whichButton) {
						try {
							mService.clearQueue();
						} 
						catch (RemoteException e) {
						}
					}
				})
				.setNegativeButton(R.string.upload_clear_dialog_cancel, new DialogInterface.OnClickListener() {
					public void onClick(DialogInterface dialog, int whichButton) {
					}
				})
				.create();				
    	default:
    		break;
    	}
    	return null;
    }
}
