// __BEGIN_LICENSE__
// Copyright (C) 2008-2010 United States Government as represented by
// the Administrator of the National Aeronautics and Space Administration.
// All Rights Reserved.
// __END_LICENSE__

package gov.nasa.arc.geocam.geocam.util;

import java.lang.ref.WeakReference;

import gov.nasa.arc.geocam.geocam.GeoCamService;
import gov.nasa.arc.geocam.geocam.IGeoCamService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

public class ForegroundTracker {
	private static final String TAG = "ForegroundTracker";
	
	private WeakReference<Context> mContext;

    private IGeoCamService mService;
    private boolean mServiceBound = false;

    private ServiceConnection mServiceConn = new ServiceConnection() {

        public void onServiceConnected(ComponentName name, IBinder service) {
            Log.d(TAG, "Connected to GeoCam Service");
            mService = IGeoCamService.Stub.asInterface(service);
            mServiceBound = true;
            
            reallyForeground();
		}

        public void onServiceDisconnected(ComponentName name) {
            Log.d(TAG, "Disconnected from GeoCam Service");
            mService = null;
            mServiceBound = false;
        }        
    };
	
	public ForegroundTracker(Context ctx) {
		mContext = new WeakReference<Context>(ctx);
	}
	
	public void foreground() {
		if (!mServiceBound)
			mServiceBound = mContext.get().bindService(
					new Intent(mContext.get(), GeoCamService.class), 
					mServiceConn, Context.BIND_AUTO_CREATE);
		else
			reallyForeground();
	}
	
	private void reallyForeground() {
		if (!mServiceBound || mService == null) {
			Log.w(TAG, "Trying to foreground, but no service!");
			return;
		}
		
        try {
        	mService.applicationVisible();
        } catch (RemoteException e) {
        	Log.d(TAG, "Error trying to set application visible");
        }
	}
	
	public void background() {
		if (!mServiceBound || mService == null) {
			Log.w(TAG, "Trying to background, but no service!");
			return; 
		}

		try {
			mService.applicationInvisible();
		} catch (RemoteException e) {
			Log.d(TAG, "Error trying to set application invisible");
		}
		
		mContext.get().unbindService(mServiceConn);
		mService = null;
		mServiceBound = false;
	}
}
