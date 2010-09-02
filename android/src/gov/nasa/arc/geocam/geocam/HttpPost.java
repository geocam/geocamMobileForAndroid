package gov.nasa.arc.geocam.geocam;

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
import java.security.GeneralSecurityException;
import java.util.Map;
import javax.net.ssl.HttpsURLConnection;
import android.util.Log;
import org.xmlBlaster.util.Base64;

public class HttpPost {
    
    public static final String BOUNDARY = "--------multipart_formdata_boundary$--------";
    public static final String CRLF = "\r\n";

    // unused for a while, may not work anymore
    public static String postFiles(String url, Map<String,String> vars, Map<String,File> files) {
        try {
            HttpURLConnection conn = (HttpURLConnection)(new URL(url)).openConnection();
            conn.setDoInput(true);
            conn.setDoOutput(true);
            conn.setUseCaches(false);
            
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Connection", "Keep-Alive");
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + BOUNDARY);
            
            DataOutputStream out = new DataOutputStream(conn.getOutputStream());
            assembleMultipartFiles(out, vars, files);
            
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

    protected static void assembleMultipartFiles(DataOutputStream out, Map<String,String> vars, Map<String,File> files) 
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
    
    protected static void assembleMultipart(DataOutputStream out, Map<String,String> vars, String fileKey, String fileName, InputStream istream) 
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
        int bytesAvailable = istream.available();
        int bufferSize = Math.min(maxBufferSize, bytesAvailable);
        byte[] buffer = new byte[bufferSize];

        int bytesRead = istream.read(buffer, 0, bufferSize);
        while (bytesRead > 0) {
            out.write(buffer, 0, bufferSize);
            out.flush();
            bytesAvailable = istream.available();
            bufferSize = Math.min(maxBufferSize, bytesAvailable);
            bytesRead = istream.read(buffer, 0, bufferSize);
        }

        out.writeBytes(CRLF);
        out.writeBytes("--" + BOUNDARY + "--" + CRLF);
        out.writeBytes(CRLF);
    }
    
    public static int post(String url, Map<String,String> vars, String fileKey, String fileName,
                           InputStream istream, String username, String password) throws IOException {
        boolean useAuth = (password != "");
        // force SSL if useAuth=true (would send password unencrypted otherwise)
        boolean useSSL = useAuth || url.startsWith("https");

        if (useSSL) {
            if (!url.startsWith("https")) {
                // replace http: with https: -- this will obviously screw
                // up if input is something other than http so don't do that :)
                url = "https:" + url.substring(5);
            }

            // would rather not do this, need to check if there's still a problem
            // with our ssl certificates on NASA servers.
            try {
                DisableSSLCertificateCheckUtil.disableChecks();
            } catch (GeneralSecurityException e) {
                throw new IOException("HttpPost - disable ssl: " + e);
            }
        }

        HttpURLConnection conn;
        try {
            if (useSSL) {
                conn = (HttpsURLConnection)(new URL(url)).openConnection();
            }
            else {
                conn = (HttpURLConnection)(new URL(url)).openConnection();
            }
        } catch (IOException e) {
            throw new IOException("HttpPost - IOException: " + e);
        } 

        if (useAuth) {
            // add pre-emptive http basic authentication.  using
            // homebrew version -- early versions of android
            // authenticator have problems and this is easy.
            String userpassword = username + ":" + password;
            String encodedAuthorization = Base64.encode(userpassword.getBytes());
            conn.setRequestProperty("Authorization",
                                    "Basic " + encodedAuthorization);
        }

        try {
            conn.setDoInput(true);
            conn.setDoOutput(true);
            conn.setUseCaches(false);
            conn.setAllowUserInteraction(true);

            conn.setRequestMethod("POST");
            conn.setRequestProperty("Connection", "Keep-Alive");
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + BOUNDARY);

            Log.d("HttpPost", vars.toString());

            DataOutputStream out = new DataOutputStream(conn.getOutputStream());
            assembleMultipart(out, vars, fileKey, fileName, istream);
            istream.close();
            out.flush();
            
            InputStream in = conn.getInputStream();
            BufferedReader reader = new BufferedReader(new InputStreamReader(in), 2048);
            
            // Set postedSuccess to true if there is a line in the HTTP response in
            // the form "GEOCAM_SHARE_POSTED <file>" where <file> equals fileName.
            // Our old success condition was just checking for HTTP return code 200; turns
            // out that sometimes gives false positives.
            Boolean postedSuccess = false;
            for (String line = reader.readLine(); line != null; line = reader.readLine()) {
            	//Log.d("HttpPost", line);
                if (!postedSuccess && line.contains("GEOCAM_SHARE_POSTED")) {
                    String[] vals = line.trim().split("\\s+");
                    for (int i = 0; i < vals.length; ++i) {
                    	//String filePosted = vals[1];
                    	if (vals[i].equals(fileName)) {
                    		Log.d(GeoCamMobile.DEBUG_ID, line);
                    		postedSuccess = true;
                    		break;
                    	}
                    }
                }
            }
            out.close();
            
            int responseCode = 0;
            responseCode = conn.getResponseCode();
            if (responseCode == 200 && !postedSuccess) {
                // our code for when we got value 200 but no confirmation
                responseCode = -3;
            }
            return responseCode;
        }
        catch (UnsupportedEncodingException e) {
            throw new IOException("HttpPost - Encoding exception: " + e);
        }
        
        // ??? when would this ever be thrown?
        catch (IllegalStateException e) {
            throw new IOException("HttpPost - IllegalState: " + e);
        } 
        
        catch (IOException e) {
            try {
                return conn.getResponseCode();
            } catch(IOException f) {
                throw new IOException("HttpPost - IOException: " + e);
            }
        }
    }
}

