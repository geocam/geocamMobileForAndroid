package gov.nasa.arc.geocam.geocam;

import android.content.Context;
import android.content.DialogInterface;
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

public class AuthorizeUserDialog extends Dialog implements OnClickListener {
	private EditText mKeyEditText;
	private Button mContinueButton;
	private Button mQuitButton;
	
	public AuthorizeUserDialog(Context context) {
		super(context);
	}

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("Enter Beta Test Key");
        setContentView(R.layout.authorize_user);
        setCancelable(false);
        mKeyEditText = (EditText) findViewById(R.id.authorize_user_key_entry);
        mContinueButton = (Button) findViewById(R.id.authorize_user_continue_button);
        mQuitButton = (Button) findViewById(R.id.authorize_user_quit_button);
        mContinueButton.setOnClickListener(this);
        mQuitButton.setOnClickListener(this);
    }
    
    public void onClick(View view) {
    	switch (view.getId()) {
    	case R.id.authorize_user_continue_button:
    		String keyEntered = mKeyEditText.getText().toString();
    		Log.d(GeoCamMobile.DEBUG_ID, "AuthorizeUserDialog keyEntered: " + keyEntered);
            continueHandler(keyEntered);
            break;
    	case R.id.authorize_user_quit_button:
    		Log.d(GeoCamMobile.DEBUG_ID, "user quit");
    		quitHandler();
    		break;
    	}
    }
    
    public void continueHandler(String keyEntered) {
    	// callback, override
    }
    public void quitHandler() {
    	// callback, override
    }
}
