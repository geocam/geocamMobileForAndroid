package gov.nasa.arc.geocam;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.util.concurrent.LinkedBlockingQueue;

import org.json.JSONArray;
import org.json.JSONException;

import android.content.Context;
import android.util.Log;

// WARNING: We currently only support template type String!  See loadFromFile.
public class JsonQueueFileStore<T> extends LinkedBlockingQueue<T> {

	private String mName;
	private Context mContext;
	
	public JsonQueueFileStore(Context context, String name) {
		super();
		mContext = context;
		mName = name;
	}
	
	public boolean loadFromFile() {
		try {
			FileInputStream fstream = mContext.openFileInput(mName);
			BufferedReader reader = new BufferedReader(new InputStreamReader(fstream));
			JSONArray jsonArray = new JSONArray(reader.readLine());
			for (int i = 0; i < jsonArray.length(); i++) {
				this.add((T) jsonArray.getString(i));
			}
			return true;
		} 
		catch (FileNotFoundException e) {
			Log.d(GeoCamMobile.DEBUG_ID, "JsonQueueFileStore: " + e);
			return false;
		} 
		catch (JSONException e) {
			Log.d(GeoCamMobile.DEBUG_ID, "JsonQueueFileStore: " + e);
			return false;
		} 
		catch (IOException e) {
			Log.d(GeoCamMobile.DEBUG_ID, "JsonQueueFileStore: " + e);
			return false;
		}		
	}
	
	public boolean saveToFile() {
		try {
			JSONArray jsonArray = new JSONArray(this);
			FileOutputStream fstream = mContext.openFileOutput(mName, Context.MODE_PRIVATE);
			PrintWriter p = new PrintWriter(fstream);
			p.print(jsonArray.toString());
			p.close();
			fstream.flush();
			fstream.close();
			return true;
		}
		catch (IOException e) {
			return false;
		}
	}
}
