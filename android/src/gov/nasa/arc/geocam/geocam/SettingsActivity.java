// __BEGIN_LICENSE__
// Copyright (C) 2008-2010 United States Government as represented by
// the Administrator of the National Aeronautics and Space Administration.
// All Rights Reserved.
// __END_LICENSE__

package gov.nasa.arc.geocam.geocam;

import gov.nasa.arc.geocam.geocam.util.ForegroundTracker;
import android.os.Bundle;
import android.preference.Preference;
import android.preference.PreferenceActivity;
import android.preference.PreferenceManager;
import android.preference.Preference.OnPreferenceClickListener;
import android.content.Context;
import android.util.Log;
import android.app.Dialog;
import android.content.DialogInterface;
import android.app.AlertDialog;

public class SettingsActivity extends PreferenceActivity {
    public static final int DIALOG_REALLY_RESET_SETTINGS = 993;
    
    private ForegroundTracker mForeground;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        addPreferencesFromResource(R.xml.preferences);

        mForeground = new ForegroundTracker(this);

        // handler for when the user clicks "Reset to original values"
        Preference reset = (Preference) findPreference("settings_reset");
        reset.setOnPreferenceClickListener(new OnPreferenceClickListener() {
                public boolean onPreferenceClick(Preference preference) {
                    showDialog(DIALOG_REALLY_RESET_SETTINGS);
                    return true;
                }
            }
        );
    }
    
    protected void onPause() {
    	super.onPause();
    	mForeground.background();
    }

    protected void onResume() {
    	super.onResume();
    	mForeground.foreground();
    }

    @Override
    protected Dialog onCreateDialog(int id) {
        switch (id) {
        case DIALOG_REALLY_RESET_SETTINGS:
            return new AlertDialog.Builder(this)
                .setTitle("Really reset GeoCam settings to original values?")
                .setPositiveButton("OK", new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog, int whichButton) {
                            dialog.dismiss();
                            resetSettings();
                        }
                    })
                .setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog, int whichButton) {
                            dialog.dismiss();
                        }
                    })
                .create();
        }
        return null;
    }

    void resetSettings() {
        // reset settings
        Context context = getBaseContext();
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit();
        PreferenceManager.setDefaultValues(context, R.xml.preferences, true);
        
        // but don't force user to enter beta key again
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putString(GeoCamMobile.SETTINGS_BETA_TEST_KEY,
                       GeoCamMobile.SETTINGS_BETA_TEST_CORRECT).commit();
        
        // restart SettingsActivity to refresh UI with new values
        finish();
        startActivity(getIntent());
    }
}
