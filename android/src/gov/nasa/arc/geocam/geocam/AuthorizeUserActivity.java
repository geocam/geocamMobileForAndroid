// __BEGIN_LICENSE__
// Copyright (C) 2008-2010 United States Government as represented by
// the Administrator of the National Aeronautics and Space Administration.
// All Rights Reserved.
// __END_LICENSE__

package gov.nasa.arc.geocam.geocam;

import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.app.AlertDialog;
import android.app.Dialog;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.EditText;
import android.widget.Button;
import android.util.Log;
import android.app.Activity;

public class AuthorizeUserActivity extends Activity implements OnClickListener {
    public static final int DIALOG_AUTHORIZE_USER = 991;
    public static final int DIALOG_AUTHORIZE_USER_ERROR = 992;
    
    private EditText mKeyEditText;

    public void hookUpButton(int id) {
        Button b = (Button) findViewById(id);
        b.setOnClickListener(this);
    }
    
    public boolean userIsAuthorized() {
        SharedPreferences settings = PreferenceManager.getDefaultSharedPreferences(this);
        String betaTestAttempt = settings.getString(GeoCamMobile.SETTINGS_BETA_TEST_KEY, "");
        return (GeoCamMobile.SETTINGS_BETA_TEST_CORRECT.equals("")
                || betaTestAttempt.equals(GeoCamMobile.SETTINGS_BETA_TEST_CORRECT));
    }

    public void commitKey(String keyEntered) {
        SharedPreferences settings = PreferenceManager.getDefaultSharedPreferences(this);        
        SharedPreferences.Editor editor = settings.edit();
        editor.putString(GeoCamMobile.SETTINGS_BETA_TEST_KEY, keyEntered);
        editor.commit();
    }
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (userIsAuthorized()) {
            nextStep();
            finish();
        } else {
            setContentView(R.layout.authorize_user);
            setTitle("Enter Beta Test Key");
            mKeyEditText = (EditText) findViewById(R.id.authorize_user_key_entry);
            hookUpButton(R.id.authorize_user_continue_button);
            hookUpButton(R.id.authorize_user_quit_button);
        }
    }

    public void onClick(View view) {
        switch (view.getId()) {
        case R.id.authorize_user_continue_button:
            String keyEntered = mKeyEditText.getText().toString();
            if (!keyEntered.equals(GeoCamMobile.SETTINGS_BETA_TEST_CORRECT)) {
            	showDialog(DIALOG_AUTHORIZE_USER_ERROR);
            	return;
            }
            commitKey(keyEntered);
            Log.d(GeoCamMobile.DEBUG_ID, "AuthorizeUserDialog keyEntered: " + keyEntered);
            nextStep();
            finish();
            break;
        case R.id.authorize_user_quit_button:
            Log.d(GeoCamMobile.DEBUG_ID, "user quit");
            finish();
            break;
        }
    }

    @Override
    protected Dialog onCreateDialog(int id) {
        Log.d(GeoCamMobile.DEBUG_ID, "onCreateDialog");
        switch (id) {
        case DIALOG_AUTHORIZE_USER_ERROR:
               return new AlertDialog.Builder(this)
              .setTitle("Sorry, Incorrect Key")
              .setPositiveButton("Continue", new DialogInterface.OnClickListener() {
                  public void onClick(DialogInterface dialog, int whichButton) {
                      dialog.dismiss();
                  }
              })
              .create();
        }
        return null;
    }

    public void nextStep() {
        Intent i = new Intent(AuthorizeUserActivity.this, GeoCamMobile.class);
        startActivity(i);    
    }    
}
