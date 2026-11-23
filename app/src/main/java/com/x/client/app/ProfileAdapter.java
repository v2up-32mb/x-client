/*
 ============================================================================
 Name        : ProfileAdapter.java
 Author      : Claude Code
 Description : RecyclerView Adapter for Profile List with Swipe-to-Reveal
 ============================================================================
 */

package com.x.client.app;

import android.graphics.drawable.GradientDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.RadioButton;
import android.widget.TextView;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.color.MaterialColors;
import java.util.ArrayList;
import java.util.List;

public class ProfileAdapter extends RecyclerView.Adapter<ProfileAdapter.ViewHolder> {
    private List<Preferences.ProfileInfo> profiles;
    private String selectedProfileId;
    private OnProfileActionListener listener;
    private Preferences prefs;
    private int spanCount = 1;

    // 跟踪当前打开的项
    private SwipeRevealLayout currentlyOpenedLayout;

    public interface OnProfileActionListener {
        void onProfileClick(String profileId);
        void onProfileLongClick(View anchor, String profileId, Preferences.ProfileInfo profile);
        void onProfileMenuClick(View anchor, String profileId, Preferences.ProfileInfo profile);
        void onShareClick(String profileId);
        void onCopyClick(String profileId);
        void onEditClick(String profileId);
        void onDeleteClick(String profileId);
    }

    public ProfileAdapter(OnProfileActionListener listener, Preferences prefs) {
        this(listener, prefs, 1);
    }

    public ProfileAdapter(OnProfileActionListener listener, Preferences prefs, int spanCount) {
        this.profiles = new ArrayList<>();
        this.listener = listener;
        this.prefs = prefs;
        this.spanCount = Math.max(1, spanCount);
    }

    public void setSpanCount(int spanCount) {
        this.spanCount = Math.max(1, spanCount);
        notifyDataSetChanged();
    }

    public void setProfiles(List<Preferences.ProfileInfo> profiles, String selectedProfileId) {
        this.profiles = profiles;
        this.selectedProfileId = selectedProfileId;
        notifyDataSetChanged();
    }

    public String getProfileId(int position) {
        if (position >= 0 && position < profiles.size()) {
            return profiles.get(position).id;
        }
        return null;
    }

    public int getSelectedPosition() {
        if (selectedProfileId == null || profiles == null) {
            return -1;
        }
        for (int i = 0; i < profiles.size(); i++) {
            if (selectedProfileId.equals(profiles.get(i).id)) {
                return i;
            }
        }
        return -1;
    }

    public Preferences.ProfileInfo getProfileInfo(String profileId) {
        if (profileId == null || profiles == null) {
            return null;
        }
        for (Preferences.ProfileInfo p : profiles) {
            if (profileId.equals(p.id)) {
                return p;
            }
        }
        return null;
    }

    public void closeAllItems() {
        if (currentlyOpenedLayout != null) {
            currentlyOpenedLayout.close(true);
            currentlyOpenedLayout = null;
        }
    }

    @Override
    public ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_profile_swipe, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(ViewHolder holder, int position) {
        Preferences.ProfileInfo profile = profiles.get(position);

        // 根据列位置动态调整卡片左右边距（外侧 12dp，中缝 12dp，8-point 体系对称）
        int col = spanCount > 1 ? (position % spanCount) : 0;
        float density = holder.itemView.getResources().getDisplayMetrics().density;
        int leftMargin;
        int rightMargin;
        if (spanCount <= 1) {
            leftMargin = Math.round(12f * density);
            rightMargin = Math.round(12f * density);
        } else {
            leftMargin = Math.round((12f - (float) col * 12f / spanCount) * density);
            rightMargin = Math.round(((float) 12f / spanCount * (col + 1)) * density);
        }
        ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) holder.foregroundLayout.getLayoutParams();
        if (lp != null && (lp.leftMargin != leftMargin || lp.rightMargin != rightMargin)) {
            lp.leftMargin = leftMargin;
            lp.rightMargin = rightMargin;
            holder.foregroundLayout.setLayoutParams(lp);
        }
        ViewGroup.MarginLayoutParams actionLp = (ViewGroup.MarginLayoutParams) holder.actionButtons.getLayoutParams();
        if (actionLp != null && actionLp.rightMargin != rightMargin) {
            actionLp.rightMargin = rightMargin;
            holder.actionButtons.setLayoutParams(actionLp);
        }
        holder.actionButtons.setTranslationZ(8f * density);

