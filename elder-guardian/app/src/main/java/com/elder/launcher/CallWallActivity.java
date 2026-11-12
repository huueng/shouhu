package com.elder.launcher;

import android.content.Intent;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.ContactsContract;
import android.text.TextUtils;
import android.util.LruCache;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.cardview.widget.CardView;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.InputStream;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 电话墙 —— 老人点「打电话」看到的大头像卡片墙
 *
 * 为什么不用系统拨号盘：
 * 系统拨号盘要老人自己输号码、自己找联系人，字小、层级深、还容易点进通话记录乱拨。
 * 这里一张卡片一个人：头像 + 名字，点一下直接拨出去，没有第二个步骤。
 *
 * 分组：先「家人」（来自联系人卡片配置，永远排最前），再按拼音顺序排全部联系人。
 */
public class CallWallActivity extends AppCompatActivity {

    /**
     * 两列而不是三列：老人机普遍是 5.5–6.5 寸、720p 屏，三列时一张卡片只有 100dp 宽，
     * 头像挤到 88dp、名字只能显示三四个字。两列能放下 108dp 头像 + 完整姓名，
     * 手指也更好点（目标尺寸 ≥ 120dp 是人手舒适区）。
     */
    private static final int SPAN = 2;
    private static final int AVATAR_DP = 120;

    private RecyclerView grid;
    private WallAdapter adapter;
    private final List<Row> rows = new ArrayList<>();
    private LruCache<String, Bitmap> photoCache;

    /** 列表里的一行：要么是分组标题，要么是联系人卡片 */
    static class Row {
        boolean section;
        String title;
        String name;
        String number;
        String photoUri;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        int cacheSize = (int) (Runtime.getRuntime().maxMemory() / 8);
        photoCache = new LruCache<String, Bitmap>(cacheSize) {
            @Override
            protected int sizeOf(String key, Bitmap value) {
                return value.getByteCount();
            }
        };

