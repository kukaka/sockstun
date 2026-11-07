/*
 ============================================================================
 Name        : WifiUtils.java
 Author      : xyz
 Copyright   : Copyright (c) 2026 xyz
 Description : Wi-Fi helpers
 ============================================================================
 */

package hev.sockstun;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.location.LocationManager;
import android.os.Build;
import android.util.Log;

public class WifiUtils {
	private static final String TAG = "SocksTun";

	/* Value returned by WifiInfo.getSSID() when the app is not allowed to
	   read the SSID. */
	public static final String SSID_UNKNOWN = "<unknown ssid>";

	/* Returns the SSID of the Wi-Fi network we are currently connected to,
	   or null if it cannot be determined (no Wi-Fi, or the permission to
	   read the SSID has not been granted). */
	public static String getCurrentSsid(Context context) {
		Context app = context.getApplicationContext();

		/* Note: the default network is the VPN while the tunnel is running,
		   so the Wi-Fi network has to be looked up explicitly. */
		String ssid = fromNetworks(app);
		if (ssid != null)
		  return ssid;

		/* Fallback */
		try {
			WifiManager wm = (WifiManager)
				app.getSystemService(Context.WIFI_SERVICE);
			if (wm != null) {
				String raw = wm.getConnectionInfo().getSSID();
				Log.d(TAG, "WifiManager.getSSID() = \"" + raw + "\"");
				return normalize(raw);
			}
		} catch (SecurityException e) {
			Log.w(TAG, "getConnectionInfo denied", e);
		}
		return null;
	}

	/* Look for the Wi-Fi network among all networks. The default network
	   cannot be used: it is the VPN as soon as the tunnel is running. */
	private static String fromNetworks(Context app) {
		ConnectivityManager cm = (ConnectivityManager)
			app.getSystemService(Context.CONNECTIVITY_SERVICE);
		if (cm == null)
		  return null;

		try {
			for (Network network : cm.getAllNetworks()) {
				NetworkCapabilities caps = cm.getNetworkCapabilities(network);
				if (caps == null ||
				    !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))
				  continue;
				String ssid = ssidFromCapabilities(caps);
				if (ssid != null)
				  return ssid;
			}
		} catch (SecurityException e) {
			Log.w(TAG, "getAllNetworks denied", e);
		}
		return null;
	}

	/* Extract the SSID from the transport info of a network (API 29+). */
	public static String ssidFromCapabilities(NetworkCapabilities caps) {
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q)
		  return null;
		try {
			Object ti = caps.getTransportInfo();
			if (!(ti instanceof WifiInfo))
			  return null;
			String raw = ((WifiInfo) ti).getSSID();
			Log.d(TAG, "transportInfo.getSSID() = \"" + raw + "\"");
			return normalize(raw);
		} catch (SecurityException e) {
			Log.w(TAG, "getTransportInfo denied", e);
			return null;
		}
	}

	/* getSSID() returns the SSID wrapped in double quotes, and
	   "<unknown ssid>" when the app lacks the permission to read it.
	   Normalize both into a plain SSID, or null when unavailable. */
	public static String normalize(String ssid) {
		if (ssid == null)
		  return null;

		ssid = ssid.trim();
		if (ssid.length() >= 2 &&
		    ssid.charAt(0) == '"' && ssid.charAt(ssid.length() - 1) == '"')
		  ssid = ssid.substring(1, ssid.length() - 1).trim();

		if (ssid.isEmpty())
		  return null;
		if (ssid.equals(SSID_UNKNOWN) || ssid.equalsIgnoreCase("unknown ssid"))
		  return null;
		if (ssid.equals("0"))
		  return null;
		return ssid;
	}

	/* Whether the app is allowed to read the SSID.
	   The SSID counts as location information, so ACCESS_FINE_LOCATION is
	   the permission that actually matters. NEARBY_WIFI_DEVICES is accepted
	   as well because it is enough on some builds. */
	public static boolean hasSsidPermission(Context context) {
		if (isGranted(context, android.Manifest.permission.ACCESS_FINE_LOCATION))
		  return true;
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
		    isGranted(context, android.Manifest.permission.NEARBY_WIFI_DEVICES))
		  return true;
		return false;
	}

	/* The permissions that have to be granted to read the SSID.
	   The SSID counts as location information, so ACCESS_FINE_LOCATION is
	   the permission that actually unlocks it. NEARBY_WIFI_DEVICES covers
	   "connect to a known SSID" and is kept as a fallback for builds that
	   gate getConnectionInfo() behind it. */
	public static String[] ssidPermissions() {
		return new String[] {
			android.Manifest.permission.ACCESS_FINE_LOCATION };
	}

	/* Whether the location services of the device are turned on. The SSID
	   stays redacted even with the permission granted when they are off. */
	public static boolean isLocationEnabled(Context context) {
		LocationManager lm = (LocationManager)
			context.getApplicationContext().getSystemService(Context.LOCATION_SERVICE);
		if (lm == null)
		  return false;
		try {
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
			  return lm.isLocationEnabled();
			return lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
			       lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
		} catch (Exception e) {
			return false;
		}
	}

	private static boolean isGranted(Context context, String permission) {
		return context.checkSelfPermission(permission) ==
			android.content.pm.PackageManager.PERMISSION_GRANTED;
	}
}
