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
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import android.Manifest;
import android.content.Context;
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
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;
import com.x.client.app.ui.EmptyStateView;

public class AppListActivity extends BaseActivity {
	private Preferences prefs;
	private AppArrayAdapter adapter;
	private ListView listView;
	private MaterialToolbar toolbar;
	private TextInputEditText searchBox;
	private TextView selectedCountView;
	private View bottomBar;
	private View loadingContainer;
	private EmptyStateView emptyState;
	private boolean isChanged = false;
	private boolean loaded = false;

	private final Collator collator = Collator.getInstance(Locale.getDefault());

	/* 列表排序规则与原实现一致：已选优先，其余按应用名字母/拼音序 */
	private final Comparator<Package> packageOrder = (a, b) -> {
		if (a.selected != b.selected)
			return a.selected ? -1 : 1;
		return collator.compare(a.label, b.label);
	};

	private static class Package {
		public final PackageInfo info;
		public final String label;
		public boolean selected;

		public Package(PackageInfo info, boolean selected, String label) {
			this.info = info;
			this.selected = selected;
			this.label = (label != null && !label.isEmpty()) ? label : (info != null ? info.packageName : "");
		}
	}

	private static class ViewHolder {
		final ImageView icon;
		final TextView name;
		final TextView packageName;
		final CheckBox checked;

		ViewHolder(View row) {
			icon = (ImageView) row.findViewById(R.id.icon);
			name = (TextView) row.findViewById(R.id.name);
			packageName = (TextView) row.findViewById(R.id.package_name);
			checked = (CheckBox) row.findViewById(R.id.checked);
		}
	}

	private class AppArrayAdapter extends ArrayAdapter<Package> {
		private final List<Package> allPackages = new ArrayList<Package>();
		private final List<Package> filteredPackages = new ArrayList<Package>();
		private String lastFilter = "";

		/* 应用图标缓存（固定条数，上界确定）：避免滚动时重复 loadIcon 造成卡顿 */
		private final LruCache<String, Drawable> iconCache = new LruCache<String, Drawable>(128);

		public AppArrayAdapter(Context context) {
			super(context, R.layout.appitem);
		}

		public void setPackages(List<Package> packages) {
			allPackages.clear();
			allPackages.addAll(packages);
			applyFilter(lastFilter);
		}

		@Override
		public void clear() {
			allPackages.clear();
			filteredPackages.clear();
			notifyDataSetChanged();
		}

		@Override
		public int getCount() {
			return filteredPackages.size();
		}

		@Override
		public Package getItem(int position) {
			return filteredPackages.get(position);
		}

		public List<Package> getAllPackages() {
			return allPackages;
		}

		public List<Package> getFilteredPackages() {
			return filteredPackages;
		}

		public void applyFilter(String filter) {
			lastFilter = filter != null ? filter.trim() : "";
			filteredPackages.clear();
			if (lastFilter.isEmpty()) {
				filteredPackages.addAll(allPackages);
			} else {
				String f = lastFilter.toLowerCase(Locale.getDefault());
				for (Package p : allPackages) {
					boolean matchLabel = p.label != null && p.label.toLowerCase(Locale.getDefault()).contains(f);
					boolean matchPkg = p.info != null && p.info.packageName != null && p.info.packageName.toLowerCase(Locale.getDefault()).contains(f);
					if (matchLabel || matchPkg) {
						filteredPackages.add(p);
					}
				}
			}
			notifyDataSetChanged();
		}

