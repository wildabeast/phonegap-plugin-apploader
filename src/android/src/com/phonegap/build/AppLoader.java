package com.phonegap.build;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.json.*;

import org.apache.cordova.*;
import org.apache.cordova.engine.SystemWebViewClient;
import org.apache.cordova.engine.SystemWebViewEngine;

import android.annotation.TargetApi;
import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

public class AppLoader extends CordovaPlugin {

    CallbackContext cb;
    String zipPath;
    String extractPath;
    String indexPath;
    Context context;
    
    public void initialize(CordovaInterface cordova, final CordovaWebView webView) {
        super.initialize(cordova, webView);
        
        context = cordova.getActivity().getApplicationContext();
        
        zipPath = context.getFilesDir().getPath() + "/downloads/app.zip";
        extractPath = context.getFilesDir().getPath() + "/downloads/app_dir/";
        indexPath = extractPath + "index.html";
        Log.d("AppLoader", "zipPath: " + zipPath);
        Log.d("AppLoader", "indexPath: " + indexPath);
        
        cordova.getActivity().runOnUiThread(new Runnable() {  
            @Override
            public void run() {
                ((WebView) webView.getView()).getSettings().setAppCacheEnabled(false);
                ((WebView) webView.getView()).getSettings().setCacheMode(WebSettings.LOAD_NO_CACHE);
            }
        });
    }

    public boolean initialize() {
        
        boolean firstRun = false;
        File indexFile = new File(indexPath);
        if (!indexFile.exists()) {
            Log.d("AppLoader", "No index file, forcing sync");
            firstRun = true;
        } else {
            Log.d("AppLoader", "Found existing installed app");
        }
        return firstRun;
    }

    @TargetApi(19)
    @Override
    public boolean execute(
        String action, JSONArray args, final CallbackContext callbackContext ){

        this.cb = callbackContext;
        
        if (action.equals("initialize")) {
            boolean firstRun = this.initialize();
            JSONObject json = new JSONObject();
            try {
                json.put("firstRun", String.valueOf(firstRun));
            } catch (JSONException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }
            PluginResult r = new PluginResult(
                    PluginResult.Status.OK,
                    json);
            r.setKeepCallback(false);
            cb.sendPluginResult(r);
            return true;
        }
        
        if (action.equals("load")) {
            Log.d("AppLoader", "Loading " + indexPath);
            File f = new File(indexPath);
            if (!f.exists()) {
                PluginResult res = new PluginResult(
                    PluginResult.Status.ERROR,
                    "App not found at " + indexPath
                    );
                res.setKeepCallback(false);
                cb.sendPluginResult(res);
                return true;
            }
            injectHomeScript(callbackContext);
            
            cordova.getActivity().runOnUiThread(new Runnable() {  
                @Override
                public void run() {
                    webView.loadUrl("file://" + indexPath);
                }
            });
        }

        if (action.equals("fetch")) {
            String url;
            try {
                url = (String) args.get(0);
            } catch (JSONException e1) {
                PluginResult r = new PluginResult(
                        PluginResult.Status.ERROR,
                        e1.getMessage()
                        );
                r.setKeepCallback(false);
                cb.sendPluginResult(r);
                return true;
            }
            
            try {
                this.download(url, zipPath, extractPath);
            } catch (Exception e) {
                PluginResult r = new PluginResult(
                        PluginResult.Status.ERROR,
                        e.getMessage()
                        );
                r.setKeepCallback(false);
                cb.sendPluginResult(r);
                return true;
            }
        }

        PluginResult r = new PluginResult(
            PluginResult.Status.NO_RESULT
            );
        r.setKeepCallback(true);
        cb.sendPluginResult(r);
        return true;
    }


    public void download(final String url, final String zipPath, final String extractPath)
            throws Exception {
        
        cordova.getThreadPool().execute(new Runnable() {
            @Override
            public void run() {
                try {
                
                Log.d("AppLoader", "Extracting to " + extractPath);
                
                URL urlObj = new URL(url);
                HttpURLConnection conn = (HttpURLConnection) urlObj.openConnection();
                
                conn.setRequestMethod("GET");
                conn.connect();
                
                BufferedInputStream download = new BufferedInputStream(conn.getInputStream());
                
                File tmp = new File(zipPath);
                if (tmp.exists()) {
                    tmp.delete();
                } else {
                    tmp.getParentFile().mkdirs();
                }
                
                FileOutputStream file = new FileOutputStream(zipPath);
        
                int bytesRead = 0;
                long totalBytesToRead = conn.getContentLength();
                
                byte[] bytes = new byte[1024];
                
                if (totalBytesToRead == 0) {
                    file.close();
                    throw new Exception("... lets not divide by zero");
                }
                
                long totalBytesRead = 0;
                float percentage = 0.0f;
                float nextUpdatePercent = 5.0f;
                
                while ((bytesRead = download.read(bytes)) >= 0) {
                    file.write(bytes, 0, bytesRead);
                    totalBytesRead += bytesRead;
                    percentage = 100.0f * ((float) totalBytesRead / (float) totalBytesToRead);
                    
                    // only write at 5% increments
                    if (percentage >= nextUpdatePercent) {
                        PluginResult r = new PluginResult(
                                PluginResult.Status.OK,
                                AppLoader.message(
                                    "downloading", Float.toString(percentage)
                                    )
                                );
                        r.setKeepCallback(true);
                        Log.d("AppLoader", Float.toString(percentage));
                        nextUpdatePercent += 5.0f;
                        cb.sendPluginResult(r);
                    }
                    
                    // force a write
                    file.flush();
                }
                
                conn.disconnect();
                file.close();
                
                installApp(zipPath);
                
                } catch (Exception e) {
                    Log.d("AppLoader", e.getMessage());
                }
                
            }
        });
    }
    
