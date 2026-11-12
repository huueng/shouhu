package com.elder.launcher;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.cardview.widget.CardView;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

/**
 * 主界面图标网格适配器（手机版：2 列大卡片）
 *
 * 电视版的 3 列自适应 + 遥控器焦点放大逻辑已随 TV 模块一起移除，
 * 配色统一由 values / values-night 提供，深色模式不需要额外分支。
 */
public class TileAdapter extends RecyclerView.Adapter<TileAdapter.TileViewHolder> {

    public interface OnTileClickListener {
        void onTileClick(TileItem item);
    }

    private final List<TileItem> items;
    private final OnTileClickListener listener;

    public TileAdapter(List<TileItem> items, OnTileClickListener listener) {
        this.items = items;
        this.listener = listener;
    }

    @NonNull
    @Override
    public TileViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_tile, parent, false);
        return new TileViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull TileViewHolder holder, int position) {
        TileItem item = items.get(position);
        holder.label.setText(item.label);

        if (item.imagePath != null && !item.imagePath.isEmpty()) {
            android.graphics.Bitmap bm = android.graphics.BitmapFactory.decodeFile(item.imagePath);
            if (bm != null) {
                holder.icon.setImageBitmap(bm);
            } else if (item.iconDrawable != null) {
                holder.icon.setImageDrawable(item.iconDrawable);
            } else {
                holder.icon.setImageResource(android.R.drawable.ic_menu_call);
            }
        } else if (item.iconDrawable != null) {
            // 应用图标：必须用 Drawable，不能把别的包的资源 ID 传进 setImageResource
            holder.icon.setImageDrawable(item.iconDrawable);
        } else {
            try {
                holder.icon.setImageResource(item.iconRes);
            } catch (Exception e) {
                holder.icon.setImageResource(android.R.drawable.sym_def_app_icon);
            }
        }

        holder.card.setOnClickListener(v -> {
            if (listener != null) listener.onTileClick(item);
        });
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class TileViewHolder extends RecyclerView.ViewHolder {
        CardView card;
        ImageView icon;
        TextView label;

        TileViewHolder(@NonNull View itemView) {
            super(itemView);
            card = itemView.findViewById(R.id.tile_card);
            icon = itemView.findViewById(R.id.tile_icon);
            label = itemView.findViewById(R.id.tile_label);
        }
    }
}
