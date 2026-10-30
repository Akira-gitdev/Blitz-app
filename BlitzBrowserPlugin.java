package com.flambounds.blitz;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import androidx.activity.OnBackPressedCallback;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

/**
 * The Blitz browser. The page draws the toolbar, this plugin only supplies the web view
 * and puts it exactly where the page says (the "bounds"), so it lives inside the Search tab.
 */
@CapacitorPlugin(name = "BlitzBrowser")
public class BlitzBrowserPlugin extends Plugin {

  private FrameLayout box; // holds the web view, sits on top of the app page
  private WebView web;
  private FrameLayout full; // full screen video
  private View customView;
  private WebChromeClient.CustomViewCallback customCb;
  private OnBackPressedCallback backCb;
  private boolean shown = true;

  private float dens(Activity a) {
    return a.getResources().getDisplayMetrics().density;
  }

  private String fix(String raw) {
    String t = raw == null ? "" : raw.trim();
    if (t.length() == 0) return "about:blank";
    if (t.startsWith("http://") || t.startsWith("https://")) return t;
    if (t.indexOf(' ') < 0 && t.indexOf('.') > 0) return "https://" + t;
    return "https://duckduckgo.com/?q=" + Uri.encode(t);
  }

  private void place(Activity a, double x, double y, double w, double h, boolean visible) {
    if (box == null) return;
    float d = dens(a);
    int ox = 0;
    int oy = 0;
    try {
      View bw = getBridge().getWebView();
      View content = a.findViewById(android.R.id.content);
      int[] wl = new int[2];
      int[] cl = new int[2];
      bw.getLocationInWindow(wl);
      content.getLocationInWindow(cl);
      ox = wl[0] - cl[0];
      oy = wl[1] - cl[1];
    } catch (Exception e) {
      // keep zero
    }
    FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(Math.max(1, Math.round((float) (w * d))), Math.max(1, Math.round((float) (h * d))));
    lp.leftMargin = ox + Math.round((float) (x * d));
    lp.topMargin = oy + Math.round((float) (y * d));
    box.setLayoutParams(lp);
    shown = visible;
    box.setVisibility(visible ? View.VISIBLE : View.INVISIBLE);
    refreshBack();
  }

  private void refreshBack() {
    if (backCb == null) return;
    boolean on = box != null && ((customView != null) || (shown && web != null && web.canGoBack()));
    backCb.setEnabled(on);
  }

  private void page(String url, boolean done) {
    if (web == null) return;
    JSObject o = new JSObject();
    o.put("url", url);
    o.put("title", web.getTitle());
    o.put("canBack", web.canGoBack());
    o.put("canForward", web.canGoForward());
    notifyListeners(done ? "done" : "page", o);
    refreshBack();
  }

  private void build(final Activity a, String startUrl) {
    ViewGroup parent = (ViewGroup) a.findViewById(android.R.id.content);
    box = new FrameLayout(a);
    box.setBackgroundColor(Color.parseColor("#101018"));
    box.setClickable(true);

    web = new WebView(a);
    WebSettings s = web.getSettings();
    s.setJavaScriptEnabled(true);
    s.setDomStorageEnabled(true);
    s.setMediaPlaybackRequiresUserGesture(false);
    s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
    s.setSupportZoom(true);
    s.setBuiltInZoomControls(true);
    s.setDisplayZoomControls(false);
    s.setLoadWithOverviewMode(true);
    s.setUseWideViewPort(true);
    CookieManager.getInstance().setAcceptCookie(true);
    CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);

