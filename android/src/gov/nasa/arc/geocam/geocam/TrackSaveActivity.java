// __BEGIN_LICENSE__
// Copyright (C) 2008-2010 United States Government as represented by
// the Administrator of the National Aeronautics and Space Administration.
// All Rights Reserved.
// __END_LICENSE__

package gov.nasa.arc.geocam.geocam;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.Spinner;
import android.widget.Toast;

public class TrackSaveActivity extends Activity {
	public static final String TAG = "TrackSaveActivity";
	
	public static final String TRACK_ID_EXTRA = "gov.nasa.arc.geocam.geocam.TrackId";
	
	private static final String[] LINE_STYLES = { "Solid", "Dashed" };
	private static final String[] TRACK_COLORS = { "Red", "Green", "Blue" };
	private static final int[] TRACK_COLOR_VALUES = { 0xFFFF0000, 0xFF00FF00, 0xFF0000FF };
	
	private Spinner mTrackColor;
	private Spinner mLineStyle;
	private EditText mNotes;
	private long mTrackId;
	
    // Location/Track Service
    private IGeoCamService mService;
    private boolean mServiceBound = false;
    private ServiceConnection mServiceConn = new ServiceConnection() {
    	public void onServiceConnected(ComponentName name, IBinder service) {
            mService = IGeoCamService.Stub.asInterface(service);
            mServiceBound = true;
    	}
    	
    	public void onServiceDisconnected(ComponentName name) {
            mService = null;
            mServiceBound = false;
    	}
    };
	
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        setContentView(R.layout.track_save);
        
        mTrackColor = (Spinner) findViewById(R.id.track_save_line_color);
        ArrayAdapter<String> colors = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, TRACK_COLORS);
        colors.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        mTrackColor.setAdapter(colors);
        mTrackColor.setSelection(0);
        
        mLineStyle = (Spinner) findViewById(R.id.track_save_line_style);
        ArrayAdapter<String> styles = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, LINE_STYLES);
        styles.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        mLineStyle.setAdapter(styles);
        mTrackColor.setSelection(0);
 
        mNotes = (EditText) findViewById(R.id.track_save_notes);
        
        Intent i = getIntent();
        mTrackId = i.getLongExtra(TRACK_ID_EXTRA, -1);
        if (mTrackId == -1) {
            Log.e(TAG, "Trying to save a track that doesn't exist");
            this.finish();
        }
        
        ImageButton saveButton = (ImageButton) findViewById(R.id.track_save_save);
        saveButton.setOnClickListener(new OnClickListener() {
            public void onClick(View arg0) {
                if (!mServiceBound) {
                    Log.e(TAG, "onSave: No service bound.  Can't cancel track?");
                    return;
                }

                boolean isDashed = mLineStyle.getSelectedItemPosition() == 1;
                int color = TRACK_COLOR_VALUES[mTrackColor.getSelectedItemPosition()];
				
                try {
                    Log.d(TAG, "Stopping track");
                    mService.stopRecordingTrack();
					
                    GpsDbAdapter gpsDb = new GpsDbAdapter(TrackSaveActivity.this);
                    gpsDb.open();
                    
                    gpsDb.updateTrackNotes(mTrackId, mNotes.getText().toString());
                    gpsDb.updateTrackStyle(mTrackId, isDashed, color);
					
                    gpsDb.close();
					
                    mService.addTrackToUploadQueue(mTrackId);
                } catch (RemoteException e) {
                    Log.e(TAG, "onSave: Error communicating with service: " + e);
                }
				
                TrackSaveActivity.this.finish();
            }
        });
        
        ImageButton cancelButton = (ImageButton) findViewById(R.id.track_save_cancel);
        cancelButton.setOnClickListener(new OnClickListener() {
            public void onClick(View arg0) {
                Log.d(TAG, "Cancelling a track");
                try {
                    if (mServiceBound) {
                        mService.stopRecordingTrack();
                    }
                } catch (RemoteException e) {
                    Log.e(TAG, "onTrash: Error communicating with service: " + e);
                }

                TrackSaveActivity.this.finish();
            }
        });
    }
    
    public void onResume() {
    	super.onResume();
    	
        mServiceBound = bindService(new Intent(this, GeoCamService.class), mServiceConn, Context.BIND_AUTO_CREATE);
        if (!mServiceBound) {
            Log.e(TAG, "onResume - error binding to service");
        }
    }
    
    public void onPause() {
    	if (mServiceBound)
            unbindService(mServiceConn);
    	
    	super.onPause();
    }
}