    public void installApp(String path) throws Exception {
        ZipFile zip = new ZipFile(zipPath);
        Enumeration<? extends ZipEntry> entries = zip.entries(); 

        while(entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            File zipFile = new File(extractPath + entry.getName());
            if (!entry.isDirectory()) {
                zipFile.getParentFile().mkdirs();
                copyStream(
                        zip.getInputStream(entry),
                        new BufferedOutputStream(
                                new FileOutputStream(
                                    extractPath + entry.getName()
                                    )
                                )
                        );
            }
        }
        zip.close();

        Log.d("AppLoader", "Copying to " + extractPath);
        
        copyAssetFolder(context.getAssets(), "www", extractPath);
        
        PluginResult complete = new PluginResult(
                PluginResult.Status.OK,
                AppLoader.message("complete", "")
                );
        complete.setKeepCallback(false);
        cb.sendPluginResult(complete);
    }
    
    private void injectHomeScript(final CallbackContext callbackContext) {

        cordova.getActivity().runOnUiThread(new Runnable() {
            public void run() {
                
                class myWebClient extends SystemWebViewClient
                {

                    public myWebClient(SystemWebViewEngine parentEngine) {
                        super(parentEngine);
                        // TODO Auto-generated constructor stub
                    }

                    @Override
                    public void onPageFinished(WebView view, String url) {
                        // Michael Brooks' homepage.js (https://github.com/phonegap/connect-phonegap/blob/master/res/middleware/homepage.js)
                        String javascript="javascript: console.log('adding homepage.js'); (function(){var e={},t={touchstart:'touchstart',touchend:'touchend'};if(window.navigator.msPointerEnabled){t={touchstart:'MSPointerDown',touchend:'MSPointerUp'}}document.addEventListener(t.touchstart,function(t){var n=t.touches||[t],r;for(var i=0,s=n.length;i<s;i++){r=n[i];e[r.identifier||r.pointerId]=r}},false);document.addEventListener(t.touchend,function(t){var n=Object.keys(e).length;e={};if(n===3){t.preventDefault();window.history.back(window.history.length)}},false)})(window)";
                        view.loadUrl(javascript);
                        super.onPageFinished(view, url);
                    }
                }
                WebViewClient client = new myWebClient((SystemWebViewEngine)webView.getEngine());
                
                ((WebView) webView.getView()).setWebViewClient(client);
                //callbackContext.success();
            }
        });
    }
    
    private static boolean copyAssetFolder(AssetManager assetManager,
            String fromAssetPath, String toPath) {
        try {
            String[] files = assetManager.list(fromAssetPath);
            new File(toPath).mkdirs();
            boolean res = true;
            for (String file : files)
                if (assetManager.list(fromAssetPath + "/" + file).length == 0) // is file
                    res &= copyAsset(assetManager, 
                            fromAssetPath + "/" + file,
                            toPath + "/" + file);
                else                                    // is dir
                    res &= copyAssetFolder(assetManager, 
                            fromAssetPath + "/" + file,
                            toPath + "/" + file);
            return res;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    private static boolean copyAsset(AssetManager assetManager,
            String fromAssetPath, String toPath) {
        InputStream in = null;
        OutputStream out = null;
        try {
          in = assetManager.open(fromAssetPath);
          File dest = new File(toPath);
          if (dest.exists())
              return false;
          dest.createNewFile();
          out = new FileOutputStream(toPath);
          copyFile(in, out);
          in.close();
          in = null;
          out.flush();
          out.close();
          out = null;
          return true;
        } catch(Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    private static void copyFile(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[1024];
        int read;
        while((read = in.read(buffer)) != -1){
          out.write(buffer, 0, read);
        }
    }
    
    private void copyStream(InputStream in, OutputStream out)
        throws IOException {
        byte[] buffer = new byte[1024];
        int len;
        while((len = in.read(buffer)) >= 0) {
            out.write(buffer, 0, len);
        }
        in.close();
        out.close();
    }

    private static JSONObject message(String state, String status)
        throws JSONException {

        JSONObject json = new JSONObject();
        json.put("state", state);
        json.put("status", status);
        return json;
    }
}
