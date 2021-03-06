package com.danga.squeezer;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.preference.CheckBoxPreference;
import android.preference.EditTextPreference;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.PreferenceActivity;
import android.preference.Preference.OnPreferenceChangeListener;
import android.util.Log;

public class SettingsActivity extends PreferenceActivity implements
		OnPreferenceChangeListener {
	private final String TAG = "SettingsActivity";

    private ISqueezeService serviceStub = null;
    private EditTextPreference addrPref;

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        public void onServiceConnected(ComponentName name, IBinder service) {
            serviceStub = ISqueezeService.Stub.asInterface(service);
        }
        public void onServiceDisconnected(ComponentName name) {
            serviceStub = null;
        };
    };
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getPreferenceManager().setSharedPreferencesName(Preferences.NAME);
        addPreferencesFromResource(R.xml.preferences);

        // Both not yet implemented, so disable.  TODO(bradfitz): implement.
        CheckBoxPreference autoDiscoverPref = (CheckBoxPreference) findPreference(Preferences.KEY_AUTO_DISCOVER);
        autoDiscoverPref.setEnabled(false);
        CheckBoxPreference autoConnectPref = (CheckBoxPreference) findPreference(Preferences.KEY_AUTO_CONNECT);
        autoConnectPref.setEnabled(false);

        addrPref = (EditTextPreference) findPreference(Preferences.KEY_SERVERADDR);
        addrPref.setOnPreferenceChangeListener(this);
        
        SharedPreferences preferences = getPreferenceManager().getSharedPreferences();
        preferences.registerOnSharedPreferenceChangeListener(new SharedPreferences.OnSharedPreferenceChangeListener() {
            public void onSharedPreferenceChanged(
                    SharedPreferences sharedPreferences, String key) {
                    Log.v(TAG, "Preference changed: " + key);
                    if (serviceStub != null) {
                        try {
                            serviceStub.preferenceChanged(key);
                        } catch (RemoteException e) {}
                    }
            }
        });
        
        String currentCliAddr = preferences.getString(Preferences.KEY_SERVERADDR, "");
        updateAddressSummary(currentCliAddr);
    }

    @Override
    public void onResume() {
        super.onResume();
        bindService(new Intent(this, SqueezeService.class),
                    serviceConnection, Context.BIND_AUTO_CREATE);
        Log.d(TAG, "did bindService; serviceStub = " + serviceStub);
    }
    
    @Override
    public void onPause() {
        super.onPause();
        unbindService(serviceConnection);
    }    
        
	private void updateAddressSummary(String addr) {
        if (addr.length() > 0) {
            addrPref.setSummary(addr);
        } else {
            addrPref.setSummary("IP address of SqueezeCenter server, e.g. 10.0.0.5");
        }
    }

    public boolean onPreferenceChange(Preference preference, Object newValue) {
		final String key = preference.getKey();
		Log.v(TAG, "preference change for: " + key);
		if (Preferences.KEY_SERVERADDR.equals(key)) {
			final String ipPort = newValue.toString();
			// TODO: check that it looks valid?
			updateAddressSummary(ipPort);
			return true;
		}
			
		return false;
	}
	
	static void show(Context context) {
        final Intent intent = new Intent(context, SettingsActivity.class);
        context.startActivity(intent);
    }
}
