/*
 ============================================================================
 Name        : WifiBindingActivity.java
 Author      : xyz
 Copyright   : Copyright (c) 2026 xyz
 Description : Wi-Fi binding activity
 ============================================================================
 */

package hev.sockstun;

import java.util.List;
import java.util.ArrayList;
import java.util.Map;

import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.LayoutInflater;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;
import android.content.Context;
import android.content.DialogInterface;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.color.DynamicColors;

/* Manage the mapping between Wi-Fi networks and profiles. */
public class WifiBindingActivity extends AppCompatActivity {
	private Preferences prefs;
	private BindingAdapter adapter;
	private ListView listView;
	private TextView textview_current;
	private String currentSsid;

	private static class Binding {
		public String ssid;
		public int profile;

		public Binding(String ssid, int profile) {
			this.ssid = ssid;
			this.profile = profile;
		}
	}

	private class BindingAdapter extends ArrayAdapter<Binding> {
		private final List<Binding> items = new ArrayList<Binding>();

		public BindingAdapter(Context context) {
			super(context, R.layout.wifiitem);
		}

		public void add(Binding binding) {
			items.add(binding);
			notifyDataSetChanged();
		}

		public void clear() {
			items.clear();
			notifyDataSetChanged();
		}

		@Override
		public int getCount() {
			return items.size();
		}

		@Override
		public Binding getItem(int position) {
			return items.get(position);
		}

		@Override
		public View getView(int position, View convertView, ViewGroup parent) {
			LayoutInflater inflater = (LayoutInflater) getContext()
				.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
			View rowView = inflater.inflate(R.layout.wifiitem, parent, false);
			TextView ssidView = (TextView) rowView.findViewById(R.id.ssid);
			TextView profileView = (TextView) rowView.findViewById(R.id.profile);

			Binding binding = getItem(position);
			ssidView.setText(binding.ssid);
			profileView.setText(prefs.getProfileName(binding.profile));
			return rowView;
		}
	}

	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		DynamicColors.applyToActivityIfAvailable(this);

		setContentView(R.layout.wifilist);

		prefs = new Preferences(this);
		adapter = new BindingAdapter(this);
		listView = (ListView) findViewById(R.id.list);
		textview_current = (TextView) findViewById(R.id.current);

		currentSsid = WifiUtils.getCurrentSsid(this);
		updateCurrent();

		findViewById(R.id.bind).setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				bindCurrent();
			}
		});

		listView.setAdapter(adapter);
		listView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
			@Override
			public void onItemClick(AdapterView<?> parent, View view,
					int position, long id) {
				chooseProfile(adapter.getItem(position));
			}
		});
		listView.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
			@Override
			public boolean onItemLongClick(AdapterView<?> parent, View view,
					int position, long id) {
				confirmDelete(adapter.getItem(position));
				return true;
			}
		});

		reload();
	}

	@Override
	protected void onResume() {
		super.onResume();
		currentSsid = WifiUtils.getCurrentSsid(this);
		updateCurrent();
		reload();
	}

	private void updateCurrent() {
		if (currentSsid == null)
		  textview_current.setText(getString(R.string.wifi_unknown));
		else
		  textview_current.setText(getString(R.string.wifi_current, currentSsid));
	}

	private void reload() {
		adapter.clear();
		Map<String,Integer> map = prefs.getWifiMap();
		for (Map.Entry<String,Integer> e : map.entrySet())
		  adapter.add(new Binding(e.getKey(), e.getValue()));
	}

	private void bindCurrent() {
		if (currentSsid == null) {
			Toast.makeText(this, R.string.wifi_no_ssid, Toast.LENGTH_SHORT).show();
			return;
		}
		int profile = prefs.getSelected();
		prefs.setWifiProfile(currentSsid, profile);
		Toast.makeText(this, getString(R.string.wifi_bind_done, currentSsid,
			prefs.getProfileName(profile)), Toast.LENGTH_SHORT).show();
		reload();
	}

	private void chooseProfile(final Binding binding) {
		int count = prefs.getProfileCount();
		String[] names = new String[count];
		for (int i = 0; i < count; i++)
		  names[i] = prefs.getProfileName(i);

		new AlertDialog.Builder(this)
			.setTitle(binding.ssid)
			.setItems(names, new DialogInterface.OnClickListener() {
				@Override
				public void onClick(DialogInterface dialog, int which) {
					prefs.setWifiProfile(binding.ssid, which);
					reload();
				}
			})
			.setNegativeButton(android.R.string.cancel, null)
			.show();
	}

	private void confirmDelete(final Binding binding) {
		new AlertDialog.Builder(this)
			.setMessage(getString(R.string.wifi_delete_confirm, binding.ssid))
			.setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
				@Override
				public void onClick(DialogInterface dialog, int which) {
					prefs.removeWifiProfile(binding.ssid);
					reload();
				}
			})
			.setNegativeButton(android.R.string.cancel, null)
			.show();
	}
}