        buildUi();
        Ui.applyEdgeToEdge(this);
        loadContacts();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.bg(this));

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(16), dp(12), dp(16), dp(8));

        TextView back = new TextView(this);
        back.setText("‹ 返回");
        back.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        back.setTextColor(Ui.accent(this));
        back.setPadding(dp(8), dp(8), dp(16), dp(8));
        back.setOnClickListener(v -> finish());
        header.addView(back);

        TextView title = new TextView(this);
        title.setText("打电话");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        title.setTextColor(Ui.textPrimary(this));
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        header.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        root.addView(header);

        grid = new RecyclerView(this);
        GridLayoutManager lm = new GridLayoutManager(this, SPAN);
        // 分组标题独占整行，否则会和第一张卡片挤在一格里
        lm.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
            @Override
            public int getSpanSize(int position) {
                if (position < 0 || position >= rows.size()) return 1;
                return rows.get(position).section ? SPAN : 1;
            }
        });
        grid.setLayoutManager(lm);
        grid.setClipToPadding(false);
        grid.setPadding(dp(8), 0, dp(8), dp(24));
        adapter = new WallAdapter();
        grid.setAdapter(adapter);
        root.addView(grid, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
    }

    /** 读通讯录：家人分组在前，其余按拼音排序 */
    private void loadContacts() {
        new Thread(() -> {
            final List<Row> loaded = new ArrayList<>();

            // 1. 家人（家人设置里配的联系人卡片）
            List<ContactStore.Contact> family = ContactStore.load(this);
            if (!family.isEmpty()) {
                Row s = new Row();
                s.section = true;
                s.title = "家人";
                loaded.add(s);
                for (ContactStore.Contact c : family) {
                    Row r = new Row();
                    r.name = c.name == null || c.name.isEmpty() ? c.phone : c.name;
                    r.number = c.phone;
                    r.photoUri = "file:" + (c.image == null ? "" : c.image);
                    loaded.add(r);
                }
            }

            // 2. 全部联系人（按号码去重）
            List<Row> all = new ArrayList<>();
            LinkedHashSet<String> seen = new LinkedHashSet<>();
            android.database.Cursor c = null;
            try {
                c = getContentResolver().query(
                        ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                        new String[]{
                                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                                ContactsContract.CommonDataKinds.Phone.NUMBER,
                                ContactsContract.CommonDataKinds.Phone.PHOTO_URI
                        },
                        null, null, null);
                if (c != null) {
                    while (c.moveToNext()) {
                        String name = c.getString(0);
                        String number = c.getString(1);
                        String photo = c.getString(2);
                        if (number == null) continue;
                        String key = number.replaceAll("[^0-9+]", "");
                        if (key.length() < 5) continue;
                        if (seen.contains(key)) continue;
                        seen.add(key);
                        Row r = new Row();
                        r.name = TextUtils.isEmpty(name) ? number : name;
                        r.number = number;
                        r.photoUri = photo;
                        all.add(r);
                    }
                }
            } catch (Exception ignored) {
            } finally {
                if (c != null) {
                    try {
                        c.close();
                    } catch (Exception ignored) {
                    }
                }
            }

            final Collator collator = Collator.getInstance(Locale.CHINA);
            Collections.sort(all, (a, b) -> collator.compare(a.name, b.name));

            if (!all.isEmpty()) {
                Row s = new Row();
                s.section = true;
                s.title = "全部联系人（" + all.size() + "）";
                loaded.add(s);
                loaded.addAll(all);
            }

            new Handler(Looper.getMainLooper()).post(() -> {
                rows.clear();
                rows.addAll(loaded);
                adapter.notifyDataSetChanged();
                if (rows.isEmpty()) {
                    Toast.makeText(this, "通讯录里没有带号码的联系人", Toast.LENGTH_LONG).show();
                }
            });
        }).start();
    }

    private void dial(String number) {
        if (number == null || number.isEmpty()) return;
        String clean = number.replace(" ", "");
        // 记一个「这次是我们要拨的」，通话结束后好自动回桌面
        CallReturn.markPending(this);
        try {
            startActivity(new Intent(Intent.ACTION_CALL,
                    Uri.parse("tel:" + Uri.encode(clean))));
        } catch (Exception e) {
            try {
                startActivity(new Intent(Intent.ACTION_DIAL,
                        Uri.parse("tel:" + Uri.encode(clean))));
            } catch (Exception ignored) {
                Toast.makeText(this, "无法拨号", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private class WallAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

        @Override
        public int getItemViewType(int position) {
            return rows.get(position).section ? 0 : 1;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            if (viewType == 0) {
                TextView tv = new TextView(parent.getContext());
                tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
                tv.setTextColor(Ui.textSecondary(parent.getContext()));
                tv.setPadding(dp(12), dp(16), dp(12), dp(6));
                return new HeaderHolder(tv);
            }
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_contact_card, parent, false);
            return new CardHolder(v);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            Row row = rows.get(position);
            if (holder instanceof HeaderHolder) {
                ((TextView) holder.itemView).setText(row.title);
                return;
            }
            CardHolder h = (CardHolder) holder;
            h.name.setText(row.name);
            h.number.setText(row.number);
            bindPhoto(h.avatar, row.photoUri, row.name);
            ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) h.name.getLayoutParams();
            h.card.setOnClickListener(v -> dial(row.number));
        }

        @Override
        public int getItemCount() {
            return rows.size();
        }
    }

    static class HeaderHolder extends RecyclerView.ViewHolder {
        HeaderHolder(View v) {
            super(v);
        }
    }

    static class CardHolder extends RecyclerView.ViewHolder {
        CardView card;
        ImageView avatar;
        TextView name;
        TextView number;

        CardHolder(View v) {
            super(v);
            card = v.findViewById(R.id.cc_card);
            avatar = v.findViewById(R.id.cc_avatar);
            name = v.findViewById(R.id.cc_name);
            number = v.findViewById(R.id.cc_number);
        }
    }

    /** 头像：先看内存缓存 → 通讯录照片 → 家人自定义图片 → 首字占位 */
    private void bindPhoto(ImageView iv, String photoUri, String name) {
        if (photoUri != null && !photoUri.isEmpty()) {
            Bitmap cached = photoCache.get(photoUri);
            if (cached != null) {
                iv.setImageBitmap(cached);
                return;
            }
            Bitmap bm = decodeBitmap(photoUri);
            if (bm != null) {
                photoCache.put(photoUri, bm);
                iv.setImageBitmap(bm);
                return;
            }
        }
        iv.setImageDrawable(new LetterDrawable(name));
    }

    private Bitmap decodeBitmap(String uriStr) {
        InputStream in = null;
        try {
            Uri uri = Uri.parse(uriStr);
            int target = dp(AVATAR_DP);
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            in = getContentResolver().openInputStream(uri);
            BitmapFactory.decodeStream(in, null, opts);
            if (in != null) in.close();

            int sample = 1;
            while (opts.outWidth / (sample * 2) >= target) sample *= 2;
            opts.inJustDecodeBounds = false;
            opts.inSampleSize = sample;

            in = getContentResolver().openInputStream(uri);
            Bitmap bm = BitmapFactory.decodeStream(in, null, opts);
            if (in != null) in.close();
            return bm;
        } catch (Exception e) {
            if (in != null) {
                try {
                    in.close();
                } catch (Exception ignored) {
                }
            }
            return null;
        }
    }

    /** 没有照片时画一个圆形底色 + 姓氏，比默认灰色小人好认 */
    private class LetterDrawable extends android.graphics.drawable.Drawable {
        private final String letter;
        private final android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Paint textPaint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final int[] colors = {
                0xFF1976D2, 0xFF388E3C, 0xFFF57C00, 0xFF7B1FA2, 0xFF00796B, 0xFFC2185B
        };
        private final int color;

        LetterDrawable(String name) {
            String n = (name == null || name.isEmpty()) ? "?" : name.trim();
            // 中文取最后一个字更像"名字"，英文取首字母
            letter = n.length() > 0 && n.charAt(0) > 127
                    ? n.substring(n.length() - 1) : n.substring(0, 1).toUpperCase(Locale.CHINA);
            color = colors[Math.abs(n.hashCode()) % colors.length];
            paint.setColor(color);
            textPaint.setColor(0xFFFFFFFF);
            textPaint.setTextAlign(android.graphics.Paint.Align.CENTER);
        }

        @Override
        public void draw(@NonNull android.graphics.Canvas canvas) {
            int w = getBounds().width();
            int h = getBounds().height();
            float r = Math.min(w, h) / 2f;
            canvas.drawCircle(w / 2f, h / 2f, r, paint);
            textPaint.setTextSize(r * 0.9f);
            float baseline = h / 2f - (textPaint.descent() + textPaint.ascent()) / 2f;
            canvas.drawText(letter, w / 2f, baseline, textPaint);
        }

        @Override
        public void setAlpha(int alpha) {
        }

        @Override
        public void setColorFilter(android.graphics.ColorFilter colorFilter) {
        }

        @Override
        public int getOpacity() {
            return android.graphics.PixelFormat.TRANSLUCENT;
        }
    }

    private int dp(float v) {
        Resources r = getResources();
        return (int) (v * r.getDisplayMetrics().density + 0.5f);
    }
}
