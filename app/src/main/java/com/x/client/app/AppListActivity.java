/*
 ============================================================================
 Name        : AppListActivity.java
 Author      : hev <r@hev.cc>
 Copyright   : Copyright (c) 2025 xyz
 Description : App List Activity
 ============================================================================
 */

package com.x.client.app;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import android.Manifest;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.LruCache;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.checkbox.MaterialCheckBox;
import com.google.android.material.textfield.TextInputEditText;
import com.x.client.app.ui.EmptyStateView;

public class AppListActivity extends BaseActivity {
	private static final int MENU_SELECT_ALL = 1;
	private static final int MENU_INVERT = 2;
	private static final int MENU_CLEAR = 3;
	private static final int MENU_SHOW_SYSTEM = 4;

	private Preferences prefs;
	private AppAdapter adapter;
	private RecyclerView recyclerView;
	private MaterialToolbar toolbar;
	private TextInputEditText searchBox;
	private TextView selectedCountView;
	private View bottomBar;
	private View loadingContainer;
	private EmptyStateView emptyState;

	private final List<AppItem> allApps = new ArrayList<>();
	private final List<AppItem> filteredApps = new ArrayList<>();
	private final LruCache<String, Drawable> iconCache = new LruCache<>(128);
	private final Collator collator = Collator.getInstance(Locale.getDefault());

	private String currentQuery = "";
	private boolean showSystemApps = false;
	private boolean isChanged = false;
	private boolean loaded = false;
	private MenuItem showSystemMenuItem;

	public static class AppItem {
		public final PackageInfo info;
		public final String packageName;
		public final String label;
		public final boolean isSystemApp;
		public boolean selected;

		public AppItem(PackageInfo info, boolean selected, String label, boolean isSystemApp) {
			this.info = info;
			this.packageName = info != null ? info.packageName : "";
			this.selected = selected;
			this.label = (label != null && !label.isEmpty()) ? label : this.packageName;
			this.isSystemApp = isSystemApp;
		}
	}

	private static class AppViewHolder extends RecyclerView.ViewHolder {
		final ImageView icon;
		final TextView name;
		final TextView badgeSystem;
		final TextView packageName;
		final MaterialCheckBox checked;

		AppViewHolder(@NonNull View itemView) {
			super(itemView);
			icon = itemView.findViewById(R.id.icon);
			name = itemView.findViewById(R.id.name);
			badgeSystem = itemView.findViewById(R.id.badge_system);
			packageName = itemView.findViewById(R.id.package_name);
			checked = itemView.findViewById(R.id.checked);
		}
	}

	private class AppAdapter extends RecyclerView.Adapter<AppViewHolder> {
		@NonNull
		@Override
		public AppViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
			View view = LayoutInflater.from(parent.getContext())
					.inflate(R.layout.appitem, parent, false);
			return new AppViewHolder(view);
		}

		@Override
		public void onBindViewHolder(@NonNull AppViewHolder holder, int position) {
			AppItem item = filteredApps.get(position);
			holder.name.setText(item.label);
			holder.packageName.setText(item.packageName);
			holder.badgeSystem.setVisibility(item.isSystemApp ? View.VISIBLE : View.GONE);
			holder.checked.setChecked(item.selected);

			holder.itemView.setContentDescription(
					getString(R.string.acc_app_select, item.label));

			Drawable icon = iconCache.get(item.packageName);
			if (icon != null) {
				holder.icon.setImageDrawable(icon);
			} else {
				holder.icon.setImageDrawable(null);
				if (item.info != null && item.info.applicationInfo != null) {
					try {
						icon = item.info.applicationInfo.loadIcon(getPackageManager());
						iconCache.put(item.packageName, icon);
						holder.icon.setImageDrawable(icon);
					} catch (Exception ignored) {
					}
				}
			}

			holder.itemView.setOnClickListener(v -> {
				int pos = holder.getBindingAdapterPosition();
				if (pos >= 0 && pos < filteredApps.size()) {
					AppItem target = filteredApps.get(pos);
					target.selected = !target.selected;
					notifyItemChanged(pos);
					isChanged = true;
					updateSelectedCount();
				}
			});
		}