        // 设置 tag 便于在遥控器菜单键响应时找到对应项
        holder.foregroundLayout.setTag(profile.id);
        // TV 遥控器 D-pad：仅列表末尾项引导至 VPN FAB，其余项自然按网格 focusSearch
        if (position == profiles.size() - 1) {
            holder.foregroundLayout.setNextFocusRightId(R.id.vpn_fab);
        } else {
            holder.foregroundLayout.setNextFocusRightId(View.NO_ID);
        }

        // 设置选中状态
        boolean isSelected = profile.id.equals(selectedProfileId);
        holder.radioSelected.setChecked(isSelected);

        // 统一渲染当前项底色与遥控器焦点高光
        updateItemFocusVisual(holder.foregroundLayout, isSelected, holder.foregroundLayout.hasFocus());

        holder.foregroundLayout.setOnFocusChangeListener((v, hasFocus) -> {
            boolean currentSelected = profile.id.equals(selectedProfileId);
            updateItemFocusVisual(v, currentSelected, hasFocus);
            if (hasFocus) {
                // TV 端聚焦动效：1.02x 轻微放大并抬升 Z 轴
                v.animate().scaleX(1.02f).scaleY(1.02f).translationZ(6f).setDuration(120).start();
            } else {
                v.animate().scaleX(1.0f).scaleY(1.0f).translationZ(0f).setDuration(120).start();
            }
        });

        // 长按事件：呼出 TV / 遥控器节点操作菜单（以 btnProfileMenu 为锚点紧贴弹出）
        holder.foregroundLayout.setOnLongClickListener(v -> {
            if (listener != null) {
                listener.onProfileLongClick(holder.btnProfileMenu, profile.id, profile);
                return true;
            }
            return false;
        });

        // 设置配置名称
        holder.textProfileName.setText(profile.name);

        // 设置服务器地址（全局开关关闭时掩码显示，防截图泄露）
        if (prefs.getShowServerAddr()) {
            holder.textServerAddr.setText(profile.serverAddr);
        } else {
            holder.textServerAddr.setText("••••••");
        }

        // 设置协议类型标签（大写微徽章）
        String protocolLabel = Preferences.PROTOCOL_X_TUNNEL.equals(profile.protocol) ? "X-TUNNEL" : "GCM";
        holder.textProtocol.setText(protocolLabel);
        GradientDrawable badgeBg = new GradientDrawable();
        badgeBg.setShape(GradientDrawable.RECTANGLE);
        badgeBg.setCornerRadius(6f * density);
        badgeBg.setColor(MaterialColors.getColor(holder.textProtocol, R.attr.xTertiaryContainer));
        holder.textProtocol.setTextColor(MaterialColors.getColor(holder.textProtocol, R.attr.xOnTertiaryContainer));
        holder.textProtocol.setBackground(badgeBg);

        // 配置滑动行为（始终允许滑动，分享功能在 VPN 运行时也可用）
        // 注意：编辑/删除按钮在 VPN 运行时会禁用，见下方按钮点击事件

        // 设置滑动监听器
        holder.swipeLayout.setOnSwipeListener(new SwipeRevealLayout.OnSwipeListener() {
            @Override
            public void onOpened(SwipeRevealLayout.State state) {
                // 关闭之前打开的项
                if (currentlyOpenedLayout != null && currentlyOpenedLayout != holder.swipeLayout) {
                    currentlyOpenedLayout.close(true);
                }
                currentlyOpenedLayout = holder.swipeLayout;
            }

            @Override
            public void onClosed() {
                if (currentlyOpenedLayout == holder.swipeLayout) {
                    currentlyOpenedLayout = null;
                }
            }
        });

        // 设置滑动开始监听器（立即关闭其他打开的项）
        holder.swipeLayout.setOnSwipeStartListener(layout -> {
            if (currentlyOpenedLayout != null && currentlyOpenedLayout != layout) {
                currentlyOpenedLayout.close(true);
            }
        });

        // 前景层点击 - 选择配置
        holder.foregroundLayout.setOnClickListener(v -> {
            if (holder.swipeLayout.isOpened()) {
                // 点击当前打开的配置项，关闭它
                holder.swipeLayout.close(true);
            } else {
                // 先关闭其他打开的项（带动画）
                if (currentlyOpenedLayout != null) {
                    SwipeRevealLayout toClose = currentlyOpenedLayout;
                    currentlyOpenedLayout = null;
                    toClose.close(true);
                }
                // 然后执行点击回调
                if (listener != null) {
                    listener.onProfileClick(profile.id);
                }
            }
        });

