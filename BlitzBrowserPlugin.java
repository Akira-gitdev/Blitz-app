package com.flambounds.blitz;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.net.Uri;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import androidx.activity.OnBackPressedCallback;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

/** A real browser inside Blitz: opens on top of the app, with back, forward, reload, an address bar and full screen video. */
@CapacitorPlugin(name = "BlitzBrowser")
public class BlitzBrowserPlugin extends Plugin {

  private FrameLayout root;
  private LinearLayout column;
  private WebView web;
  private EditText addr;
  private ProgressBar bar;
  private View customView;
  private WebChromeClient.CustomViewCallback customCb;
  private OnBackPressedCallback backCb;

  private int dp(Context c, int v) {
    return (int) (v * c.getResources().getDisplayMetrics().density + 0.5f);
  }

  private String fix(String raw) {
    String t = raw == null ? "" : raw.trim();
    if (t.length() == 0) return "about:blank";
    if (t.startsWith("http://") || t.startsWith("https://")) return t;
    if (t.indexOf(' ') < 0 && t.indexOf('.') > 0) return "https://" + t;
    return "https://duckduckgo.com/?q=" + Uri.encode(t);
  }

  private TextView button(final Activity a, String label, View.OnClickListener l) {
    TextView b = new TextView(a);
    b.setText(label);
    b.setTextColor(Color.WHITE);
    b.setTextSize(18f);
    b.setGravity(Gravity.CENTER);
    b.setPadding(dp(a, 12), dp(a, 8), dp(a, 12), dp(a, 8));
    b.setOnClickListener(l);
    return b;
  }

  private void build(final Activity a) {
    if (root != null) return;
    ViewGroup content = (ViewGroup) a.findViewById(android.R.id.content);
    root = new FrameLayout(a);
    root.setBackgroundColor(Color.BLACK);
    root.setClickable(true);
    root.setFitsSystemWindows(true);

    column = new LinearLayout(a);
    column.setOrientation(LinearLayout.VERTICAL);
    root.addView(column, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

    LinearLayout tb = new LinearLayout(a);
    tb.setOrientation(LinearLayout.HORIZONTAL);
    tb.setGravity(Gravity.CENTER_VERTICAL);
    tb.setBackgroundColor(Color.parseColor("#1B1830"));
    tb.setPadding(dp(a, 4), dp(a, 2), dp(a, 4), dp(a, 2));
    tb.addView(button(a, "◀", new View.OnClickListener() {
      @Override
      public void onClick(View v) {
        if (web != null && web.canGoBack()) web.goBack();
      }
    }));
    tb.addView(button(a, "▶", new View.OnClickListener() {
      @Override
      public void onClick(View v) {
        if (web != null && web.canGoForward()) web.goForward();
      }
    }));
    tb.addView(button(a, "⟳", new View.OnClickListener() {
      @Override
      public void onClick(View v) {
        if (web != null) web.reload();
      }
    }));
    addr = new EditText(a);
    addr.setSingleLine(true);
    addr.setTextColor(Color.WHITE);
    addr.setHintTextColor(Color.GRAY);
    addr.setHint("Search or type a link");
    addr.setTextSize(14f);
    addr.setImeOptions(EditorInfo.IME_ACTION_GO);
    addr.setSelectAllOnFocus(true);
    addr.setOnEditorActionListener(new TextView.OnEditorActionListener() {
      @Override
      public boolean onEditorAction(TextView v, int actionId, android.view.KeyEvent event) {
        String u = fix(addr.getText().toString());
        if (web != null) web.loadUrl(u);
        InputMethodManager imm = (InputMethodManager) a.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(addr.getWindowToken(), 0);
        addr.clearFocus();
        return true;
      }
    });
    tb.addView(addr, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
    tb.addView(button(a, "✕", new View.OnClickListener() {
      @Override
      public void onClick(View v) {
        closeNow();
      }
    }));
    column.addView(tb, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

    bar = new ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal);
    bar.setMax(100);
    column.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(a, 3)));

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
      public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
        bar.setVisibility(View.VISIBLE);
        if (!addr.hasFocus()) addr.setText(url);
      }

      @Override
      public void onPageFinished(WebView view, String url) {
        bar.setVisibility(View.GONE);
        if (!addr.hasFocus()) addr.setText(url);
        JSObject o = new JSObject();
        o.put("url", url);
        o.put("title", view.getTitle());
        notifyListeners("page", o);
      }
    });
    web.setWebChromeClient(new WebChromeClient() {
      @Override
      public void onProgressChanged(WebView view, int newProgress) {
        bar.setProgress(newProgress);
      }

      @Override
      public void onShowCustomView(View view, WebChromeClient.CustomViewCallback cb) {
        if (customView != null) {
          cb.onCustomViewHidden();
          return;
        }
        customView = view;
        customCb = cb;
        column.setVisibility(View.GONE);
        root.addView(view, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
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
    column.addView(web, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

    content.addView(root, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

    backCb = new OnBackPressedCallback(true) {
      @Override
      public void handleOnBackPressed() {
        if (customView != null) {
          hideCustom();
        } else if (web != null && web.canGoBack()) {
          web.goBack();
        } else {
          closeNow();
        }
      }
    };
    getActivity().getOnBackPressedDispatcher().addCallback(backCb);
  }

  private void hideCustom() {
    if (customView == null) return;
    root.removeView(customView);
    customView = null;
    if (customCb != null) customCb.onCustomViewHidden();
    customCb = null;
    column.setVisibility(View.VISIBLE);
  }

  private void closeNow() {
    if (root == null) return;
    hideCustom();
    if (backCb != null) {
      backCb.remove();
      backCb = null;
    }
    try {
      ((ViewGroup) root.getParent()).removeView(root);
    } catch (Exception e) {
      // already gone
    }
    if (web != null) {
      web.loadUrl("about:blank");
      web.destroy();
    }
    web = null;
    root = null;
    column = null;
    addr = null;
    bar = null;
    notifyListeners("closed", new JSObject());
  }

  @PluginMethod
  public void open(final PluginCall call) {
    final String url = fix(call.getString("url", ""));
    final Activity a = getActivity();
    a.runOnUiThread(new Runnable() {
      @Override
      public void run() {
        try {
          build(a);
          web.loadUrl(url);
          call.resolve();
        } catch (Exception e) {
          call.reject("Could not open the browser: " + e.getMessage());
        }
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
