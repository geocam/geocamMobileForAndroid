// __BEGIN_LICENSE__
// Copyright (C) 2008-2010 United States Government as represented by
// the Administrator of the National Aeronautics and Space Administration.
// All Rights Reserved.
// __END_LICENSE__

package gov.nasa.arc.geocam.geocam;

import android.app.Activity;
import android.os.Bundle;
import android.provider.MediaStore;
import android.database.Cursor;

public class GalleryActivity extends Activity {

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }

    @SuppressWarnings("unused")
	private void loadPhotos() {
        String[] projection = new String[] {
                MediaStore.Images.ImageColumns._ID,
                MediaStore.Images.ImageColumns.DISPLAY_NAME,
                MediaStore.Images.ImageColumns.DATE_TAKEN,
                MediaStore.Images.ImageColumns.LATITUDE,
                MediaStore.Images.ImageColumns.LONGITUDE,
                MediaStore.Images.ImageColumns.DESCRIPTION,
                MediaStore.Images.ImageColumns.SIZE,
        };
        Cursor cur = managedQuery(GeoCamMobile.MEDIA_URI, projection, null, null, null);
        cur.moveToFirst();
        String id, title, name, bucket_id, bucket_name, size;
        id = cur.getString(cur.getColumnIndex(MediaStore.Images.ImageColumns._ID));
        title = cur.getString(cur.getColumnIndex(MediaStore.Images.ImageColumns.TITLE));
        name = cur.getString(cur.getColumnIndex(MediaStore.Images.ImageColumns.DISPLAY_NAME));
        bucket_id = cur.getString(cur.getColumnIndex(MediaStore.Images.ImageColumns.BUCKET_ID));
        bucket_name = cur.getString(cur.getColumnIndex(MediaStore.Images.ImageColumns.BUCKET_DISPLAY_NAME));
        size = String.valueOf(cur.getInt(cur.getColumnIndex(MediaStore.Images.ImageColumns.SIZE)));
        
    }
}
