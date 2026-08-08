/*
 ============================================================================
 Name        : ProfileAdapter.java
 Author      : Claude Code
 Description : RecyclerView Adapter for Profile List with Swipe-to-Reveal
 ============================================================================
 */

package com.x.client.app;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.RadioButton;
import android.widget.TextView;
import androidx.recyclerview.widget.RecyclerView;
import java.util.ArrayList;
import java.util.List;

public class ProfileAdapter extends RecyclerView.Adapter<ProfileAdapter.ViewHolder> {
    private List<Preferences.ProfileInfo> profiles;
    private String selectedProfileId;
    private OnProfileActionListener listener;
    private Preferences prefs;

    // 跟踪当前打开的项
    private SwipeRevealLayout currentlyOpenedLayout;

    public interface OnProfileActionListener {
        void onProfileClick(String profileId);
        void onShareClick(String profileId);
        void onCopyClick(String profileId);
        void onEditClick(String profileId);
        void onDeleteClick(String profileId);
    }

    public ProfileAdapter(OnProfileActionListener listener, Preferences prefs) {
        this.profiles = new ArrayList<>();
        this.listener = listener;
        this.prefs = prefs;
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

        // 设置选中状态
        holder.radioSelected.setChecked(profile.id.equals(selectedProfileId));

        // 设置配置名称
        holder.textProfileName.setText(profile.name);

        // 设置服务器地址
        holder.textServerAddr.setText(profile.serverAddr);

        // 设置协议类型标签（右下角）
        String protocolLabel = Preferences.PROTOCOL_X_TUNNEL.equals(profile.protocol) ? "X-Tunnel" : "GCM";
        holder.textProtocol.setText(protocolLabel);

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
                android.widget.Toast.makeText(v.getContext(), "VPN 正在运行，无法编辑当前配置", android.widget.Toast.LENGTH_SHORT).show();
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
                android.widget.Toast.makeText(v.getContext(), "VPN 正在运行，无法删除当前配置", android.widget.Toast.LENGTH_SHORT).show();
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

    @Override
    public int getItemCount() {
        return profiles.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        SwipeRevealLayout swipeLayout;
        View foregroundLayout;
        RadioButton radioSelected;
        TextView textProfileName;
        TextView textServerAddr;
        TextView textProtocol;
        ImageButton btnShare;
        ImageButton btnCopy;
        ImageButton btnEdit;
        ImageButton btnDelete;

        ViewHolder(View itemView) {
            super(itemView);
            swipeLayout = (SwipeRevealLayout) itemView;
            foregroundLayout = itemView.findViewById(R.id.foreground_layout);
            radioSelected = itemView.findViewById(R.id.radio_selected);
            textProfileName = itemView.findViewById(R.id.text_profile_name);
            textServerAddr = itemView.findViewById(R.id.text_server_addr);
            textProtocol = itemView.findViewById(R.id.text_protocol);
            btnShare = itemView.findViewById(R.id.btn_share);
            btnCopy = itemView.findViewById(R.id.btn_copy);
            btnEdit = itemView.findViewById(R.id.btn_edit);
            btnDelete = itemView.findViewById(R.id.btn_delete);
        }
    }
}
