package com.x.client.app;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.graphics.drawable.GradientDrawable;
import android.widget.ImageButton;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.checkbox.MaterialCheckBox;
import com.google.android.material.color.MaterialColors;
import org.json.JSONObject;

import java.util.*;

/**
 * 0-indexed ListAdapter for preferred IP candidates.
 * Uses static DiffUtil.ItemCallback to prevent memory leaks and supports Android TV focus highlight.
 */
public class PreferredIpResultAdapter extends ListAdapter<PreferredIpController.Row, PreferredIpResultAdapter.ResultViewHolder> {

    public interface ItemListener {
        void onToggleSelect(String address, boolean checked);
        void onToggleExpand(String address);
        void onStartDownload(String address);
    }

    private static final DiffUtil.ItemCallback<PreferredIpController.Row> DIFF_CALLBACK =
            new DiffUtil.ItemCallback<PreferredIpController.Row>() {
                @Override
                public boolean areItemsTheSame(@NonNull PreferredIpController.Row oldItem, @NonNull PreferredIpController.Row newItem) {
                    return oldItem.address.equals(newItem.address);
                }

                @Override
                public boolean areContentsTheSame(@NonNull PreferredIpController.Row oldItem, @NonNull PreferredIpController.Row newItem) {
                    return oldItem.content().equals(newItem.content());
                }
            };

    private final ItemListener listener;
    private final Set<String> selectedAddresses = new HashSet<>();
    private final Set<String> expandedAddresses = new HashSet<>();
    private final Map<String, Long> stableIds = new HashMap<>();
    private long nextStableId = 1L;
    private String activeDownload = "";

    public PreferredIpResultAdapter(@NonNull ItemListener listener) {
        super(DIFF_CALLBACK);
        this.listener = listener;
        setHasStableIds(true);
    }

    @Override
    public long getItemId(int position) {
        if (position < 0 || position >= getItemCount()) return RecyclerView.NO_ID;
        String address = getItem(position).address;
        Long id = stableIds.get(address);
        if (id == null) {
            id = nextStableId++;
            stableIds.put(address, id);
        }
        return id;
    }

    public void setSelectedAddresses(Set<String> addresses) {
        selectedAddresses.clear();
        if (addresses != null) {
            selectedAddresses.addAll(addresses);
        }
    }

    public void setExpandedAddresses(Set<String> expanded) {
        expandedAddresses.clear();
        if (expanded != null) {
            expandedAddresses.addAll(expanded);
        }
    }

    public void setActiveDownload(String activeDownload) {
        this.activeDownload = activeDownload == null ? "" : activeDownload;
    }

    public void rebindSelection(String address) {
        if (address == null) return;
        for (int i = 0; i < getItemCount(); i++) {
            if (address.equals(getItem(i).address)) {
                notifyItemChanged(i);
                return;
            }
        }
    }