    web.setWebViewClient(new WebViewClient() {
      @Override
      public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
        String u = request.getUrl().toString();
        return !(u.startsWith("http://") || u.startsWith("https://") || u.startsWith("about:"));
      }

      @Override
      public void onPageStarted(WebView view, String url, Bitmap favicon) {
        notifyListeners("start", new JSObject());
      }

      @Override
      public void doUpdateVisitedHistory(WebView view, String url, boolean isReload) {
        page(url, false);
      }

      @Override
      public void onPageFinished(WebView view, String url) {
        page(url, true);
      }
    });
    web.setWebChromeClient(new WebChromeClient() {
      @Override
      public void onProgressChanged(WebView view, int newProgress) {
        JSObject o = new JSObject();
        o.put("p", newProgress);
        notifyListeners("progress", o);
      }

      @Override
      public void onShowCustomView(View view, WebChromeClient.CustomViewCallback cb) {
        if (customView != null) {
          cb.onCustomViewHidden();
          return;
        }
        customView = view;
        customCb = cb;
        ViewGroup content = (ViewGroup) a.findViewById(android.R.id.content);
        full = new FrameLayout(a);
        full.setBackgroundColor(Color.BLACK);
        full.setClickable(true);
        full.addView(view, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        content.addView(full, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        refreshBack();
      }

      @Override
      public void onHideCustomView() {
        hideCustom();
      }
    });
    web.setDownloadListener(new android.webkit.DownloadListener() {
      @Override
      public void onDownloadStart(String url, String userAgent, String contentDisposition, String mimeType, long contentLength) {
        JSObject o = new JSObject();
        o.put("url", url);
        o.put("userAgent", userAgent);
        o.put("contentDisposition", contentDisposition);
        o.put("mimeType", mimeType);
        o.put("length", contentLength);
        String ck = CookieManager.getInstance().getCookie(url);
        if (ck != null) o.put("cookie", ck);
        notifyListeners("download", o);
      }
    });
    box.addView(web, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    parent.addView(box, new FrameLayout.LayoutParams(10, 10));

    backCb = new OnBackPressedCallback(false) {
      @Override
      public void handleOnBackPressed() {
        if (customView != null) {
          hideCustom();
        } else if (web != null && web.canGoBack()) {
          web.goBack();
        }
      }
    };
    getActivity().getOnBackPressedDispatcher().addCallback(backCb);
  }

  private void hideCustom() {
    if (customView == null) return;
    try {
      if (full != null && full.getParent() != null) ((ViewGroup) full.getParent()).removeView(full);
    } catch (Exception e) {
      // already gone
    }
    full = null;
    customView = null;
    if (customCb != null) customCb.onCustomViewHidden();
    customCb = null;
    refreshBack();
  }

  private void closeNow() {
    if (box == null) return;
    hideCustom();
    if (backCb != null) {
      backCb.remove();
      backCb = null;
    }
    try {
      ((ViewGroup) box.getParent()).removeView(box);
    } catch (Exception e) {
      // already gone
    }
    if (web != null) {
      web.loadUrl("about:blank");
      web.destroy();
    }
    web = null;
    box = null;
    notifyListeners("closed", new JSObject());
  }

  @PluginMethod
  public void open(final PluginCall call) {
    final String url = fix(call.getString("url", ""));
    final double x = call.getDouble("x", 0.0);
    final double y = call.getDouble("y", 0.0);
    final double w = call.getDouble("w", 100.0);
    final double h = call.getDouble("h", 100.0);
    final Activity a = getActivity();
    a.runOnUiThread(new Runnable() {
      @Override
      public void run() {
        try {
          if (box == null) build(a, url);
          place(a, x, y, w, h, true);
          web.loadUrl(url);
          call.resolve();
        } catch (Exception e) {
          call.reject("Could not open the browser: " + e.getMessage());
        }
      }
    });
  }

  @PluginMethod
  public void go(final PluginCall call) {
    final String url = fix(call.getString("url", ""));
    getActivity().runOnUiThread(new Runnable() {
      @Override
      public void run() {
        if (web == null) {
          call.reject("Browser is not open");
          return;
        }
        web.loadUrl(url);
        call.resolve();
      }
    });
  }

  @PluginMethod
  public void bounds(final PluginCall call) {
    final double x = call.getDouble("x", 0.0);
    final double y = call.getDouble("y", 0.0);
    final double w = call.getDouble("w", 100.0);
    final double h = call.getDouble("h", 100.0);
    final boolean vis = call.getBoolean("visible", true);
    final Activity a = getActivity();
    a.runOnUiThread(new Runnable() {
      @Override
      public void run() {
        place(a, x, y, w, h, vis);
        call.resolve();
      }
    });
  }

  @PluginMethod
  public void back(final PluginCall call) {
    getActivity().runOnUiThread(new Runnable() {
      @Override
      public void run() {
        if (web != null && web.canGoBack()) web.goBack();
        call.resolve();
      }
    });
  }

  @PluginMethod
  public void forward(final PluginCall call) {
    getActivity().runOnUiThread(new Runnable() {
      @Override
      public void run() {
        if (web != null && web.canGoForward()) web.goForward();
        call.resolve();
      }
    });
  }

  @PluginMethod
  public void reload(final PluginCall call) {
    getActivity().runOnUiThread(new Runnable() {
      @Override
      public void run() {
        if (web != null) web.reload();
        call.resolve();
      }
    });
  }

  /** Opens a link in the phone's own app (YouTube, Maps, ...). Resolves opened:false when the app is not installed. */
  @PluginMethod
  public void openApp(final PluginCall call) {
    final String url = call.getString("url", "");
    final String pkg = call.getString("pkg", "");
    final Activity a = getActivity();
    a.runOnUiThread(new Runnable() {
      @Override
      public void run() {
        JSObject r = new JSObject();
        try {
          Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
          if (pkg != null && pkg.length() > 0) i.setPackage(pkg);
          i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
          a.startActivity(i);
          r.put("opened", true);
        } catch (ActivityNotFoundException e) {
          r.put("opened", false);
        } catch (Exception e) {
          r.put("opened", false);
        }
        call.resolve(r);
      }
    });
  }

  @PluginMethod
  public void close(final PluginCall call) {
    getActivity().runOnUiThread(new Runnable() {
      @Override
      public void run() {
        closeNow();
        call.resolve();
      }
    });
  }
}
