package com.archdroid.miuihome;

import android.content.Intent;
import android.graphics.Bitmap;

/** One launchable app: label, package, launch intent and a ready-made rounded icon. */
final class App {
    String label = "";
    String shortLabel = "";
    String pkg = "";
    Intent intent;
    Bitmap icon;
}
