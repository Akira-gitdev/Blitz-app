"""Optional: adds the on-phone model plugin. Runs after extra_patch.py. If BlitzLLMPlugin.java is missing it does nothing."""
import os, re, shutil

ROOT = os.getcwd()
src = os.path.join(ROOT, 'BlitzLLMPlugin.java')
if not os.path.exists(src):
    print('no BlitzLLMPlugin.java - skipping on-phone model')
    raise SystemExit(0)

AND = os.path.join(ROOT, 'android')
jdir = os.path.join(AND, 'app', 'src', 'main', 'java', 'com', 'flambounds', 'blitz')
shutil.copy(src, os.path.join(jdir, 'BlitzLLMPlugin.java'))

ma = os.path.join(jdir, 'MainActivity.java')
t = open(ma, encoding='utf-8').read()
if 'BlitzLLMPlugin' not in t:
    t = t.replace('super.onCreate(savedInstanceState);', 'registerPlugin(BlitzLLMPlugin.class);\n    super.onCreate(savedInstanceState);', 1)
    open(ma, 'w', encoding='utf-8').write(t)

vg = os.path.join(AND, 'variables.gradle')
if os.path.exists(vg):
    v = open(vg, encoding='utf-8').read()
    v = re.sub(r'minSdkVersion\s*=\s*\d+', 'minSdkVersion = 26', v)
    open(vg, 'w', encoding='utf-8').write(v)

bg = os.path.join(AND, 'app', 'build.gradle')
b = open(bg, encoding='utf-8').read()
if 'tasks-genai' not in b:
    b = b.replace('dependencies {', "dependencies {\n    implementation 'com.google.mediapipe:tasks-genai:0.10.+'", 1)
    open(bg, 'w', encoding='utf-8').write(b)

man = os.path.join(AND, 'app', 'src', 'main', 'AndroidManifest.xml')
m = open(man, encoding='utf-8').read()
if 'largeHeap' not in m:
    m = m.replace('<application', '<application android:largeHeap="true"', 1)
    open(man, 'w', encoding='utf-8').write(m)
print('on-phone model plugin added')
