/*
 ============================================================================
 Name        : WifiWatchService.java
 Author      : xyz
 Copyright   : Copyright (c) 2026 xyz
 Description : Wi-Fi watch service
 ============================================================================
 */

package hev.sockstun;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.core.app.NotificationCompat;

/* Watches the Wi-Fi we are connected to and switches the tunnel to the
   profile bound to it. The service is a foreground service so that it keeps
   running in the background and so that it is allowed to start the VPN
   service on Android 12 and later. */
public class WifiWatchService extends Service {
	public static final String ACTION_START = "hev.sockstun.WIFI_WATCH_START";
	public static final String ACTION_STOP = "hev.sockstun.WIFI_WATCH_STOP";

	private static final String TAG = "SocksTun";
	private static final String CHANNEL = "wifi_watch";
	/* The tunnel runs in its own process which exits when it is stopped.
	   Wait for that process to die before starting a new one. */
	private static final long RESTART_DELAY_MS = 800;

	private ConnectivityManager cm;
	private ConnectivityManager.NetworkCallback callback;
	private final Handler handler = new Handler(Looper.getMainLooper());
	private String currentSsid;
	private boolean switching;

	@Override
	public void onCreate() {
		super.onCreate();
		cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
	}

	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		if (intent != null && ACTION_STOP.equals(intent.getAction())) {
			stopSelf();
			return START_NOT_STICKY;
		}

		/* The service is sticky, so it may be restarted by the system after
		   the app process was killed. Do not run when the feature has been
		   turned off in the meantime. */
		if (!new Preferences(this).getWifiEnabled()) {
			stopSelf();
			return START_NOT_STICKY;
		}

		startForegroundCompat();
		registerCallback();

		/* The service may be started long after the Wi-Fi has changed,
		   so evaluate the current network right away. */
		handler.post(new Runnable() {
			@Override
			public void run() {
				onSsidChanged(WifiUtils.getCurrentSsid(WifiWatchService.this));
			}
		});

		return START_STICKY;
	}

	@Override
	public void onDestroy() {
		if (cm != null && callback != null) {
			try {
				cm.unregisterNetworkCallback(callback);
			} catch (Exception e) {
			}
			callback = null;
		}
		super.onDestroy();
	}

	@Override
	public IBinder onBind(Intent intent) {
		return null;
	}

	private void registerCallback() {
		if (callback != null || cm == null)
		  return;

		callback = new ConnectivityManager.NetworkCallback() {
			@Override
			public void onCapabilitiesChanged(Network network,
					NetworkCapabilities caps) {
				String ssid = WifiUtils.ssidFromCapabilities(caps);
				Log.d(TAG, "onCapabilitiesChanged ssid = " + ssid);
				postSsid(ssid);
			}

			@Override
			public void onAvailable(Network network) {
				/* The capabilities are not complete yet, read the SSID
				   directly a moment later. */
				handler.postDelayed(new Runnable() {
					@Override
					public void run() {
						postSsid(WifiUtils.getCurrentSsid(WifiWatchService.this));
					}
				}, 1000);
			}

			@Override
			public void onLost(Network network) {
				postSsid(null);
			}
		};

		/* A Wi-Fi specific request is required: while the tunnel is running
		   the default network is the VPN, and a default network callback
		   would never report the Wi-Fi network. */
		NetworkRequest request = new NetworkRequest.Builder()
			.addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
			.build();
		try {
			cm.registerNetworkCallback(request, callback);
		} catch (Exception e) {
			Log.w(TAG, "registerNetworkCallback failed", e);
		}
	}

	private void postSsid(final String ssid) {
		handler.post(new Runnable() {
			@Override
			public void run() {
				onSsidChanged(ssid);
			}
		});
	}

	private void onSsidChanged(String ssid) {
		if (switching)
		  return;

		/* The SSID flaps while roaming between access points of the same
		   network. Only react when it really changed. */
		if (ssid == null ? currentSsid == null : ssid.equals(currentSsid))
		  return;

		currentSsid = ssid;
		applyProfile(ssid);
	}

	private void applyProfile(String ssid) {
		Preferences prefs = new Preferences(this);

		/* The default profile applies to Wi-Fi networks that have no
		   explicit binding. When we are not on Wi-Fi at all (mobile data,
		   Wi-Fi turned off) nothing is switched: leaving a bound network
		   keeps the profile until another one is joined. */
		if (ssid == null)
		  return;

		int target = prefs.getWifiProfile(ssid);
		boolean explicit = target >= 0;
		if (!explicit)
		  target = prefs.getWifiDefault();
		if (target < 0 || target >= prefs.getProfileCount())
		  return;

		if (!prefs.getEnable()) {
			/* The tunnel is not running. Only start it when the current
			   Wi-Fi has an explicit binding. */
			if (explicit && prefs.getWifiAutoStart())
			  startTunnel();
			return;
		}

		if (target == prefs.getSelected())
		  return;

		/* When the VPN scope (IP family, per-app list) differs between the
		   two profiles the tunnel has to be rebuilt. Otherwise the native
		   tunnel can be restarted on the same TUN fd, which keeps the
		   connection alive. */
		boolean scopeChanged = !prefs.sameVpnScope(prefs.getSelected(), target);
		prefs.setSelected(target);

		if (scopeChanged)
		  restartTunnel();
		else
		  reloadTunnel();
	}

	private void reloadTunnel() {
		Intent intent = new Intent(this, TProxyService.class);
		intent.setAction(TProxyService.ACTION_RELOAD);
		startService(intent);
	}

	private void restartTunnel() {
		switching = true;

		Intent intent = new Intent(this, TProxyService.class);
		intent.setAction(TProxyService.ACTION_DISCONNECT);
		startService(intent);

		handler.postDelayed(new Runnable() {
			@Override
			public void run() {
				switching = false;
				startTunnel();
			}
		}, RESTART_DELAY_MS);
	}

	private void startTunnel() {
		Intent intent = new Intent(this, TProxyService.class);
		intent.setAction(TProxyService.ACTION_CONNECT);
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
		  startForegroundService(intent);
		else
		  startService(intent);
	}

	private void startForegroundCompat() {
		NotificationManager nm = (NotificationManager)
			getSystemService(Context.NOTIFICATION_SERVICE);
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			CharSequence name = getString(R.string.wifi_watch_channel);
			NotificationChannel channel = new NotificationChannel(CHANNEL, name,
				NotificationManager.IMPORTANCE_LOW);
			nm.createNotificationChannel(channel);
		}

		Intent i = new Intent(this, MainActivity.class);
		i.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
		PendingIntent pi = PendingIntent.getActivity(this, 0, i,
			PendingIntent.FLAG_IMMUTABLE);
		NotificationCompat.Builder notification =
			new NotificationCompat.Builder(this, CHANNEL);
		Notification notify = notification
				.setContentTitle(getString(R.string.app_name))
				.setContentText(getString(R.string.wifi_watch_running))
				.setSmallIcon(android.R.drawable.sym_def_app_icon)
				.setContentIntent(pi)
				.setOngoing(true)
				.build();
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
			/* The location foreground service type is required: reading the
			   SSID counts as a location access, and Android only hands it
			   out in the foreground (a visible activity or a foreground
			   service of this type). */
			startForeground(2, notify,
				ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
		} else {
			startForeground(2, notify);
		}
	}
}
