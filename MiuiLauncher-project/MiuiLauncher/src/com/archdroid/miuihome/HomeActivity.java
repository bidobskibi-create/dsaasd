package com.archdroid.miuihome;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.WindowManager;
import android.widget.Toast;

/** MIUI-style home screen. Singleton task; pressing Home brings you back to the first page. */
public class HomeActivity extends Activity implements HomeView.Host {
    private HomeView view;
    private BroadcastReceiver pkgRx, timeRx;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS);
        view = new HomeView(this, this);
        setContentView(view);

        IntentFilter pf = new IntentFilter();
        pf.addAction(Intent.ACTION_PACKAGE_ADDED);
        pf.addAction(Intent.ACTION_PACKAGE_REMOVED);
        pf.addAction(Intent.ACTION_PACKAGE_CHANGED);
        pf.addDataScheme("package");
        pkgRx = new BroadcastReceiver() {
            public void onReceive(Context c, Intent i) { view.reload(); }
        };
        registerReceiver(pkgRx, pf);

        IntentFilter tf = new IntentFilter(Intent.ACTION_TIME_TICK);
        tf.addAction(Intent.ACTION_TIME_CHANGED);
        tf.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        timeRx = new BroadcastReceiver() {
            public void onReceive(Context c, Intent i) { view.tickClock(); }
        };
        registerReceiver(timeRx, tf);

        view.reload();
    }

    @Override protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        view.goHome();
    }

    @Override protected void onResume() {
        super.onResume();
        view.tickClock();
    }

    @Override public void onBackPressed() {
        view.goHome();   // a launcher never closes
    }

    @Override protected void onDestroy() {
        try { unregisterReceiver(pkgRx); } catch (Throwable t) { }
        try { unregisterReceiver(timeRx); } catch (Throwable t) { }
        super.onDestroy();
    }

    // ---- HomeView.Host

    public void launch(App a) {
        try {
            Intent i = new Intent(a.intent);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            startActivity(i);
        } catch (Throwable t) {
            Toast.makeText(this, "Can't open " + a.label, Toast.LENGTH_SHORT).show();
        }
    }

    public void appMenu(final App a) {
        new AlertDialog.Builder(this)
                .setTitle(a.label)
                .setItems(new CharSequence[]{"App info", "Uninstall"}, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        try {
                            Uri u = Uri.parse("package:" + a.pkg);
                            if (which == 0) {
                                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, u));
                            } else {
                                startActivity(new Intent(Intent.ACTION_DELETE, u));
                            }
                        } catch (Throwable t) {
                            Toast.makeText(HomeActivity.this, "Not available", Toast.LENGTH_SHORT).show();
                        }
                    }
                }).show();
    }

    public void emptyMenu() {
        new AlertDialog.Builder(this)
                .setItems(new CharSequence[]{"Wallpaper", "System settings"}, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        try {
                            if (which == 0) {
                                startActivity(Intent.createChooser(new Intent(Intent.ACTION_SET_WALLPAPER), "Wallpaper"));
                            } else {
                                startActivity(new Intent(Settings.ACTION_SETTINGS));
                            }
                        } catch (Throwable t) {
                            Toast.makeText(HomeActivity.this, "Not available", Toast.LENGTH_SHORT).show();
                        }
                    }
                }).show();
    }
}
