package app.netlify.fault_summary.twa;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Message;
import android.print.PrintAttributes;
import android.print.PrintManager;
import android.provider.MediaStore;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.MimeTypeMap;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.ArrayList;

/**
 * MLFRS - the Mainline Fault Summary site in its own app window.
 * The live site is always loaded, so every change made to the site shows here
 * straight away. Downloads, file uploads, WhatsApp sharing and job-card
 * printing are handled natively because a WebView cannot do them on its own.
 */
public class MainActivity extends Activity {

    private static final String START_URL = "https://fault-summary.netlify.app/";
    private static final String HOST = "fault-summary.netlify.app";
    private static final int FILE_CHOOSER_REQUEST = 1001;

    private WebView webView;
    private ValueCallback<Uri[]> filePathCallback;

    // Injected into every page: blob downloads go to the app, and the Web Share
    // API (used for WhatsApp) is provided through Android's own share sheet.
    private static final String BRIDGE_JS =
        "(function(){if(window.__mlfrsApp)return;window.__mlfrsApp=1;" +
        "function b64(bl){return new Promise(function(res,rej){var r=new FileReader();" +
        "r.onload=function(){var s=String(r.result);res(s.substring(s.indexOf(',')+1));};" +
        "r.onerror=rej;r.readAsDataURL(bl);});}" +
        "var oc=HTMLAnchorElement.prototype.click;" +
        "HTMLAnchorElement.prototype.click=function(){var h=this.href||'';" +
        "if(this.hasAttribute('download')&&(h.indexOf('blob:')===0||h.indexOf('data:')===0)){" +
        "var n=this.getAttribute('download')||'download';" +
        "fetch(h).then(function(r){return r.blob();}).then(function(bl){return b64(bl).then(function(d){" +
        "MLFRSApp.saveFile(d,n,bl.type||'');});}).catch(function(e){MLFRSApp.toast('Download failed: '+e.message);});" +
        "return;}return oc.call(this);};" +
        "navigator.canShare=function(){return true;};" +
        "navigator.share=function(d){d=d||{};var fs=Array.prototype.slice.call(d.files||[]);" +
        "return Promise.all(fs.map(function(f){return b64(f).then(function(x){return {name:f.name,type:f.type,data:x};});}))" +
        ".then(function(arr){MLFRSApp.share(JSON.stringify({files:arr,text:d.text||'',title:d.title||''}));});};" +
        "})();";

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        webView = new WebView(this);
        webView.setBackgroundColor(Color.WHITE);
        setContentView(webView);
        setupWebView(webView);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);
        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState);
        } else {
            webView.loadUrl(START_URL);
        }
    }

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    private void setupWebView(WebView wv) {
        WebSettings s = wv.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setSupportMultipleWindows(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        wv.addJavascriptInterface(new Bridge(this), "MLFRSApp");
        wv.setWebViewClient(new AppWebViewClient());
        wv.setWebChromeClient(new AppChromeClient());
        wv.setDownloadListener((url, userAgent, contentDisposition, mimeType, contentLength) -> {
            if (url != null && (url.startsWith("http://") || url.startsWith("https://"))) openExternal(Uri.parse(url));
        });
    }

    private boolean isOwnSite(Uri uri) {
        return uri != null && HOST.equalsIgnoreCase(uri.getHost());
    }

    private void openExternal(Uri uri) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, uri);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "No app found to open this link", Toast.LENGTH_SHORT).show();
        }
    }

    private class AppWebViewClient extends WebViewClient {
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            Uri uri = request.getUrl();
            String scheme = uri.getScheme() == null ? "" : uri.getScheme();
            if ((scheme.equals("https") || scheme.equals("http")) && isOwnSite(uri)) return false;
            if (scheme.equals("about") || scheme.equals("blob") || scheme.equals("data") || scheme.equals("javascript")) return false;
            openExternal(uri);   // WhatsApp, phone, mail and other sites open in their own apps
            return true;
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            super.onPageFinished(view, url);
            view.evaluateJavascript(BRIDGE_JS, null);
        }
    }

    private class AppChromeClient extends WebChromeClient {
        @Override
        public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
            if (filePathCallback != null) filePathCallback.onReceiveValue(null);
            filePathCallback = callback;
            try {
                Intent intent = params.createIntent();
                intent.setType("*/*");
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                if (params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE) intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                startActivityForResult(Intent.createChooser(intent, "Choose file"), FILE_CHOOSER_REQUEST);
                return true;
            } catch (ActivityNotFoundException e) {
                filePathCallback = null;
                Toast.makeText(MainActivity.this, "No file picker available", Toast.LENGTH_SHORT).show();
                return false;
            }
        }

        // window.open(): links to other sites (e.g. WhatsApp) open in their own
        // app; a blank page the site writes into (job card print) opens in a
        // full-screen window with Print and Close buttons.
        @Override
        public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture, Message resultMsg) {
            final Dialog dialog = new Dialog(MainActivity.this, android.R.style.Theme_Material_Light_NoActionBar);
            final WebView popup = new WebView(MainActivity.this);
            popup.getSettings().setJavaScriptEnabled(true);
            popup.getSettings().setDomStorageEnabled(true);
            popup.getSettings().setLoadWithOverviewMode(true);
            popup.getSettings().setUseWideViewPort(true);
            popup.getSettings().setBuiltInZoomControls(true);
            popup.getSettings().setDisplayZoomControls(false);
            final boolean[] external = {false};
            popup.setWebViewClient(new WebViewClient() {
                @Override
                public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest request) {
                    Uri uri = request.getUrl();
                    String scheme = uri.getScheme() == null ? "" : uri.getScheme();
                    if (scheme.equals("about") || scheme.equals("blob") || scheme.equals("data")) return false;
                    external[0] = true;
                    if (isOwnSite(uri)) webView.loadUrl(uri.toString()); else openExternal(uri);
                    dialog.dismiss();
                    return true;
                }

                @Override
                public void onPageStarted(WebView v, String url, android.graphics.Bitmap favicon) {
                    // Some Android versions load the first page of a new window
                    // without asking shouldOverrideUrlLoading - catch it here.
                    if (url == null || external[0]) return;
                    Uri uri = Uri.parse(url);
                    String scheme = uri.getScheme() == null ? "" : uri.getScheme();
                    if (scheme.equals("http") || scheme.equals("https")) {
                        external[0] = true;
                        v.stopLoading();
                        if (isOwnSite(uri)) webView.loadUrl(url); else openExternal(uri);
                        dialog.dismiss();
                    }
                }
            });
            popup.setWebChromeClient(new WebChromeClient() {
                @Override
                public void onCloseWindow(WebView window) { dialog.dismiss(); }
            });

            LinearLayout root = new LinearLayout(MainActivity.this);
            root.setOrientation(LinearLayout.VERTICAL);
            LinearLayout bar = new LinearLayout(MainActivity.this);
            bar.setOrientation(LinearLayout.HORIZONTAL);
            bar.setGravity(Gravity.END);
            bar.setBackgroundColor(Color.parseColor("#0D2F63"));
            Button print = new Button(MainActivity.this);
            print.setText("Print / Save PDF");
            print.setOnClickListener(b -> {
                PrintManager pm = (PrintManager) getSystemService(Context.PRINT_SERVICE);
                if (pm != null) pm.print("MLFRS", popup.createPrintDocumentAdapter("MLFRS"),
                        new PrintAttributes.Builder().setMediaSize(PrintAttributes.MediaSize.ISO_A4).build());
            });
            Button close = new Button(MainActivity.this);
            close.setText("Close");
            close.setOnClickListener(b -> dialog.dismiss());
            bar.addView(print);
            bar.addView(close);
            root.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            root.addView(popup, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
            dialog.setContentView(root);
            dialog.setOnDismissListener(d -> popup.destroy());

            WebView.WebViewTransport transport = (WebView.WebViewTransport) resultMsg.obj;
            transport.setWebView(popup);
            resultMsg.sendToTarget();
            // Show the window only if it is not just a hand-off to another app.
            popup.postDelayed(() -> { if (!external[0] && !isFinishing()) dialog.show(); }, 400);
            return true;
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == FILE_CHOOSER_REQUEST && filePathCallback != null) {
            Uri[] result = null;
            if (resultCode == RESULT_OK && data != null) {
                if (data.getClipData() != null) {
                    int n = data.getClipData().getItemCount();
                    result = new Uri[n];
                    for (int i = 0; i < n; i++) result[i] = data.getClipData().getItemAt(i).getUri();
                } else if (data.getData() != null) {
                    result = new Uri[]{data.getData()};
                }
            }
            filePathCallback.onReceiveValue(result);
            filePathCallback = null;
        }
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (webView != null) webView.saveState(outState);
    }

    @Override
    protected void onPause() {
        super.onPause();
        CookieManager.getInstance().flush();
    }

    /** Called from the page (MLFRSApp.*). */
    private static class Bridge {
        private final MainActivity a;
        Bridge(MainActivity a) { this.a = a; }

        @JavascriptInterface
        public void toast(String msg) {
            a.runOnUiThread(() -> Toast.makeText(a, msg, Toast.LENGTH_LONG).show());
        }

        @JavascriptInterface
        public void saveFile(String base64, String name, String mime) {
            try {
                byte[] bytes = Base64.decode(base64, Base64.DEFAULT);
                String type = guessMime(name, mime);
                String safe = name.replaceAll("[\\\\/:*?\"<>|]", "_");
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ContentResolver cr = a.getContentResolver();
                    ContentValues cv = new ContentValues();
                    cv.put(MediaStore.MediaColumns.DISPLAY_NAME, safe);
                    cv.put(MediaStore.MediaColumns.MIME_TYPE, type);
                    cv.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/MLFRS");
                    Uri uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                    if (uri == null) throw new Exception("could not create the file");
                    try (OutputStream os = cr.openOutputStream(uri)) { os.write(bytes); }
                    toast("Saved to Downloads/MLFRS: " + safe);
                } else {
                    File dir = new File(a.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "");
                    if (!dir.exists()) dir.mkdirs();
                    File f = new File(dir, safe);
                    try (FileOutputStream fos = new FileOutputStream(f)) { fos.write(bytes); }
                    toast("Saved: " + f.getAbsolutePath());
                }
            } catch (Exception e) {
                toast("Download failed: " + e.getMessage());
            }
        }

        @JavascriptInterface
        public void share(String json) {
            try {
                JSONObject o = new JSONObject(json);
                JSONArray files = o.optJSONArray("files");
                String text = o.optString("text", "");
                String title = o.optString("title", "");
                File dir = new File(a.getCacheDir(), "shared");
                if (!dir.exists()) dir.mkdirs();
                ArrayList<Uri> uris = new ArrayList<>();
                String type = "*/*";
                if (files != null) {
                    for (int i = 0; i < files.length(); i++) {
                        JSONObject f = files.getJSONObject(i);
                        String name = f.optString("name", "file").replaceAll("[\\\\/:*?\"<>|]", "_");
                        File out = new File(dir, name);
                        try (FileOutputStream fos = new FileOutputStream(out)) {
                            fos.write(Base64.decode(f.optString("data", ""), Base64.DEFAULT));
                        }
                        uris.add(FileProvider.getUriForFile(a, a.getPackageName() + ".fileprovider", out));
                        String t = guessMime(name, f.optString("type", ""));
                        type = (files.length() == 1) ? t : "*/*";
                    }
                }
                Intent send;
                if (uris.size() > 1) {
                    send = new Intent(Intent.ACTION_SEND_MULTIPLE);
                    send.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
                    send.setType(type);
                } else if (uris.size() == 1) {
                    send = new Intent(Intent.ACTION_SEND);
                    send.putExtra(Intent.EXTRA_STREAM, uris.get(0));
                    send.setType(type);
                } else {
                    send = new Intent(Intent.ACTION_SEND);
                    send.setType("text/plain");
                }
                if (!text.isEmpty()) send.putExtra(Intent.EXTRA_TEXT, text);
                if (!title.isEmpty()) send.putExtra(Intent.EXTRA_SUBJECT, title);
                send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                Intent chooser = Intent.createChooser(send, "Share");
                chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                a.runOnUiThread(() -> a.startActivity(chooser));
            } catch (Exception e) {
                toast("Share failed: " + e.getMessage());
            }
        }

        private static String guessMime(String name, String given) {
            if (given != null && !given.isEmpty()) return given;
            String ext = MimeTypeMap.getFileExtensionFromUrl(name.replace(" ", "_"));
            String m = ext == null ? null : MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext.toLowerCase());
            return m != null ? m : "application/octet-stream";
        }
    }
}
