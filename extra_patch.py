"""Runs in the cloud right after patch_android.py. Adds the in-app browser plugin to the Android project."""
import os, shutil

ROOT = os.getcwd()
jdir = os.path.join(ROOT, 'android', 'app', 'src', 'main', 'java', 'com', 'flambounds', 'blitz')
os.makedirs(jdir, exist_ok=True)
shutil.copy(os.path.join(ROOT, 'BlitzBrowserPlugin.java'), os.path.join(jdir, 'BlitzBrowserPlugin.java'))
open(os.path.join(jdir, 'MainActivity.java'), 'w', encoding='utf-8').write('''package com.flambounds.blitz;

import android.os.Bundle;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
  @Override
  public void onCreate(Bundle savedInstanceState) {
    registerPlugin(ImmersivePlugin.class);
    registerPlugin(BlitzBrowserPlugin.class);
    super.onCreate(savedInstanceState);
  }
}
''')
print('browser plugin added')