        // 右侧操作菜单按钮
        holder.btnProfileMenu.setOnClickListener(v -> {
            if (currentlyOpenedLayout != null) {
                SwipeRevealLayout toClose = currentlyOpenedLayout;
                currentlyOpenedLayout = null;
                toClose.close(true);
            }
            if (listener != null) {
                listener.onProfileMenuClick(v, profile.id, profile);
            }
        });

        // 分享按钮
        holder.btnShare.setOnClickListener(v -> {
            if (listener != null) {
                listener.onShareClick(profile.id);
            }
            holder.swipeLayout.close(true);
        });

        // 复制按钮：仅读取配置数据创建副本，不修改当前配置，VPN 运行时也可用
        holder.btnCopy.setOnClickListener(v -> {
            if (listener != null) {
                listener.onCopyClick(profile.id);
            }
            holder.swipeLayout.close(true);
        });

        // 修改按钮
        holder.btnEdit.setOnClickListener(v -> {
            boolean isVpnRunning = prefs.getEnable();
            boolean isCurrentProfile = profile.id.equals(selectedProfileId);
            if (isVpnRunning && isCurrentProfile) {
                // VPN 运行且是当前配置：显示提示
                android.widget.Toast.makeText(v.getContext(), R.string.cannot_edit_running, android.widget.Toast.LENGTH_SHORT).show();
            } else if (listener != null) {
                listener.onEditClick(profile.id);
            }
            holder.swipeLayout.close(true);
        });

        // 删除按钮
        holder.btnDelete.setOnClickListener(v -> {
            boolean isVpnRunning = prefs.getEnable();
            boolean isCurrentProfile = profile.id.equals(selectedProfileId);
            if (isVpnRunning && isCurrentProfile) {
                // VPN 运行且是当前配置：显示提示
                android.widget.Toast.makeText(v.getContext(), R.string.cannot_delete_running, android.widget.Toast.LENGTH_SHORT).show();
            } else if (listener != null) {
                listener.onDeleteClick(profile.id);
            }
            holder.swipeLayout.close(true);
        });
    }

    @Override
    public void onViewRecycled(ViewHolder holder) {
        super.onViewRecycled(holder);
        // 重置滑动状态
        holder.swipeLayout.close(false);
        holder.swipeLayout.setOnSwipeListener(null);
        holder.swipeLayout.setOnSwipeStartListener(null);
    }

    private void updateItemFocusVisual(View view, boolean isSelected, boolean hasFocus) {
        float density = view.getResources().getDisplayMetrics().density;
        int fillColor = MaterialColors.getColor(view, isSelected ? R.attr.xPrimaryContainer : R.attr.xSurface);
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
        gd.setCornerRadius(14f * density);
        gd.setStroke(strokeWidth, strokeColor);
        view.setBackground(gd);
    }

    @Override
    public int getItemCount() {
        return profiles.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        SwipeRevealLayout swipeLayout;
        View foregroundLayout;
        View actionButtons;
        RadioButton radioSelected;
        TextView textProfileName;
        TextView textServerAddr;
        TextView textProtocol;
        ImageButton btnProfileMenu;
        ImageButton btnShare;
        ImageButton btnCopy;
        ImageButton btnEdit;
        ImageButton btnDelete;

        ViewHolder(View itemView) {
            super(itemView);
            swipeLayout = (SwipeRevealLayout) itemView;
            foregroundLayout = itemView.findViewById(R.id.foreground_layout);
            actionButtons = itemView.findViewById(R.id.action_buttons);
            radioSelected = itemView.findViewById(R.id.radio_selected);
            textProfileName = itemView.findViewById(R.id.text_profile_name);
            textServerAddr = itemView.findViewById(R.id.text_server_addr);
            textProtocol = itemView.findViewById(R.id.text_protocol);
            btnProfileMenu = itemView.findViewById(R.id.btn_profile_menu);
            btnShare = itemView.findViewById(R.id.btn_share);
            btnCopy = itemView.findViewById(R.id.btn_copy);
            btnEdit = itemView.findViewById(R.id.btn_edit);
            btnDelete = itemView.findViewById(R.id.btn_delete);
        }
    }
}
