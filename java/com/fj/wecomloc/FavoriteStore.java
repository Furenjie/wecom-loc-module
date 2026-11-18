package com.fj.wecomloc;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

final class FavoriteStore {

    private static final String PREFS = "favorites";
    private static final String KEY = "items";
    private static final double SAME_DISTANCE = 0.0005d; // about 50 m

    static final class Item {
        String name;
        String addr;
        double lat;
        double lng;

        Item(String name, String addr, double lat, double lng) {
            this.name = name;
            this.addr = addr;
            this.lat = lat;
            this.lng = lng;
        }
    }

    private FavoriteStore() {}

    private static boolean same(Item a, Item b) {
        return a.name.equals(b.name)
                && Math.abs(a.lat - b.lat) < SAME_DISTANCE
                && Math.abs(a.lng - b.lng) < SAME_DISTANCE;
    }

    static List<Item> load(Context ctx) {
        List<Item> raw = new ArrayList<>();
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String json = sp.getString(KEY, "");
        if (json == null || json.isEmpty()) return raw;
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                raw.add(new Item(
                        o.optString("name"),
                        o.optString("addr"),
                        o.optDouble("lat"),
                        o.optDouble("lng")));
            }
        } catch (Throwable ignored) {
        }
        List<Item> out = new ArrayList<>();
        for (Item it : raw) {
            boolean merged = false;
            for (Item old : out) {
                if (same(old, it)) {
                    old.lat = it.lat;
                    old.lng = it.lng;
                    old.addr = it.addr;
                    merged = true;
                    break;
                }
            }
            if (!merged) out.add(it);
        }
        return out;
    }

    static void save(Context ctx, List<Item> items) {
        List<Item> unique = new ArrayList<>();
        for (Item it : items) {
            boolean merged = false;
            for (Item old : unique) {
                if (same(old, it)) {
                    old.lat = it.lat;
                    old.lng = it.lng;
                    old.addr = it.addr;
                    merged = true;
                    break;
                }
            }
            if (!merged) unique.add(it);
        }
        JSONArray arr = new JSONArray();
        for (Item it : unique) {
            try {
                JSONObject o = new JSONObject();
                o.put("name", it.name);
                o.put("addr", it.addr);
                o.put("lat", it.lat);
                o.put("lng", it.lng);
                arr.put(o);
            } catch (Throwable ignored) {
            }
        }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY, arr.toString()).apply();
    }

    static void add(Context ctx, Item item) {
        List<Item> items = load(ctx);
        for (Item it : items) {
            if (same(it, item)) {
                it.lat = item.lat;
                it.lng = item.lng;
                it.addr = item.addr;
                save(ctx, items);
                return;
            }
        }
        items.add(item);
        save(ctx, items);
    }
}
