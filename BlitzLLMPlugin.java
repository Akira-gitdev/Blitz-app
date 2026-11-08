package com.flambounds.blitz;

import android.content.Context;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.google.mediapipe.tasks.genai.llminference.LlmInference;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** Optional offline model for Blitz. Downloads one model file, loads it with MediaPipe, answers prompts. */
@CapacitorPlugin(name = "BlitzLLM")
public class BlitzLLMPlugin extends Plugin {
  private LlmInference engine;
  private final Object lock = new Object();

  private File modelFile() {
    return new File(getContext().getFilesDir(), "blitz_model.task");
  }

  @PluginMethod
  public void download(final PluginCall call) {
    final String link = call.getString("url", "");
    final String token = call.getString("token", "");
    if (link == null || link.isEmpty()) { call.reject("No link given"); return; }
    new Thread(new Runnable() {
      @Override public void run() {
        try {
          File tmp = new File(getContext().getFilesDir(), "blitz_model.part");
          HttpURLConnection c = (HttpURLConnection) new URL(link).openConnection();
          c.setInstanceFollowRedirects(true);
          if (token != null && !token.isEmpty()) c.setRequestProperty("Authorization", "Bearer " + token);
          c.setConnectTimeout(20000);
          c.setReadTimeout(30000);
          int code = c.getResponseCode();
          if (code >= 300 && code < 400) {
            String loc = c.getHeaderField("Location");
            c.disconnect();
            c = (HttpURLConnection) new URL(loc).openConnection();
            c.setConnectTimeout(20000);
            c.setReadTimeout(30000);
            code = c.getResponseCode();
          }
          if (code != 200) { call.reject("Download failed (HTTP " + code + "). Some models need a Hugging Face token."); return; }
          long total = c.getContentLengthLong();
          InputStream in = c.getInputStream();
          FileOutputStream out = new FileOutputStream(tmp);
          byte[] buf = new byte[1 << 16];
          long got = 0; long lastPing = 0; int n;
          while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
            got += n;
            long now = System.currentTimeMillis();
            if (total > 0 && now - lastPing > 700) {
              lastPing = now;
              JSObject p = new JSObject();
              p.put("pct", (got * 100.0) / total);
              notifyListeners("llmProgress", p);
            }
          }
          out.close(); in.close();
          File dst = modelFile();
          if (dst.exists()) dst.delete();
          if (!tmp.renameTo(dst)) { call.reject("Could not save the model file"); return; }
          synchronized (lock) { if (engine != null) { try { engine.close(); } catch (Exception e) {} engine = null; } }
          JSObject r = new JSObject();
          r.put("name", link.substring(link.lastIndexOf('/') + 1).split("\\?")[0]);
          r.put("bytes", got);
          call.resolve(r);
        } catch (Throwable e) {
          call.reject("Download error: " + e.getMessage());
        }
      }
    }).start();
  }

  @PluginMethod
  public void load(final PluginCall call) {
    new Thread(new Runnable() {
      @Override public void run() {
        try {
          synchronized (lock) {
            if (engine == null) {
              File f = modelFile();
              if (!f.exists()) { call.reject("No model downloaded yet"); return; }
              LlmInference.LlmInferenceOptions o = LlmInference.LlmInferenceOptions.builder()
                  .setModelPath(f.getAbsolutePath())
                  .setMaxTokens(1024)
                  .build();
              engine = LlmInference.createFromOptions(getContext(), o);
            }
          }
          call.resolve();
        } catch (Throwable e) {
          call.reject("Could not load the model: " + e.getMessage());
        }
      }
    }).start();
  }

  @PluginMethod
  public void generate(final PluginCall call) {
    final String prompt = call.getString("prompt", "");
    new Thread(new Runnable() {
      @Override public void run() {
        try {
          String text;
          synchronized (lock) {
            if (engine == null) {
              File f = modelFile();
              if (!f.exists()) { call.reject("No model downloaded yet"); return; }
              LlmInference.LlmInferenceOptions o = LlmInference.LlmInferenceOptions.builder()
                  .setModelPath(f.getAbsolutePath())
                  .setMaxTokens(1024)
                  .build();
              engine = LlmInference.createFromOptions(getContext(), o);
            }
            text = engine.generateResponse(prompt);
          }
          JSObject r = new JSObject();
          r.put("text", text == null ? "" : text);
          call.resolve(r);
        } catch (Throwable e) {
          call.reject("Model error: " + e.getMessage());
        }
      }
    }).start();
  }

  @PluginMethod
  public void remove(PluginCall call) {
    synchronized (lock) { if (engine != null) { try { engine.close(); } catch (Exception e) {} engine = null; } }
    File f = modelFile();
    if (f.exists()) f.delete();
    call.resolve();
  }
}
