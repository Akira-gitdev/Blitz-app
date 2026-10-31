package com.flambounds.blitz;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Message;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import androidx.activity.OnBackPressedCallback;
import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Blitz browser. The page draws the toolbar and the tab strip; this plugin supplies the web views
 * (one per tab), puts them exactly where the page says (the "bounds"), and reports what happens.
 */
@CapacitorPlugin(name = "BlitzBrowser")
public class BlitzBrowserPlugin extends Plugin {

  private static final int MAX_TABS = 8;
  private static final String DESKTOP_UA =
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";
  private static final String[] AD_HOSTS = {
    "doubleclick.net", "googlesyndication.com", "googleadservices.com", "googletagservices.com", "google-analytics.com",
    "googletagmanager.com", "adservice.google.com", "2mdn.net", "adnxs.com", "taboola.com", "outbrain.com", "criteo.com",
    "criteo.net", "scorecardresearch.com", "amazon-adsystem.com", "adsrvr.org", "moatads.com", "rubiconproject.com",
    "pubmatic.com", "openx.net", "adform.net", "smartadserver.com", "popads.net", "propellerads.com", "exoclick.com",
    "juicyads.com", "adsterra.com", "revcontent.com", "mgid.com", "zedo.com", "yieldmo.com", "casalemedia.com",
    "advertising.com", "adcolony.com", "applovin.com", "unityads.unity3d.com", "ads.yahoo.com", "hotjar.com",
    "quantserve.com", "bidswitch.net", "mathtag.com", "contextweb.com", "sharethrough.com", "teads.tv"
  };

  private static class Tab {
    String id;
    WebView web;
    String url = "";
    String title = "";
  }

  private final LinkedHashMap<String, Tab> tabs = new LinkedHashMap<String, Tab>();
  private String active = null;
  private int seq = 0;
  private FrameLayout box; // holds all the web views, sits on top of the app page
  private FrameLayout full; // full screen video
  private View customView;
  private WebChromeClient.CustomViewCallback customCb;
  private OnBackPressedCallback backCb;
  private boolean shown = true;
  private boolean desktop = false;
  private boolean adblock = true;
  private int blocked = 0;
  private String[] blockHosts = new String[0];
  private String mobileUa = null;

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

  private static boolean hostMatches(String host, String base) {
    if (host == null || base == null) return false;
    return host.equals(base) || host.endsWith("." + base);
  }

  private boolean isAd(String host) {
    if (host == null) return false;
    for (int i = 0; i < AD_HOSTS.length; i++) {
      if (hostMatches(host, AD_HOSTS[i])) return true;
    }
    return false;
  }

