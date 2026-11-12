package com.elder.launcher;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 联系人卡片的存储（家人配置，主界面展示）
 * 存在 elder_config / contact_tiles，结构：[{name,phone,image}]
 */
public final class ContactStore {

    public static class Contact {
        public String name;
        public String phone;
        public String image;   // 自定义图片的本地路径，可为空

        public Contact(String name, String phone, String image) {
            this.name = name;
            this.phone = phone;
            this.image = image;
        }
    }

    private ContactStore() {
    }

    public static List<Contact> load(Context ctx) {
        List<Contact> list = new ArrayList<>();
        String raw = prefs(ctx).getString("contact_tiles", "");
        if (raw == null || raw.isEmpty()) return list;
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                list.add(new Contact(o.optString("name"), o.optString("phone"),
                        o.optString("image")));
            }
        } catch (Exception ignored) {
        }
        return list;
    }

    public static void save(Context ctx, List<Contact> list) {
        JSONArray arr = new JSONArray();
        for (Contact c : list) {
            try {
                JSONObject o = new JSONObject();
                o.put("name", c.name);
                o.put("phone", c.phone);
                o.put("image", c.image == null ? "" : c.image);
                arr.put(o);
            } catch (Exception ignored) {
            }
        }
        prefs(ctx).edit().putString("contact_tiles", arr.toString()).apply();
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences("elder_config", Context.MODE_PRIVATE);
    }
}