		@Override
		public int getItemCount() {
			return filteredApps.size();
		}
	}

	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_app_list);

		toolbar = findViewById(R.id.toolbar);
		recyclerView = findViewById(R.id.app_recycler_view);
		searchBox = findViewById(R.id.search_apps);
		selectedCountView = findViewById(R.id.selected_count);
		bottomBar = findViewById(R.id.bottom_bar);
		loadingContainer = findViewById(R.id.loading_container);
		emptyState = findViewById(R.id.empty_state);
		MaterialButton saveButton = findViewById(R.id.btn_save);

		setupToolbar();

		prefs = new Preferences(this);
		adapter = new AppAdapter();
		recyclerView.setLayoutManager(new LinearLayoutManager(this));
		recyclerView.setAdapter(adapter);

		saveButton.setOnClickListener(v -> {
			saveSelection();
			finish();
		});

		searchBox.addTextChangedListener(new TextWatcher() {
			@Override
			public void beforeTextChanged(CharSequence s, int start, int count, int after) { }

			@Override
			public void onTextChanged(CharSequence s, int start, int before, int count) {
				currentQuery = s != null ? s.toString() : "";
				applyFilter();
			}

			@Override
			public void afterTextChanged(Editable s) { }
		});

		loadApps();
	}

	private void setupToolbar() {
		toolbar.setTitle(R.string.apps);
		toolbar.setNavigationIcon(R.drawable.ic_arrow_back);
		toolbar.setNavigationContentDescription(R.string.back);
		toolbar.setNavigationOnClickListener(v -> onBackPressed());

		MenuItem selectAll = toolbar.getMenu().add(0, MENU_SELECT_ALL, 0, R.string.action_select_all);
		selectAll.setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
		selectAll.setOnMenuItemClickListener(item -> {
			setFilteredSelection(true);
			return true;
		});

		MenuItem invert = toolbar.getMenu().add(0, MENU_INVERT, 1, R.string.action_invert_selection);
		invert.setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
		invert.setOnMenuItemClickListener(item -> {
			invertFilteredSelection();
			return true;
		});

		MenuItem clearSel = toolbar.getMenu().add(0, MENU_CLEAR, 2, R.string.action_clear_selection);
		clearSel.setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
		clearSel.setOnMenuItemClickListener(item -> {
			setFilteredSelection(false);
			return true;
		});

		showSystemMenuItem = toolbar.getMenu().add(0, MENU_SHOW_SYSTEM, 3, R.string.action_show_system_apps);
		showSystemMenuItem.setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
		showSystemMenuItem.setCheckable(true);
		showSystemMenuItem.setChecked(showSystemApps);
		showSystemMenuItem.setOnMenuItemClickListener(item -> {
			showSystemApps = !showSystemApps;
			item.setChecked(showSystemApps);
			applyFilter();
			return true;
		});
	}

	private void loadApps() {
		new Thread(() -> {
			PackageManager pm = getPackageManager();
			Set<String> selectedSet = prefs.getApps();
			List<AppItem> list = new ArrayList<>();

			List<PackageInfo> installedPackages;
			try {
				installedPackages = pm.getInstalledPackages(PackageManager.GET_PERMISSIONS);
			} catch (Exception e) {
				installedPackages = Collections.emptyList();
			}

			String myPackage = getPackageName();
			for (PackageInfo info : installedPackages) {
				if (info == null || info.packageName == null || info.packageName.equals(myPackage)) {
					continue;
				}
				if (info.applicationInfo == null) {
					continue;
				}
				if (info.requestedPermissions == null) {
					continue;
				}
				boolean hasInternet = false;
				for (String perm : info.requestedPermissions) {
					if (Manifest.permission.INTERNET.equals(perm)) {
						hasInternet = true;
						break;
					}
				}
				if (!hasInternet) {
					continue;
				}

				boolean isSystem = (info.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
				boolean selected = selectedSet.contains(info.packageName);
				String label;
				try {
					CharSequence cs = info.applicationInfo.loadLabel(pm);
					label = cs != null ? cs.toString().trim() : info.packageName;
				} catch (Exception e) {
					label = info.packageName;
				}

				list.add(new AppItem(info, selected, label, isSystem));
			}

			sortAppList(list);

			runOnUiThread(() -> {
				if (isDestroyed() || isFinishing()) {
					return;
				}
				allApps.clear();
				allApps.addAll(list);
				loaded = true;
				loadingContainer.setVisibility(View.GONE);
				recyclerView.setVisibility(View.VISIBLE);
				bottomBar.setVisibility(View.VISIBLE);
				applyFilter();
				updateSelectedCount();
			});
		}, "app-list-loader").start();
	}

	private void sortAppList(List<AppItem> list) {
		Collections.sort(list, (a, b) -> {
			if (a.selected != b.selected) {
				return a.selected ? -1 : 1;
			}
			if (a.isSystemApp != b.isSystemApp) {
				return a.isSystemApp ? 1 : -1;
			}
			return collator.compare(a.label, b.label);
		});
	}

	private void applyFilter() {
		String query = currentQuery.trim().toLowerCase(Locale.getDefault());
		filteredApps.clear();
		for (AppItem app : allApps) {
			if (!showSystemApps && app.isSystemApp && !app.selected) {
				continue;
			}
			if (query.isEmpty()) {
				filteredApps.add(app);
			} else {
				boolean matchLabel = app.label != null
						&& app.label.toLowerCase(Locale.getDefault()).contains(query);
				boolean matchPkg = app.packageName != null
						&& app.packageName.toLowerCase(Locale.getDefault()).contains(query);
				if (matchLabel || matchPkg) {
					filteredApps.add(app);
				}
			}
		}
		adapter.notifyDataSetChanged();
		updateEmptyState();
	}

	private void setFilteredSelection(boolean selected) {
		for (AppItem item : filteredApps) {
			item.selected = selected;
		}
		isChanged = true;
		adapter.notifyDataSetChanged();
		updateSelectedCount();
	}

	private void invertFilteredSelection() {
		for (AppItem item : filteredApps) {
			item.selected = !item.selected;
		}
		isChanged = true;
		adapter.notifyDataSetChanged();
		updateSelectedCount();
	}

	private void updateSelectedCount() {
		int count = 0;
		for (AppItem app : allApps) {
			if (app.selected) {
				count++;
			}
		}
		selectedCountView.setText(getString(R.string.app_selected_count, count));
	}

	private void updateEmptyState() {
		if (!loaded) {
			emptyState.setVisibility(View.GONE);
			return;
		}
		boolean empty = filteredApps.isEmpty();
		emptyState.setVisibility(empty ? View.VISIBLE : View.GONE);
		if (empty) {
			emptyState.setProfileActionsVisible(false);
			emptyState.setIconResource(android.R.drawable.ic_menu_search);
			emptyState.setTitle(getString(R.string.empty_search_title));
			emptyState.setMessage(getString(R.string.empty_search_message));
			emptyState.setAction(getString(R.string.action_clear_search),
					v -> searchBox.setText(""));
		}
	}

	private void saveSelection() {
		if (!isChanged) {
			return;
		}

		Set<String> apps = new HashSet<>();
		for (AppItem app : allApps) {
			if (app.selected) {
				apps.add(app.packageName);
			}
		}

		prefs.setApps(apps);
		isChanged = false;
	}

	@Override
	protected void onPause() {
		saveSelection();
		super.onPause();
	}

	@Override
	public void onBackPressed() {
		saveSelection();
		super.onBackPressed();
	}

	@Override
	protected void onDestroy() {
		saveSelection();
		super.onDestroy();
	}
}