		@Override
		public View getView(int position, View convertView, ViewGroup parent) {
			View rowView = convertView;
			ViewHolder holder;
			if (rowView == null) {
				rowView = LayoutInflater.from(getContext())
					.inflate(R.layout.appitem, parent, false);
				holder = new ViewHolder(rowView);
				rowView.setTag(holder);
			} else {
				holder = (ViewHolder) rowView.getTag();
			}

			Package pkg = getItem(position);
			if (pkg == null)
				return rowView;

			holder.name.setText(pkg.label);
			holder.packageName.setText(pkg.info != null ? pkg.info.packageName : "");
			holder.checked.setChecked(pkg.selected);

			String pkgName = pkg.info != null ? pkg.info.packageName : "";
			Drawable icon = iconCache.get(pkgName);
			if (icon != null) {
				holder.icon.setImageDrawable(icon);
			} else {
				holder.icon.setImageDrawable(null);
				if (pkg.info != null && pkg.info.applicationInfo != null) {
					try {
						icon = pkg.info.applicationInfo.loadIcon(getContext().getPackageManager());
						if (icon != null) {
							iconCache.put(pkgName, icon);
							holder.icon.setImageDrawable(icon);
						}
					} catch (Exception ignored) {
					}
				}
			}

			return rowView;
		}
	}

	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_app_list);

		toolbar = (MaterialToolbar) findViewById(R.id.toolbar);
		listView = (ListView) findViewById(R.id.app_list);
		searchBox = (TextInputEditText) findViewById(R.id.search_apps);
		selectedCountView = (TextView) findViewById(R.id.selected_count);
		bottomBar = findViewById(R.id.bottom_bar);
		loadingContainer = findViewById(R.id.loading_container);
		emptyState = (EmptyStateView) findViewById(R.id.empty_state);
		MaterialButton saveButton = (MaterialButton) findViewById(R.id.btn_save);

		emptyState.setProfileActionsVisible(false);
		setupToolbar();

		prefs = new Preferences(this);
		adapter = new AppArrayAdapter(this);
		listView.setAdapter(adapter);

		listView.setOnItemClickListener((parent, view, position, id) -> {
			Package pkg = adapter.getItem(position);
			if (pkg == null)
				return;
			pkg.selected = !pkg.selected;
			ViewHolder holder = (ViewHolder) view.getTag();
			if (holder != null) {
				holder.checked.setChecked(pkg.selected);
			}
			isChanged = true;
			updateSelectedCount();
		});

		saveButton.setOnClickListener(v -> {
			/* 显式保存：与 onDestroy 兜底同一套逻辑（同 prefs.setApps） */
			saveSelection();
			finish();
		});

		searchBox.addTextChangedListener(new TextWatcher() {
			@Override
			public void beforeTextChanged(CharSequence s, int start, int count, int after) { }

			@Override
			public void onTextChanged(CharSequence s, int start, int before, int count) {
				adapter.applyFilter(s.toString());
				updateEmptyState();
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

		/* 溢出菜单：文字项（放不进图标资源，走标题文案）。
		 * 三个选择动作均作用于"当前过滤后的结果集"（WireGuard 惯例）：
		 * 无过滤时即作用于全部应用。 */
		MenuItem selectAll = toolbar.getMenu().add(R.string.action_select_all);
		selectAll.setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
		selectAll.setOnMenuItemClickListener(item -> {
			setFilteredSelection(true);
			return true;
		});

		MenuItem invert = toolbar.getMenu().add(R.string.action_invert_selection);
		invert.setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
		invert.setOnMenuItemClickListener(item -> {
			invertFilteredSelection();
			return true;
		});

		MenuItem clearSel = toolbar.getMenu().add(R.string.action_clear_selection);
		clearSel.setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
		clearSel.setOnMenuItemClickListener(item -> {
			setFilteredSelection(false);
			return true;
		});
	}

	/* 应用枚举放后台线程，onCreate 立即展示加载态；逐条加载应用与权限 */
	private void loadApps() {
		loadingContainer.setVisibility(View.VISIBLE);
		listView.setVisibility(View.GONE);
		bottomBar.setVisibility(View.GONE);
		emptyState.setVisibility(View.GONE);
		loaded = false;

		new Thread(() -> {
			PackageManager pm = getPackageManager();
			Set<String> apps = prefs.getApps();
			List<Package> packages = new ArrayList<Package>();

			List<PackageInfo> installedPackages = null;
			try {
				installedPackages = pm.getInstalledPackages(PackageManager.GET_PERMISSIONS);
			} catch (Exception e) {
				installedPackages = null;
			}

			if (installedPackages == null || installedPackages.isEmpty()) {
				try {
					installedPackages = pm.getInstalledPackages(0);
				} catch (Exception e) {
					installedPackages = new ArrayList<PackageInfo>();
				}
			}

			String myPackage = getPackageName();
			for (PackageInfo info : installedPackages) {
				if (info == null || info.packageName == null || info.packageName.equals(myPackage))
					continue;
				if (info.applicationInfo == null)
					continue;
				if (info.requestedPermissions != null && !Arrays.asList(info.requestedPermissions).contains(Manifest.permission.INTERNET))
					continue;
				boolean selected = apps.contains(info.packageName);
				String label;
				try {
					CharSequence cs = info.applicationInfo.loadLabel(pm);
					label = (cs != null && cs.length() > 0) ? cs.toString().trim() : info.packageName;
				} catch (Exception e) {
					label = info.packageName;
				}
				packages.add(new Package(info, selected, label));
			}

			Collections.sort(packages, packageOrder);

			runOnUiThread(() -> {
				if (isDestroyed() || isFinishing())
					return;
				adapter.setPackages(packages);
				loaded = true;
				loadingContainer.setVisibility(View.GONE);
				listView.setVisibility(View.VISIBLE);
				bottomBar.setVisibility(View.VISIBLE);
				updateSelectedCount();
				updateEmptyState();
			});
		}, "app-list-loader").start();
	}

	/* 全选/清除选择：作用于当前过滤后的结果集（无过滤 = 全部应用） */
	private void setFilteredSelection(boolean selected) {
		for (Package pkg : adapter.getFilteredPackages()) {
			pkg.selected = selected;
		}
		isChanged = true;
		adapter.notifyDataSetChanged();
		updateSelectedCount();
	}

	/* 反选：在当前过滤后的结果集内翻转 */
	private void invertFilteredSelection() {
		for (Package pkg : adapter.getFilteredPackages()) {
			pkg.selected = !pkg.selected;
		}
		isChanged = true;
		adapter.notifyDataSetChanged();
		updateSelectedCount();
	}

	private void updateSelectedCount() {
		int count = 0;
		for (Package pkg : adapter.getAllPackages()) {
			if (pkg.selected)
				count++;
		}
		selectedCountView.setText(getString(R.string.app_selected_count, count));
	}

	private void updateEmptyState() {
		/* 加载完成前不判空，避免把"还没加载出来"误读成"无结果" */
		if (!loaded) {
			emptyState.setVisibility(View.GONE);
			return;
		}
		boolean empty = adapter.getCount() == 0;
		emptyState.setVisibility(empty ? View.VISIBLE : View.GONE);
		if (empty) {
			emptyState.setProfileActionsVisible(false);
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

		Set<String> apps = new HashSet<String>();

		for (Package pkg : adapter.getAllPackages()) {
			if (pkg.selected && pkg.info != null && pkg.info.packageName != null)
				apps.add(pkg.info.packageName);
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
		/* 兜底：显式保存（保存按钮）已置 isChanged=false，此处不会重复写 prefs */
		saveSelection();
		super.onDestroy();
	}
}
