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
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.os.Build;
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

	/* 列表排序规则：已选优先，其余按应用名字母/拼音序 */
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
			String pkgName = pkg.info != null ? pkg.info.packageName : "";
			holder.packageName.setText(pkgName);
			holder.checked.setChecked(pkg.selected);

			Drawable icon = iconCache.get(pkgName);
			if (icon != null) {
				holder.icon.setImageDrawable(icon);
			} else {
				holder.icon.setImageDrawable(null);
				PackageManager pm = getContext().getPackageManager();
				try {
					if (pkg.info != null && pkg.info.applicationInfo != null) {
						icon = pkg.info.applicationInfo.loadIcon(pm);
					}
					if (icon == null && !pkgName.isEmpty()) {
						icon = pm.getApplicationIcon(pkgName);
					}
					if (icon != null) {
						iconCache.put(pkgName, icon);
						holder.icon.setImageDrawable(icon);
					}
				} catch (Exception ignored) {
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

	/* 应用枚举：多层探测策略（getInstalledPackages -> getInstalledApplications -> queryIntentActivities 兜底），彻底规避 API 30+ 权限拦截与空态 */
	private void loadApps() {
		loadingContainer.setVisibility(View.VISIBLE);
		listView.setVisibility(View.GONE);
		bottomBar.setVisibility(View.GONE);
		emptyState.setVisibility(View.GONE);
		loaded = false;

		new Thread(() -> {
			PackageManager pm = getPackageManager();
			Set<String> selectedApps = prefs.getApps();
			Map<String, Package> packageMap = new LinkedHashMap<>();
			String myPackage = getPackageName();

			// 策略 1：通过 getInstalledPackages 枚举已安装应用
			try {
				List<PackageInfo> installedList = null;
				if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
					installedList = pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(0));
				} else {
					installedList = pm.getInstalledPackages(0);
				}
				if (installedList != null) {
					for (PackageInfo info : installedList) {
						if (info == null || info.packageName == null || info.packageName.equals(myPackage))
							continue;
						if (info.applicationInfo == null)
							continue;
						boolean selected = selectedApps.contains(info.packageName);
						String label;
						try {
							CharSequence cs = info.applicationInfo.loadLabel(pm);
							label = (cs != null && cs.length() > 0) ? cs.toString().trim() : info.packageName;
						} catch (Exception e) {
							label = info.packageName;
						}
						packageMap.put(info.packageName, new Package(info, selected, label));
					}
				}
			} catch (Exception ignored) {
			}

			// 策略 2：若策略 1 结果过少（受定制系统包可见性限制拦截），通过 getInstalledApplications 补充
			if (packageMap.size() <= 1) {
				try {
					List<ApplicationInfo> appList = null;
					if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
						appList = pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0));
					} else {
						appList = pm.getInstalledApplications(0);
					}
					if (appList != null) {
						for (ApplicationInfo appInfo : appList) {
							if (appInfo == null || appInfo.packageName == null || appInfo.packageName.equals(myPackage))
								continue;
							if (!packageMap.containsKey(appInfo.packageName)) {
								PackageInfo pi = new PackageInfo();
								pi.packageName = appInfo.packageName;
								pi.applicationInfo = appInfo;
								boolean selected = selectedApps.contains(appInfo.packageName);
								String label;
								try {
									CharSequence cs = appInfo.loadLabel(pm);
									label = (cs != null && cs.length() > 0) ? cs.toString().trim() : appInfo.packageName;
								} catch (Exception e) {
									label = appInfo.packageName;
								}
								packageMap.put(appInfo.packageName, new Package(pi, selected, label));
							}
						}
					}
				} catch (Exception ignored) {
				}
			}

			// 策略 3：通过桌面/电视启动 Intent 查询补充（保证所有在桌面上可见的应用均能被枚举到）
			try {
				Intent[] intents = new Intent[] {
					new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
					new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER),
					new Intent(Intent.ACTION_MAIN)
				};
				for (Intent intent : intents) {
					List<ResolveInfo> resolveInfos = pm.queryIntentActivities(intent, 0);
					if (resolveInfos != null) {
						for (ResolveInfo ri : resolveInfos) {
							if (ri.activityInfo != null && ri.activityInfo.packageName != null) {
								String pkgName = ri.activityInfo.packageName;
								if (pkgName.equals(myPackage) || packageMap.containsKey(pkgName))
									continue;
								PackageInfo pi = null;
								try {
									if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
										pi = pm.getPackageInfo(pkgName, PackageManager.PackageInfoFlags.of(0));
									} else {
										pi = pm.getPackageInfo(pkgName, 0);
									}
								} catch (Exception ignored) {
								}
								if (pi == null) {
									pi = new PackageInfo();
									pi.packageName = pkgName;
									pi.applicationInfo = ri.activityInfo.applicationInfo;
								}
								boolean selected = selectedApps.contains(pkgName);
								String label;
								try {
									CharSequence cs = ri.loadLabel(pm);
									if (cs == null && pi.applicationInfo != null)
										cs = pi.applicationInfo.loadLabel(pm);
									label = (cs != null && cs.length() > 0) ? cs.toString().trim() : pkgName;
								} catch (Exception e) {
									label = pkgName;
								}
								packageMap.put(pkgName, new Package(pi, selected, label));
							}
						}
					}
				}
			} catch (Exception ignored) {
			}

			// 策略 4：保留之前已保存选中过的应用（即使系统当前未枚举出来也保证不丢失）
			for (String selectedPkg : selectedApps) {
				if (!packageMap.containsKey(selectedPkg) && !selectedPkg.equals(myPackage)) {
					PackageInfo pi = null;
					try {
						if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
							pi = pm.getPackageInfo(selectedPkg, PackageManager.PackageInfoFlags.of(0));
						} else {
							pi = pm.getPackageInfo(selectedPkg, 0);
						}
					} catch (Exception ignored) {
					}
					if (pi == null) {
						pi = new PackageInfo();
						pi.packageName = selectedPkg;
					}
					String label = selectedPkg;
					if (pi.applicationInfo != null) {
						try {
							CharSequence cs = pi.applicationInfo.loadLabel(pm);
							if (cs != null && cs.length() > 0)
								label = cs.toString().trim();
						} catch (Exception ignored) {
						}
					}
					packageMap.put(selectedPkg, new Package(pi, true, label));
				}
			}

			List<Package> packages = new ArrayList<>(packageMap.values());
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
			CharSequence query = searchBox != null ? searchBox.getText() : null;
			boolean isSearching = query != null && query.length() > 0;
			if (isSearching) {
				emptyState.setTitle(getString(R.string.empty_search_title));
				emptyState.setMessage(getString(R.string.empty_search_message));
				emptyState.setAction(getString(R.string.action_clear_search),
						v -> searchBox.setText(""));
			} else {
				emptyState.setTitle(getString(R.string.empty_apps_title));
				emptyState.setMessage(getString(R.string.empty_apps_message));
				emptyState.setAction(getString(R.string.common_retry),
						v -> loadApps());
			}
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