  private boolean isBlockedSite(String host) {
    if (host == null) return false;
    String h = host.startsWith("www.") ? host.substring(4) : host;
    for (int i = 0; i < blockHosts.length; i++) {
      if (blockHosts[i].length() > 0 && hostMatches(h, blockHosts[i])) return true;
    }
    return false;
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

  private Tab cur() {
    return active == null ? null : tabs.get(active);
  }

  private void refreshBack() {
    if (backCb == null) return;
    Tab t = cur();
    boolean on = box != null && ((customView != null) || (shown && t != null && t.web != null && t.web.canGoBack()));
    backCb.setEnabled(on);
  }

  private void emitTabs() {
    JSArray arr = new JSArray();
    for (Map.Entry<String, Tab> e : tabs.entrySet()) {
      JSObject o = new JSObject();
      o.put("id", e.getKey());
      o.put("url", e.getValue().url);
      o.put("title", e.getValue().title);
      arr.put(o);
    }
    JSObject r = new JSObject();
    r.put("tabs", arr);
    r.put("active", active == null ? "" : active);
    notifyListeners("tabs", r);
  }

  private void page(Tab t, boolean done) {
    if (t == null || t.web == null || !t.id.equals(active)) return;
    JSObject o = new JSObject();
    o.put("id", t.id);
    o.put("url", t.url);
    o.put("title", t.title);
    o.put("canBack", t.web.canGoBack());
    o.put("canForward", t.web.canGoForward());
    o.put("blocked", blocked);
    notifyListeners(done ? "done" : "page", o);
    refreshBack();
  }

  private void applyUa(Tab t) {
    if (t == null || t.web == null) return;
    WebSettings s = t.web.getSettings();
    if (mobileUa == null) mobileUa = s.getUserAgentString();
    s.setUserAgentString(desktop ? DESKTOP_UA : mobileUa);
  }

  private WebView makeWeb(final Activity a, final Tab tab) {
    final WebView web = new WebView(a);
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
    s.setSupportMultipleWindows(true);
    s.setJavaScriptCanOpenWindowsAutomatically(true);
    if (mobileUa == null) mobileUa = s.getUserAgentString();
    s.setUserAgentString(desktop ? DESKTOP_UA : mobileUa);
    CookieManager.getInstance().setAcceptCookie(true);
    CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);

    web.setWebViewClient(new WebViewClient() {
      @Override
      public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
        Uri u = request.getUrl();
        String us = u.toString();
        if (!(us.startsWith("http://") || us.startsWith("https://") || us.startsWith("about:"))) return true;
        if (request.isForMainFrame() && isBlockedSite(u.getHost())) {
          JSObject o = new JSObject();
          o.put("url", us);
          notifyListeners("focusblock", o);
          return true;
        }
        return false;
      }

      @Override
      public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
        if (adblock && !request.isForMainFrame()) {
          String host = request.getUrl().getHost();
          if (isAd(host)) {
            blocked++;
            return new WebResourceResponse("text/plain", "utf-8", new ByteArrayInputStream(new byte[0]));
          }
        }
        return super.shouldInterceptRequest(view, request);
      }

      @Override
      public void onPageStarted(WebView view, String url, Bitmap favicon) {
        tab.url = url;
        if (tab.id.equals(active)) notifyListeners("start", new JSObject());
      }

      @Override
      public void doUpdateVisitedHistory(WebView view, String url, boolean isReload) {
        tab.url = url;
        page(tab, false);
        emitTabs();
      }

      @Override
      public void onPageFinished(WebView view, String url) {
        tab.url = url;
        String t = view.getTitle();
        if (t != null) tab.title = t;
        page(tab, true);
        emitTabs();
      }
    });
    web.setWebChromeClient(new WebChromeClient() {
      @Override
      public void onProgressChanged(WebView view, int newProgress) {
        if (tab.id.equals(active)) {
          JSObject o = new JSObject();
          o.put("p", newProgress);
          notifyListeners("progress", o);
        }
      }

      @Override
      public void onReceivedTitle(WebView view, String title) {
        if (title != null) {
          tab.title = title;
          emitTabs();
        }
      }

      @Override
      public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture, Message resultMsg) {
        // Pop-up blocker: only a real tap may open a new tab, and never past the tab limit.
        if (!isUserGesture || tabs.size() >= MAX_TABS || resultMsg == null) return false;
        try {
          Tab nt = addTab(a);
          WebView.WebViewTransport tr = (WebView.WebViewTransport) resultMsg.obj;
          tr.setWebView(nt.web);
          resultMsg.sendToTarget();
          activate(nt.id);
          return true;
        } catch (Exception e) {
          return false;
        }
      }

      @Override
      public void onShowCustomView(View v, WebChromeClient.CustomViewCallback cb) {
        if (customView != null) {
          cb.onCustomViewHidden();
          return;
        }
        customView = v;
        customCb = cb;
        ViewGroup content = (ViewGroup) a.findViewById(android.R.id.content);
        full = new FrameLayout(a);
        full.setBackgroundColor(Color.BLACK);
        full.setClickable(true);
        full.addView(v, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
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
    web.setOnLongClickListener(new View.OnLongClickListener() {
      @Override
      public boolean onLongClick(View v) {
        WebView.HitTestResult r = web.getHitTestResult();
        if (r != null && r.getType() == WebView.HitTestResult.SRC_ANCHOR_TYPE && r.getExtra() != null) {
          JSObject o = new JSObject();
          o.put("url", r.getExtra());
          notifyListeners("linkmenu", o);
          return true;
        }
        return false;
      }
    });
    return web;
  }

  /** Makes the box on first use. */
  private void ensureBox(Activity a) {
    if (box != null) return;
    ViewGroup parent = (ViewGroup) a.findViewById(android.R.id.content);
    box = new FrameLayout(a);
    box.setBackgroundColor(Color.parseColor("#101018"));
    box.setClickable(true);
    parent.addView(box, new FrameLayout.LayoutParams(10, 10));
    backCb = new OnBackPressedCallback(false) {
      @Override
      public void handleOnBackPressed() {
        Tab t = cur();
        if (customView != null) {
          hideCustom();
        } else if (t != null && t.web != null && t.web.canGoBack()) {
          t.web.goBack();
        }
      }
    };
    getActivity().getOnBackPressedDispatcher().addCallback(backCb);
  }

  private Tab addTab(Activity a) {
    ensureBox(a);
    Tab t = new Tab();
    seq++;
    t.id = "t" + seq;
    t.web = makeWeb(a, t);
    t.web.setVisibility(View.GONE);
    box.addView(t.web, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    tabs.put(t.id, t);
    return t;
  }

  private void activate(String id) {
    if (!tabs.containsKey(id)) return;
    active = id;
    for (Map.Entry<String, Tab> e : tabs.entrySet()) {
      e.getValue().web.setVisibility(e.getKey().equals(id) ? View.VISIBLE : View.GONE);
    }
    emitTabs();
    page(tabs.get(id), true);
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

  private void destroyTab(Tab t) {
    try {
      box.removeView(t.web);
    } catch (Exception e) {
      // already gone
    }
    t.web.loadUrl("about:blank");
    t.web.destroy();
  }

  private void closeNow() {
    if (box == null) return;
    hideCustom();
    if (backCb != null) {
      backCb.remove();
      backCb = null;
    }
    List<Tab> all = new ArrayList<Tab>(tabs.values());
    for (int i = 0; i < all.size(); i++) destroyTab(all.get(i));
    tabs.clear();
    active = null;
    try {
      ((ViewGroup) box.getParent()).removeView(box);
    } catch (Exception e) {
      // already gone
    }
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
    if (call.hasOption("desktop")) desktop = call.getBoolean("desktop", false);
    if (call.hasOption("adblock")) adblock = call.getBoolean("adblock", true);
    a.runOnUiThread(new Runnable() {
      @Override
      public void run() {
        try {
          if (cur() == null) {
            Tab t = addTab(a);
            activate(t.id);
          }
          place(a, x, y, w, h, true);
          cur().web.loadUrl(url);
          call.resolve();
        } catch (Exception e) {
          call.reject("Could not open the browser: " + e.getMessage());
        }
      }
    });
  }

  @PluginMethod
  public void newTab(final PluginCall call) {
    final String url = fix(call.getString("url", ""));
    final Activity a = getActivity();
    a.runOnUiThread(new Runnable() {
      @Override
      public void run() {
        try {
          if (box == null) {
            call.reject("Browser is not open");
            return;
          }
          if (tabs.size() >= MAX_TABS) {
            call.reject("Too many tabs (" + MAX_TABS + " is the most). Close one first.");
            return;
          }
          Tab t = addTab(a);
          activate(t.id);
          t.web.loadUrl(url);
          JSObject r = new JSObject();
          r.put("id", t.id);
          call.resolve(r);
        } catch (Exception e) {
          call.reject("Could not open a new tab: " + e.getMessage());
        }
      }
    });
  }

  @PluginMethod
  public void switchTab(final PluginCall call) {
    final String id = call.getString("id", "");
    getActivity().runOnUiThread(new Runnable() {
      @Override
      public void run() {
        activate(id);
        call.resolve();
      }
    });
  }

  @PluginMethod
  public void closeTab(final PluginCall call) {
    final String id = call.getString("id", "");
    getActivity().runOnUiThread(new Runnable() {
      @Override
      public void run() {
        Tab t = tabs.get(id);
        if (t == null) {
          call.resolve();
          return;
        }
        if (tabs.size() <= 1) {
          closeNow();
          call.resolve();
          return;
        }
        // pick the neighbour before this one (or the next one)
        List<String> ids = new ArrayList<String>(tabs.keySet());
        int i = ids.indexOf(id);
        String next = i > 0 ? ids.get(i - 1) : ids.get(1);
        tabs.remove(id);
        destroyTab(t);
        if (id.equals(active)) activate(next);
        else emitTabs();
        call.resolve();
      }
    });
  }

  @PluginMethod
  public void go(final PluginCall call) {
    final String url = fix(call.getString("url", ""));
    getActivity().runOnUiThread(new Runnable() {
      @Override
      public void run() {
        Tab t = cur();
        if (t == null) {
          call.reject("Browser is not open");
          return;
        }
        t.web.loadUrl(url);
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
        Tab t = cur();
        if (t != null && t.web.canGoBack()) t.web.goBack();
        call.resolve();
      }
    });
  }

  @PluginMethod
  public void forward(final PluginCall call) {
    getActivity().runOnUiThread(new Runnable() {
      @Override
      public void run() {
        Tab t = cur();
        if (t != null && t.web.canGoForward()) t.web.goForward();
        call.resolve();
      }
    });
  }

  @PluginMethod
  public void reload(final PluginCall call) {
    getActivity().runOnUiThread(new Runnable() {
      @Override
      public void run() {
        Tab t = cur();
        if (t != null) t.web.reload();
        call.resolve();
      }
    });
  }

  /** Runs a small piece of JavaScript in the page that is showing and gives back what it returned (as text). */
  @PluginMethod
  public void eval(final PluginCall call) {
    final String code = call.getString("code", "");
    getActivity().runOnUiThread(new Runnable() {
      @Override
      public void run() {
        Tab t = cur();
        if (t == null) {
          call.reject("Browser is not open");
          return;
        }
        t.web.evaluateJavascript(code, new ValueCallback<String>() {
          @Override
          public void onReceiveValue(String value) {
            JSObject r = new JSObject();
            r.put("value", value == null ? "null" : value);
            call.resolve(r);
          }
        });
      }
    });
  }

  /** desktop = ask sites for their computer version, adblock = skip known ad and tracker hosts. */
  @PluginMethod
  public void setOptions(final PluginCall call) {
    final boolean wasDesktop = desktop;
    if (call.hasOption("desktop")) desktop = call.getBoolean("desktop", false);
    if (call.hasOption("adblock")) adblock = call.getBoolean("adblock", true);
    final boolean changed = wasDesktop != desktop;
    getActivity().runOnUiThread(new Runnable() {
      @Override
      public void run() {
        for (Tab t : tabs.values()) applyUa(t);
        Tab c = cur();
        if (changed && c != null) c.web.reload();
        JSObject r = new JSObject();
        r.put("blocked", blocked);
        call.resolve(r);
      }
    });
  }

  /** Websites (comma separated) that Focus mode keeps closed. */
  @PluginMethod
  public void setBlock(final PluginCall call) {
    String s = call.getString("hosts", "");
    blockHosts = s.length() == 0 ? new String[0] : s.toLowerCase().split(",");
    call.resolve();
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
