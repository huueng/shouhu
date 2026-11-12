package com.elder.launcher;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.provider.ContactsContract;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.List;

/**
 * 联系人卡片管理（家人使用）
 * - 从系统通讯录里挑一个联系人，生成主界面卡片
 * - 可以给卡片换一张自定义图片（从相册选）
 * - 长按/点删除移除卡片（不影响系统通讯录）
 */
public class ContactManageActivity extends AppCompatActivity {

    private static final int REQ_PICK_CONTACT = 101;
    private static final int REQ_PICK_IMAGE = 102;

    private LinearLayout list;
    private List<ContactStore.Contact> contacts;
    private int imageTargetIndex = -1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(20), dp(20), dp(20));
        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText("联系人卡片");
        title.setTextSize(24);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, 0, 0, dp(12));
        root.addView(title);

        TextView hint = new TextView(this);
        hint.setText("这里添加的联系人会直接出现在主界面，点一下就能打电话。\n"
                + "图片可以从相册里选，留空就用默认电话图标。");
        hint.setTextSize(13);
        hint.setPadding(0, 0, 0, dp(12));
        root.addView(hint);

        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        root.addView(list);

        Button add = new Button(this);
        add.setText("添加联系人");
        add.setOnClickListener(v -> pickContact());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(12);
        root.addView(add, lp);

        Button done = new Button(this);
        done.setText("完成");
        done.setOnClickListener(v -> finish());
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dlp.topMargin = dp(8);
        root.addView(done, dlp);

        setContentView(scroll);
        Ui.applyEdgeToEdge(this);
        reload();
    }

    private void reload() {
        contacts = ContactStore.load(this);
        list.removeAllViews();
        if (contacts.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("还没有添加联系人");
            empty.setTextSize(15);
            empty.setPadding(0, dp(8), 0, dp(8));
            list.addView(empty);
            return;
        }
        for (int i = 0; i < contacts.size(); i++) {
            list.addView(buildRow(i));
        }
    }

    private LinearLayout buildRow(final int index) {
        final ContactStore.Contact c = contacts.get(index);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(8), 0, dp(8));

        ImageView icon = new ImageView(this);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(56), dp(56));
        icon.setLayoutParams(ilp);
        if (c.image != null && !c.image.isEmpty()) {
            android.graphics.Bitmap bm = BitmapFactory.decodeFile(c.image);
            if (bm != null) icon.setImageBitmap(bm);
            else icon.setImageResource(android.R.drawable.ic_menu_call);
        } else {
            icon.setImageResource(android.R.drawable.ic_menu_call);
        }
        row.addView(icon);

        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tlp.leftMargin = dp(12);
        text.setLayoutParams(tlp);

        TextView name = new TextView(this);
        name.setText(c.name == null || c.name.isEmpty() ? c.phone : c.name);
        name.setTextSize(17);
        text.addView(name);

        TextView phone = new TextView(this);
        phone.setText(c.phone);
        phone.setTextSize(13);
        text.addView(phone);
        row.addView(text);

        Button img = new Button(this);
        img.setText("图片");
        img.setTextSize(13);
        img.setOnClickListener(v -> {
            imageTargetIndex = index;
            Intent i = new Intent(Intent.ACTION_GET_CONTENT);
            i.setType("image/*");
            startActivityForResult(Intent.createChooser(i, "选择图片"), REQ_PICK_IMAGE);
        });
        row.addView(img);

        Button del = new Button(this);
        del.setText("删除");
        del.setTextSize(13);
        del.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("删除卡片")
                .setMessage("只删除主界面上的卡片，不会删除手机通讯录里的联系人。")
                .setPositiveButton("删除", (d, w) -> {
                    contacts.remove(index);
                    ContactStore.save(this, contacts);
                    reload();
                })
                .setNegativeButton("取消", null)
                .show());
        row.addView(del);

        return row;
    }

    private void pickContact() {
        Intent i = new Intent(Intent.ACTION_PICK,
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI);
        startActivityForResult(i, REQ_PICK_CONTACT);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != Activity.RESULT_OK || data == null) return;

        if (requestCode == REQ_PICK_CONTACT) {
            Uri uri = data.getData();
            if (uri == null) return;
            String name = "";
            String number = "";
            Cursor cursor = null;
            try {
                cursor = getContentResolver().query(uri,
                        new String[]{ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                                ContactsContract.CommonDataKinds.Phone.NUMBER}, null, null, null);
                if (cursor != null && cursor.moveToFirst()) {
                    name = cursor.getString(0);
                    number = cursor.getString(1);
                }
            } catch (Exception e) {
                Toast.makeText(this, "读取联系人失败", Toast.LENGTH_SHORT).show();
                return;
            } finally {
                if (cursor != null) cursor.close();
            }
            if (number == null || number.isEmpty()) {
                Toast.makeText(this, "该联系人没有号码", Toast.LENGTH_SHORT).show();
                return;
            }
            contacts.add(new ContactStore.Contact(name, number, ""));
            ContactStore.save(this, contacts);
            reload();

        } else if (requestCode == REQ_PICK_IMAGE && imageTargetIndex >= 0
                && imageTargetIndex < contacts.size()) {
            String path = copyImage(data.getData());
            if (path != null) {
                contacts.get(imageTargetIndex).image = path;
                ContactStore.save(this, contacts);
                reload();
            } else {
                Toast.makeText(this, "图片保存失败", Toast.LENGTH_SHORT).show();
            }
        }
    }

    /** 把选中的图片复制到应用私有目录，避免相册权限依赖 */
    private String copyImage(Uri uri) {
        if (uri == null) return null;
        InputStream in = null;
        FileOutputStream out = null;
        try {
            in = getContentResolver().openInputStream(uri);
            if (in == null) return null;
            File f = new File(getFilesDir(), "contact_" + System.currentTimeMillis() + ".jpg");
            out = new FileOutputStream(f);
            byte[] buf = new byte[8192];
            int len;
            while ((len = in.read(buf)) > 0) out.write(buf, 0, len);
            out.flush();
            return f.getAbsolutePath();
        } catch (Exception e) {
            return null;
        } finally {
            try {
                if (in != null) in.close();
            } catch (Exception ignored) {
            }
            try {
                if (out != null) out.close();
            } catch (Exception ignored) {
            }
        }
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
