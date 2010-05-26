package gov.nasa.arc.geocam.geocam;

import gov.nasa.arc.geocam.geocam.util.ForegroundTracker;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.preference.EditTextPreference;
import android.preference.Preference;
import android.preference.PreferenceActivity;
import android.preference.PreferenceCategory;
import android.preference.PreferenceManager;
import android.preference.PreferenceScreen;
import android.preference.Preference.OnPreferenceChangeListener;
import android.preference.Preference.OnPreferenceClickListener;

public class SettingsActivity extends PreferenceActivity implements OnPreferenceChangeListener, OnPreferenceClickListener {

    private EditTextPreference m_serverUrlPref;
    private EditTextPreference m_serverUsernamePref;
    //private EditTextPreference m_serverInboxPref;
    
    private ForegroundTracker mForeground;
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setPreferenceScreen(createSettingsHierarchy());
        
        mForeground = new ForegroundTracker(this);
    }
    
    protected void onPause() {
    	super.onPause();
    	mForeground.background();
    }
    protected void onResume() {
    	super.onResume();
    	mForeground.foreground();
    }
    
    private PreferenceScreen createSettingsHierarchy() {
        SharedPreferences settings = PreferenceManager.getDefaultSharedPreferences(this);
        String serverUrl = settings.getString(GeoCamMobile.SETTINGS_SERVER_URL_KEY, GeoCamMobile.SETTINGS_SERVER_URL_DEFAULT);
        String serverUsername = settings.getString(GeoCamMobile.SETTINGS_SERVER_USERNAME_KEY, GeoCamMobile.SETTINGS_SERVER_USERNAME_DEFAULT);
        //String serverInbox = settings.getString(GeoCamMobile.SETTINGS_SERVER_INBOX_KEY, GeoCamMobile.SETTINGS_SERVER_INBOX_DEFAULT);

        //Root
        PreferenceScreen root = getPreferenceManager().createPreferenceScreen(this);

        // Server settings
        PreferenceCategory serverCategory = new PreferenceCategory(this);
        serverCategory.setTitle(R.string.settings_category_server);
        root.addPreference(serverCategory);

        // Server url
        m_serverUrlPref = new EditTextPreference(this);
        m_serverUrlPref.setKey(GeoCamMobile.SETTINGS_SERVER_URL_KEY);
        m_serverUrlPref.setDialogTitle(R.string.settings_server_url_title);
        m_serverUrlPref.setTitle(R.string.settings_server_url_title);
        m_serverUrlPref.setSummary(serverUrl);
        m_serverUrlPref.setOnPreferenceChangeListener(this);
        serverCategory.addPreference(m_serverUrlPref);

        // Server username
        m_serverUsernamePref = new EditTextPreference(this);
        m_serverUsernamePref.setKey(GeoCamMobile.SETTINGS_SERVER_USERNAME_KEY);
        m_serverUsernamePref.setDialogTitle(R.string.settings_server_username_title);
        m_serverUsernamePref.setTitle(R.string.settings_server_username_title);
        m_serverUsernamePref.setSummary(serverUsername);
        m_serverUsernamePref.setOnPreferenceChangeListener(this);
        serverCategory.addPreference(m_serverUsernamePref);

        // Server inbox
        /*
        m_serverInboxPref = new EditTextPreference(this);
        m_serverInboxPref.setKey(GeoCamMobile.SETTINGS_SERVER_INBOX_KEY);
        m_serverInboxPref.setDialogTitle(R.string.settings_server_inbox_title);
        m_serverInboxPref.setTitle(R.string.settings_server_inbox_title);
        m_serverInboxPref.setSummary(serverInbox);
        m_serverInboxPref.setOnPreferenceChangeListener(this);
        serverCategory.addPreference(m_serverInboxPref);
        */
        
        // Reset settings
        Preference resetPref = new Preference(this);
        resetPref.setKey(GeoCamMobile.SETTINGS_RESET_KEY);
        resetPref.setTitle(R.string.settings_reset_title);
        resetPref.setOnPreferenceClickListener(this);
        serverCategory.addPreference(resetPref);
        
        return root;
    }

    public boolean onPreferenceChange(Preference preference, Object newValue) {
        preference.setSummary((String)newValue);
        return true;
    }

    public boolean onPreferenceClick(Preference preference) {
        SharedPreferences settings = PreferenceManager.getDefaultSharedPreferences(this);
        SharedPreferences.Editor editor = settings.edit();
        editor.putString(GeoCamMobile.SETTINGS_SERVER_URL_KEY, GeoCamMobile.SETTINGS_SERVER_URL_DEFAULT);
        editor.putString(GeoCamMobile.SETTINGS_SERVER_USERNAME_KEY, GeoCamMobile.SETTINGS_SERVER_USERNAME_DEFAULT);
        //editor.putString(GeoCamMobile.SETTINGS_SERVER_INBOX_KEY, GeoCamMobile.SETTINGS_SERVER_INBOX_DEFAULT);
        editor.commit();            

        m_serverUrlPref.setSummary(GeoCamMobile.SETTINGS_SERVER_URL_DEFAULT);
        m_serverUsernamePref.setSummary(GeoCamMobile.SETTINGS_SERVER_USERNAME_DEFAULT);
        
        return true;
    }
}
