package com.x.client.app;

import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

/**
 * 1-item Header Adapter for ConcatAdapter, binding the speed test dashboard and controls.
 * Eliminates detach/attach hacks and index offset calculations.
 */
public class PreferredIpHeaderAdapter extends RecyclerView.Adapter<PreferredIpHeaderAdapter.HeaderViewHolder> {

    private final PreferredIpTestsBinder binder;
    private final PreferredIpTestsBinder.Listener listener;

    public PreferredIpHeaderAdapter(@NonNull PreferredIpTestsBinder binder, @NonNull PreferredIpTestsBinder.Listener listener) {
        this.binder = binder;
        this.listener = listener;
        setHasStableIds(true);
    }

    @Override
    public int getItemCount() {
        return 1;
    }

    @Override
    public int getItemViewType(int position) {
        return 0;
    }

    @Override
    public long getItemId(int position) {
        return 0L;
    }

    @NonNull
    @Override
    public HeaderViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View headerView = binder.inflateAndBind(parent, listener);
        return new HeaderViewHolder(headerView);
    }

    @Override
    public void onBindViewHolder(@NonNull HeaderViewHolder holder, int position) {
        // State updates are delivered directly to the binder
    }

    public static class HeaderViewHolder extends RecyclerView.ViewHolder {
        public HeaderViewHolder(@NonNull View itemView) {
            super(itemView);
        }
    }
}
