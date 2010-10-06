// __BEGIN_LICENSE__
// Copyright (C) 2008-2010 United States Government as represented by
// the Administrator of the National Aeronautics and Space Administration.
// All Rights Reserved.
// __END_LICENSE__

package gov.nasa.arc.geocam.geocam;

import gov.nasa.arc.geocam.geocam.util.ForegroundTracker;

import java.io.FileNotFoundException;
import org.json.JSONException;
import org.json.JSONObject;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ComponentName;
import android.content.ContentValues;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.BitmapFactory;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.preference.PreferenceManager;
import android.provider.MediaStore;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ImageView.ScaleType;
import java.io.InputStream;

public class CameraPreviewActivity extends Activity {

    private static final int DIALOG_DELETE_PHOTO = 1;
    
    private static final int PICK_ICON_REQUEST = 1;

    private Uri mImageUri;
    private JSONObject mImageData;
    private String mImageNote;
    private String mImageTag = "default";

    private Bitmap mBitmap;

    // Variables for upload service
    private IGeoCamService mService;
    private boolean mServiceBound = false;
    
    private ImageButton mFireButton;

    private ServiceConnection mServiceConn = new ServiceConnection() {

        public void onServiceConnected(ComponentName name, IBinder service) {
            Log.d(GeoCamMobile.DEBUG_ID, "GeoCamMobile - CameraPreviewActivity connected to GeoCam Service");
            mService = IGeoCamService.Stub.asInterface(service);
            mServiceBound = true;
        }

        public void onServiceDisconnected(ComponentName name) {
            Log.d(GeoCamMobile.DEBUG_ID, "GeoCamMobile - CameraPreviewActivity disconnected from GeoCam Service");
            mService = null;
            mServiceBound = false;
        }        
    };
    
    // Foreground tracker
    private ForegroundTracker mForeground;
    
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
            final BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = 4;
            InputStream in = getContentResolver().openInputStream(mImageUri);
            Bitmap bitmap = BitmapFactory.decodeStream(in, null, opts);

            //Bitmap bitmap = MediaStore.Images.Media.getBitmap(getContentResolver(), mImageUri);
            ImageView imageView = (ImageView)findViewById(R.id.camera_preview_imageview);
            imageView.setAdjustViewBounds(true);
            imageView.setScaleType(ScaleType.CENTER_INSIDE);
            imageView.setImageBitmap(bitmap);
        } catch (FileNotFoundException e) {
            Log.d(GeoCamMobile.DEBUG_ID, "Error loading bitmap in CameraPreviewActivity");
        }

        SharedPreferences settings = PreferenceManager.getDefaultSharedPreferences(this);
        String defaultNotes = settings.getString(GeoCamMobile.SETTINGS_DEFAULT_NOTES_KEY, "");
        
        // Set default notes
        EditText notesText = (EditText) findViewById(R.id.camera_preview_edittext);
        notesText.setText(defaultNotes + " ");

        // Buttons
        mFireButton = (ImageButton) findViewById(R.id.camera_preview_fire_button);
        mFireButton.setImageDrawable(getResources().getDrawable(R.drawable.fire_icon_default));
        mFireButton.setOnClickListener(new View.OnClickListener() { 
            public void onClick(View v) {
                Intent intent = new Intent();
                intent.setAction(Intent.ACTION_PICK);
                intent.setClass(CameraPreviewActivity.this, FireIconActivity.class);
                startActivityForResult(intent, PICK_ICON_REQUEST);
            }
        });
            
        final ImageButton deleteButton = (ImageButton)findViewById(R.id.camera_preview_delete_button);
        deleteButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                CameraPreviewActivity.this.showDialog(DIALOG_DELETE_PHOTO);
            }            
        });
        
        final ImageButton saveButton = (ImageButton)findViewById(R.id.camera_preview_save_button);
        saveButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                mImageNote = ((EditText)findViewById(R.id.camera_preview_edittext)).getText().toString();
                Log.d(GeoCamMobile.DEBUG_ID, "Setting image note to: " + mImageNote);
                saveWithAnnotation();
            }            
        });

        mForeground = new ForegroundTracker(this);
    }
    
    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == PICK_ICON_REQUEST) {
            if (resultCode == RESULT_OK) {
                int icon_id = data.getIntExtra(FireIconActivity.EXTRA_ID, R.drawable.fire_icon_default);
                mImageTag = data.getStringExtra(FireIconActivity.EXTRA_TAG);
                mFireButton.setImageDrawable(getResources().getDrawable(icon_id));
            }
        }
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
        if (mServiceBound) {
            unbindService(mServiceConn);
        }
        mForeground.background();
    }

    @Override
    public void onResume() {
        super.onResume();
        mServiceBound = bindService(new Intent(this, GeoCamService.class), mServiceConn, Context.BIND_AUTO_CREATE);
        mForeground.foreground();
    }

    public boolean onKeyDown(int keyCode, KeyEvent event) {        
        switch (keyCode) {
        case KeyEvent.KEYCODE_CAMERA:
        case KeyEvent.KEYCODE_DPAD_CENTER:
            // Return here after catching camera keycode so we don't launch the built-in camera app
            return true;
        }    
        return super.onKeyDown(keyCode, event);
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
            mImageData.put("tag", mImageTag);
            mImageData.put("note", mImageNote);
            Log.d(GeoCamMobile.DEBUG_ID, 
                    "Saving image with data: " + mImageData.toString());
        }
        catch (JSONException e) {
            Log.d(GeoCamMobile.DEBUG_ID, "Error while adding annotation to image");
        }

        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DESCRIPTION, mImageData.toString());
        getContentResolver().update(mImageUri, values, null, null);
        Log.d(GeoCamMobile.DEBUG_ID, "Updating " + mImageUri.toString() + " with values " + values.toString());

        // Add image URI to upload queue
        try {
            mService.addToUploadQueue(mImageUri.toString());
        } 
        catch (RemoteException e) {
            Log.d(GeoCamMobile.DEBUG_ID, "Error talking to upload service while adding uri: " + mImageUri.toString() + " - " + e);
        }
        
        this.finish();
    }
    
    private void deletePhoto() {
        Log.d(GeoCamMobile.DEBUG_ID, "Deleting photo with Uri: " + mImageUri.toString());        
        getContentResolver().delete(mImageUri, null, null);
    }
}