    @NonNull
    @Override
    public ResultViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View card = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_preferred_result, parent, false);
        ResultViewHolder holder = new ResultViewHolder(card);
        TvFocusHelper.applyFocusFeedback(holder.download);
        return holder;
    }

    @Override
    public void onBindViewHolder(@NonNull ResultViewHolder holder, int position) {
        PreferredIpController.Row row = getItem(position);
        holder.address = row.address;

        boolean isSelected = selectedAddresses.contains(row.address);
        boolean isTv = DeviceUtils.isTv(holder.itemView.getContext());

        // 统一渲染卡片底色与 TV 遥控器焦点高光
        updateItemFocusVisual(holder.itemView, isSelected, holder.itemView.hasFocus());

        holder.itemView.setOnFocusChangeListener((v, hasFocus) -> {
            boolean currentSelected = selectedAddresses.contains(row.address);
            updateItemFocusVisual(v, currentSelected, hasFocus);
            if (hasFocus) {
                v.animate().scaleX(1.02f).scaleY(1.02f).translationZ(6f).setDuration(120).start();
            } else {
                v.animate().scaleX(1.0f).scaleY(1.0f).translationZ(0f).setDuration(120).start();
            }
        });

        // Checkbox 绑定与更新
        holder.choice.setOnCheckedChangeListener(null);
        holder.choice.setChecked(isSelected);
        holder.choice.setEnabled(row.selectable);
        holder.choice.setContentDescription("选择 " + row.address);
        holder.choice.setOnClickListener(v -> {
            boolean checked = holder.choice.isChecked();
            listener.onToggleSelect(row.address, checked);
        });

        // IP 地址（加零宽空格防溢出）
        holder.addressText.setText(row.address.replace(":", ":\u200b"));

        // 地区微徽章
        if (row.location.isEmpty() || "UKN".equalsIgnoreCase(row.location)) {
            holder.location.setVisibility(View.GONE);
        } else {
            holder.location.setVisibility(View.VISIBLE);
            holder.location.setText(row.location);
        }

        // 延迟微徽章与评分
        bindLatencyAndScore(holder, row);

        // 折叠 / 展开状态控制
        boolean isExpanded = expandedAddresses.contains(row.address);
        holder.expanded.setVisibility(isExpanded ? View.VISIBLE : View.GONE);
        if (holder.chevron != null) {
            holder.chevron.setRotation(isExpanded ? 90f : 0f);
            holder.chevron.setOnClickListener(v -> listener.onToggleExpand(row.address));
        }

        holder.heading.setContentDescription(row.location + "，" + row.address + "，评分 " + row.score
                + (isExpanded ? "，已展开，点击收起" : "，已折叠，点击展开"));

        holder.heading.setOnClickListener(v -> {
            if (isTv) {
                listener.onToggleSelect(row.address, !selectedAddresses.contains(row.address));
            } else {
                listener.onToggleExpand(row.address);
            }
        });

        holder.itemView.setOnClickListener(v -> {
            if (isTv) {
                listener.onToggleSelect(row.address, !selectedAddresses.contains(row.address));
            }
        });

        holder.detail.setText(isExpanded && row.detail.isEmpty() ? "读取详情…" : row.detail);

        // 下载测速按钮
        boolean downloading = row.address.equals(activeDownload);
        holder.download.setText(downloading ? R.string.preferred_cancel_speed_test : R.string.preferred_speed_test_btn);
        holder.download.setEnabled(downloading || row.downloadable);
        holder.download.setOnClickListener(v -> listener.onStartDownload(row.address));
    }

    private void bindLatencyAndScore(ResultViewHolder holder, PreferredIpController.Row row) {
        JSONObject r = row.result;
        boolean tested = r != null && r.optBoolean("probe_complete") && r.optBoolean("score_ready");

        if (!tested) {
            holder.latencyBadge.setText("未测试");
            holder.latencyBadge.setTextColor(MaterialColors.getColor(holder.latencyBadge, R.attr.xOnSurfaceVariant));
            setMicroBadgeBackground(holder.latencyBadge, MaterialColors.getColor(holder.latencyBadge, R.attr.xSurfaceContainerHigh));
            holder.score.setVisibility(View.GONE);
            return;
        }

        boolean failed = "failed".equals(r.optString("status")) || r.optInt("http_success", 0) == 0;
        double latency = r.has("http_median_ms") && r.optDouble("http_median_ms") > 0
                ? r.optDouble("http_median_ms") : r.optDouble("tcp_avg_ms", -1);

        if (failed || latency <= 0) {
            holder.latencyBadge.setText("超时/失败");
            holder.latencyBadge.setTextColor(MaterialColors.getColor(holder.latencyBadge, R.attr.xOnErrorContainer));
            setMicroBadgeBackground(holder.latencyBadge, MaterialColors.getColor(holder.latencyBadge, R.attr.xErrorContainer));
        } else if (latency < 150) {
            holder.latencyBadge.setText(String.format(Locale.getDefault(), "%.0f ms", latency));
            holder.latencyBadge.setTextColor(MaterialColors.getColor(holder.latencyBadge, R.attr.mdOnSuccessContainer));
            setMicroBadgeBackground(holder.latencyBadge, MaterialColors.getColor(holder.latencyBadge, R.attr.mdSuccessContainer));
        } else if (latency < 300) {
            holder.latencyBadge.setText(String.format(Locale.getDefault(), "%.0f ms", latency));
            holder.latencyBadge.setTextColor(MaterialColors.getColor(holder.latencyBadge, R.attr.xOnPrimaryContainer));
            setMicroBadgeBackground(holder.latencyBadge, MaterialColors.getColor(holder.latencyBadge, R.attr.xPrimaryContainer));
        } else {
            holder.latencyBadge.setText(String.format(Locale.getDefault(), "%.0f ms", latency));
            holder.latencyBadge.setTextColor(MaterialColors.getColor(holder.latencyBadge, R.attr.mdOnWarning));
            setMicroBadgeBackground(holder.latencyBadge, MaterialColors.getColor(holder.latencyBadge, R.attr.xSurfaceContainerHigh));
        }

        if (row.score != null && !row.score.isEmpty() && !"—".equals(row.score)) {
            holder.score.setVisibility(View.VISIBLE);
            holder.score.setText("评分 " + row.score);
        } else {
            holder.score.setVisibility(View.GONE);
        }
    }

    private void setMicroBadgeBackground(TextView textView, int color) {
        float density = textView.getResources().getDisplayMetrics().density;
        GradientDrawable gd = new GradientDrawable();
        gd.setShape(GradientDrawable.RECTANGLE);
        gd.setCornerRadius(6f * density);
        gd.setColor(color);
        textView.setBackground(gd);
    }

    private void updateItemFocusVisual(View view, boolean isSelected, boolean hasFocus) {
        float density = view.getResources().getDisplayMetrics().density;
        int fillColor = MaterialColors.getColor(view, isSelected ? R.attr.xPrimaryContainer : R.attr.xSurfaceContainerLowest);
        int strokeColor;
        int strokeWidth;

        if (hasFocus) {
            strokeColor = MaterialColors.getColor(view, R.attr.xPrimary);
            strokeWidth = (int) (2.5f * density);
            view.setElevation(6f * density);
        } else if (isSelected) {
            strokeColor = MaterialColors.getColor(view, R.attr.xPrimary);
            strokeWidth = (int) (1.5f * density);
            view.setElevation(2f * density);
        } else {
            strokeColor = MaterialColors.getColor(view, R.attr.xOutlineVariant);
            strokeWidth = (int) (1f * density);
            view.setElevation(0.5f * density);
        }

        GradientDrawable gd = new GradientDrawable();
        gd.setShape(GradientDrawable.RECTANGLE);
        gd.setColor(fillColor);
        gd.setCornerRadius(16f * density);
        gd.setStroke(strokeWidth, strokeColor);
        view.setBackground(gd);
    }

    public static class ResultViewHolder extends RecyclerView.ViewHolder {
        public String address = "";
        public final MaterialCheckBox choice;
        public final View heading;
        public final View expanded;
        public final TextView addressText;
        public final TextView location;
        public final TextView latencyBadge;
        public final TextView score;
        public final TextView detail;
        public final ImageButton chevron;
        public final MaterialButton download;

        public ResultViewHolder(@NonNull View card) {
            super(card);
            heading = card.findViewById(R.id.preferred_row_heading);
            choice = card.findViewById(R.id.preferred_choice);
            addressText = card.findViewById(R.id.preferred_row_address);
            location = card.findViewById(R.id.preferred_row_location);
            latencyBadge = card.findViewById(R.id.preferred_latency_badge);
            score = card.findViewById(R.id.preferred_row_score);
            chevron = card.findViewById(R.id.preferred_row_chevron);
            expanded = card.findViewById(R.id.preferred_row_expanded);
            detail = card.findViewById(R.id.preferred_row_detail);
            download = card.findViewById(R.id.preferred_row_download);
        }
    }
}
