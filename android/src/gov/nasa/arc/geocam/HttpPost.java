package gov.nasa.arc.geocam;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import javax.net.ssl.HttpsURLConnection;

import android.util.Log;

public class HttpPost {
	private static final String DEBUG_ID = "HttpPost";
	
	public String BOUNDARY = "--------multipart_formdata_boundary$--------";
	public String CRLF = "\r\n";

	private String url = null;
	private Map<String,String> vars = null;
	private Map<String,File> files = null;

	public HttpPost() {
	}
	
	public HttpPost(String url) {
		this.url = url;
	}

	public HttpPost(String url, Map<String,String> vars, Map<String,File> files) {
		this.url = url;
		this.vars = vars;
		this.files = files;
	}

	public String post() {
		if (this.url == null || this.vars == null || this.files == null)
			return null;
		return post(this.url, this.vars, this.files);
	}
	
	public String post(Map<String,String> vars, Map<String,File> files) {
		if (this.url == null) 
			return null;
		return post(this.url, vars, files);
	}

	public String post(String url, Map<String,String> vars, Map<String,File> files) {
		try {
			HttpURLConnection conn = (HttpURLConnection)(new URL(url)).openConnection();
			conn.setDoInput(true);
			conn.setDoOutput(true);
			conn.setUseCaches(false);
			
			conn.setRequestMethod("POST");
			conn.setRequestProperty("Connection", "Keep-Alive");
			conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + BOUNDARY);
			
			DataOutputStream out = new DataOutputStream(conn.getOutputStream());
			assembleMultipart(out, vars, files);
			
			InputStream in = conn.getInputStream();
			BufferedReader reader = new BufferedReader(new InputStreamReader(in));
			StringBuilder sb = new StringBuilder();
			String line;
			while ((line = reader.readLine()) != null) {
				sb.append(line);
			}
			return sb.toString();
		}
		catch (UnsupportedEncodingException e) {
			return "Encoding exception: " + e;
		}
		catch (IOException e) {
			return "IOException: " + e;
		}
		catch (IllegalStateException e) {
			return "IllegalState: " + e;
		}
	}

	public void assembleMultipart(DataOutputStream out, Map<String,String> vars, Map<String,File> files) 
		throws IOException {
		for (String key : vars.keySet()) {
			out.writeBytes("--" + BOUNDARY + CRLF);
			out.writeBytes("Content-Disposition: form-data; name=\"" + key + "\"" + CRLF);
			out.writeBytes(CRLF);
			out.writeBytes(vars.get(key) + CRLF); 
		}

		for (String key : files.keySet()) { 
			File f = files.get(key);
			out.writeBytes("--" + BOUNDARY + CRLF);
			out.writeBytes("Content-Disposition: form-data; name=\"" + key
					+ "\"; filename=\"" + f.getName() + "\"" + CRLF);
			out.writeBytes("Content-Type: application/octet-stream" + CRLF);
			out.writeBytes(CRLF);

			// write file
			int maxBufferSize = 1024;
			FileInputStream fin = new FileInputStream(f);
			int bytesAvailable = fin.available();
			int bufferSize = Math.min(maxBufferSize, bytesAvailable);
			byte[] buffer = new byte[bufferSize];

			int bytesRead = fin.read(buffer, 0, bufferSize);
			while (bytesRead > 0) {
				out.write(buffer, 0, bufferSize);
				bytesAvailable = fin.available();
				bufferSize = Math.min(maxBufferSize, bytesAvailable);
				bytesRead = fin.read(buffer, 0, bufferSize);
			}
		}
		out.writeBytes(CRLF);
		out.writeBytes("--" + BOUNDARY + "--" + CRLF);
		out.writeBytes(CRLF);
	}
	
	// ---------------------------------------------------------------------------------------
	
	public void assembleMultipart(HttpPostProgress callback, DataOutputStream out, Map<String,String> vars,
			String fileKey, String fileName, InputStream istream) 
		throws IOException {
		
		for (String key : vars.keySet()) {
			out.writeBytes("--" + BOUNDARY + CRLF);
			out.writeBytes("Content-Disposition: form-data; name=\"" + key + "\"" + CRLF);
			out.writeBytes(CRLF);
			out.writeBytes(vars.get(key) + CRLF); 
		}

		out.writeBytes("--" + BOUNDARY + CRLF);
		out.writeBytes("Content-Disposition: form-data; name=\"" + fileKey
				+ "\"; filename=\"" + fileName + "\"" + CRLF);
		out.writeBytes("Content-Type: application/octet-stream" + CRLF);
		out.writeBytes(CRLF);

		// write stream
		//http://getablogger.blogspot.com/2008/01/android-how-to-post-file-to-php-server.html
		int maxBufferSize = 1024;
		int bytesCounter = 0;
		int prevBytesCounter = 0;
		int bytesAvailable = istream.available();
		int totalBytes = bytesAvailable;
		int bufferSize = Math.min(maxBufferSize, bytesAvailable);
		byte[] buffer = new byte[bufferSize];

		int bytesRead = istream.read(buffer, 0, bufferSize);
		while (bytesRead > 0) {
			bytesCounter += bytesRead;
			out.write(buffer, 0, bufferSize);
			bytesAvailable = istream.available();
			bufferSize = Math.min(maxBufferSize, bytesAvailable);
			bytesRead = istream.read(buffer, 0, bufferSize);
			//Log.d(DEBUG_ID, String.valueOf(bytesCounter) + " bytes sent / " + String.valueOf(totalBytes) + " bytes available");

			if (((bytesCounter - prevBytesCounter)*100.0/totalBytes) > 2.0) { 	
				callback.httpPostProgressUpdate((int)((bytesCounter*100.0)/totalBytes));
				prevBytesCounter = bytesCounter;
			}
		}

		out.writeBytes(CRLF);
		out.writeBytes("--" + BOUNDARY + "--" + CRLF);
		out.writeBytes(CRLF);
	}
	
	public String post(HttpPostProgress callback, String url, boolean useSSL, Map<String,String> vars, String fileKey, String fileName, InputStream istream) 
		throws IOException {
		try {
			HttpURLConnection conn;
			if (useSSL) {
				DisableSSLCertificateCheckUtil.disableChecks();
				conn = (HttpsURLConnection)(new URL(url)).openConnection();
			}
			else {
				conn = (HttpURLConnection)(new URL(url)).openConnection();
			}
			conn.setDoInput(true);
			conn.setDoOutput(true);
			conn.setUseCaches(false);
			conn.setAllowUserInteraction(true);

			conn.setRequestMethod("POST");
			conn.setRequestProperty("Connection", "Keep-Alive");
			conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + BOUNDARY);

			DataOutputStream out = new DataOutputStream(conn.getOutputStream());
			assembleMultipart(callback, out, vars, fileKey, fileName, istream);
			out.flush();
			out.close();
			
			InputStream in = conn.getInputStream();
			BufferedReader reader = new BufferedReader(new InputStreamReader(in));
			StringBuilder sb = new StringBuilder();
			String line;
			while ((line = reader.readLine()) != null) {
				sb.append(line);
			}
			return sb.toString();
		}
		catch (UnsupportedEncodingException e) {
			throw new IOException("HttpPost - Encoding exception: " + e);
		}
		catch (IOException e) {
			throw new IOException("HttpPost - IOException: " + e);
		}
		catch (IllegalStateException e) {
			throw new IOException("HttpPost - IllegalState: " + e);
		} 
		catch (KeyManagementException e) {
			throw new IOException("HttpPost - KeyManagement (disable ssl): " + e);
		} 
		catch (NoSuchAlgorithmException e) {
			throw new IOException("HttpPost - NoSuchAlgorithm (disable ssl): " + e);
		}
	}
}

