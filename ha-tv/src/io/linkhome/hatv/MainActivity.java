package io.linkhome.hatv;

import android.app.Activity;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.json.JSONException;
import org.json.JSONObject;

/** Home Assistant on the TV: its own small app, so the web view runs apart from anything with ADB access. */
public class MainActivity extends Activity {
    private static final String HOME = "http://192.168.178.127:8123/";
    private static final String HOST = Uri.parse(HOME).getHost();
    private WebView web;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        nav = asset("tvnav.js");
        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        s.setMediaPlaybackRequiresUserGesture(false);
        CookieManager.getInstance().setAcceptCookie(true);
        web.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView v, String url) {
                v.evaluateJavascript(nav, null);
            }
            /** Only your Home Assistant opens here: nothing else gets a place in this window. */
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                return !HOST.equals(r.getUrl().getHost());
            }
            @Override public void onReceivedError(WebView v, WebResourceRequest r, WebResourceError e) {
                if (r.isForMainFrame()) {
                    v.loadData("<body style='background:#111;color:#eee;font:32px sans-serif;padding:80px'>"
                            + "Home Assistant ist nicht erreichbar. OK versucht es erneut.</body>", "text/html", "utf-8");
                    failed = true;
                }
            }
        });
        web.setFocusable(true);
        web.setFocusableInTouchMode(true);
        setContentView(web);
        web.requestFocus();
        if (state != null) web.restoreState(state);
        else web.loadUrl(HOME);
    }

    private boolean failed;
    private String nav = "";

    private String asset(String name) {
        try (InputStream in = getAssets().open(name)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            for (int n; (n = in.read(b)) > 0; ) out.write(b, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    /** The remote's arrows move tvnav.js's frame; OK taps where it is, as a finger would (fields then open the keyboard). */
    private void press(String key) {
        web.evaluateJavascript(nav + ";window.__tvNav('" + key + "')", value -> {
            if (!"ok".equals(key) || value == null || "null".equals(value)) return;
            try {
                JSONObject at = new JSONObject(value);
                float x = (float) at.getDouble("x"), y = (float) at.getDouble("y");
                long now = SystemClock.uptimeMillis();
                MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0);
                MotionEvent up = MotionEvent.obtain(now, now + 60, MotionEvent.ACTION_UP, x, y, 0);
                down.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
                up.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
                web.dispatchTouchEvent(down);
                web.dispatchTouchEvent(up);
                down.recycle();
                up.recycle();
                web.postDelayed(() -> web.evaluateJavascript(
                        "(function(){var a=document.activeElement;while(a&&a.shadowRoot&&a.shadowRoot.activeElement)a=a.shadowRoot.activeElement;"
                        + "return !!a&&(a.tagName==='TEXTAREA'||(a.tagName==='INPUT'&&!/^(checkbox|radio|button|submit)$/.test(a.type)));})()",
                        typing -> {
                            if ("true".equals(typing)) {
                                InputMethodManager im = getSystemService(InputMethodManager.class);
                                web.requestFocus();
                                im.showSoftInput(web, 0);
                            }
                        }), 250);
            } catch (JSONException e) {
                // no frame on this page
            }
        });
    }

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        if (failed && e.getAction() == KeyEvent.ACTION_UP && e.getKeyCode() == KeyEvent.KEYCODE_DPAD_CENTER) {
            failed = false;
            web.loadUrl(HOME);
            return true;
        }
        String key = null;
        switch (e.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_UP: key = "up"; break;
            case KeyEvent.KEYCODE_DPAD_DOWN: key = "down"; break;
            case KeyEvent.KEYCODE_DPAD_LEFT: key = "left"; break;
            case KeyEvent.KEYCODE_DPAD_RIGHT: key = "right"; break;
            case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER: key = "ok"; break;
            default: break;
        }
        if (key != null && !failed) {
            if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() % 2 == 0) press(key);
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    @Override public void onBackPressed() {
        if (web.canGoBack() && !failed) web.goBack();
        else super.onBackPressed();
    }

    @Override protected void onResume() {
        super.onResume();
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        web.onResume();
    }

    @Override protected void onPause() {
        web.onPause();
        CookieManager.getInstance().flush();
        super.onPause();
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        web.saveState(out);
    }

    @Override protected void onDestroy() {
        web.destroy();
        super.onDestroy();
    }
}
