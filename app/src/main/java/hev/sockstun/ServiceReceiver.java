/*
 ============================================================================
 Name        : ServiceReceiver.java
 Author      : hev <r@hev.cc>
 Copyright   : Copyright (c) 2023 xyz
 Description : ServiceReceiver
 ============================================================================
 */

package hev.sockstun;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.VpnService;
import android.os.Build;
import android.util.Log;

public class ServiceReceiver extends BroadcastReceiver {
	@Override
	public void onReceive(Context context, Intent intent) {
		if (intent.getAction().equals(Intent.ACTION_BOOT_COMPLETED)) {
			Preferences prefs = new Preferences(context);

			/* Auto-start */
			if (prefs.getEnable()) {
				Intent i = VpnService.prepare(context);
				if (i != null) {
					i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
					context.startActivity(i);
				}
				i = new Intent(context, TProxyService.class);
				if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
					context.startForegroundService(i.setAction(TProxyService.ACTION_CONNECT));
				} else {
					context.startService(i.setAction(TProxyService.ACTION_CONNECT));
				}
			}

			/* Wi-Fi watcher */
			if (prefs.getWifiEnabled()) {
				Intent w = new Intent(context, WifiWatchService.class);
				w.setAction(WifiWatchService.ACTION_START);
				try {
					if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
						context.startForegroundService(w);
					} else {
						context.startService(w);
					}
				} catch (Exception e) {
					/* A foreground service of type location may not be
					   started from the background on Android 14 and later.
					   It is started again when the app is opened. */
					Log.w("SocksTun", "start WifiWatchService failed", e);
				}
			}
		}
	}
}
