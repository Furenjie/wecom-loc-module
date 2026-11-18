package com.fj.wecomloc;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tencent Maps place search + static map preview.
 *
 * The key is the one shipped in WeCom's manifest. It is used here only for
 * place search / static map requests from this local module.
 */
public class PlacePickerActivity extends Activity {

    private static final String KEY = "ZNKBZ-WALKF-RITJD-JHF3R-SDHKS-SXFW2";
    private static final String SEARCH_API = "https://apis.map.qq.com/ws/place/v1/search";
    private static final String STATIC_API = "https://apis.map.qq.com/ws/staticmap/v2/";

    private EditText searchEdit;
    private ImageView mapPreview;
    private TextView selectedText;
    private ListView resultList;
    private Button collectButton;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final List<Place> places = new ArrayList<>();
    private ArrayAdapter<String> adapter;
    private Place selected;

    private static final class Place {
        String title;
        String address;
        double lat;
        double lng;

        Place(String title, String address, double lat, double lng) {
            this.title = title;
            this.address = address;
            this.lat = lat;
            this.lng = lng;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("腾讯地图选点");
        buildUi();
        String q = getIntent() == null ? null : getIntent().getStringExtra("q");
        String shared = null;
        if (getIntent() != null && Intent.ACTION_SEND.equals(getIntent().getAction())) {
            shared = getIntent().getStringExtra(Intent.EXTRA_TEXT);
        }
        if (shared != null && !shared.trim().isEmpty()) {
            double[] ll = parseCoordinates(shared);
            if (ll != null) {
                selected = new Place("腾讯地图分享", "来自腾讯地图分享", ll[0], ll[1]);
                collectButton.setEnabled(true);
                selectedText.setText("已识别分享坐标：" + ll[0] + ", " + ll[1]);
                loadStaticMap(ll[0], ll[1]);
            } else {
                q = shared.trim();
            }
        }
        if (q != null && !q.trim().isEmpty()) {
            searchEdit.setText(q);
            searchEdit.post(new Runnable() {
                @Override public void run() { search(); }
            });
        }
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(14), dp(16), dp(12));
        root.setBackgroundColor(Color.rgb(248, 249, 251));

        TextView title = new TextView(this);
        title.setText("腾讯地图搜索选点");
        title.setTextSize(22);
        title.setTextColor(Color.rgb(24, 28, 36));
        root.addView(title, lp(-1, -2, 0, 4));

        TextView hint = new TextView(this);
        hint.setText("输入地点名，选中结果后点“收藏并返回”。也可以从腾讯地图复制分享链接，点“剪贴板导入”。");
        hint.setTextSize(12);
        hint.setTextColor(Color.rgb(96, 104, 116));
        hint.setLineSpacing(dp(2), 1f);
        root.addView(hint, lp(-1, -2, 0, 10));

        LinearLayout searchRow = new LinearLayout(this);
        searchRow.setOrientation(LinearLayout.HORIZONTAL);
        searchRow.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(searchRow, lp(-1, dp(48), 0, 10));

        searchEdit = new EditText(this);
        searchEdit.setSingleLine(true);
        searchEdit.setHint("搜索地点，如：川威大厦");
        searchEdit.setTextSize(15);
        searchEdit.setInputType(InputType.TYPE_CLASS_TEXT);
        searchEdit.setPadding(dp(12), 0, dp(12), 0);
        searchEdit.setBackgroundColor(Color.WHITE);
        searchRow.addView(searchEdit, new LinearLayout.LayoutParams(0, dp(46), 1f));

        Button search = new Button(this);
        search.setText("搜索");
        search.setAllCaps(false);
        search.setTextSize(14);
        searchRow.addView(search, new LinearLayout.LayoutParams(dp(78), dp(46)));
        search.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { search(); }
        });
        searchEdit.setOnEditorActionListener((v, actionId, event) -> {
            search();
            return true;
        });

        mapPreview = new ImageView(this);
        mapPreview.setScaleType(ImageView.ScaleType.CENTER_CROP);
        mapPreview.setBackgroundColor(Color.rgb(226, 232, 238));
        root.addView(mapPreview, lp(-1, dp(170), 0, 8));

        selectedText = new TextView(this);
        selectedText.setText("尚未选点");
        selectedText.setTextSize(13);
        selectedText.setTextColor(Color.rgb(80, 88, 100));
        selectedText.setLineSpacing(dp(2), 1f);
        root.addView(selectedText, lp(-1, -2, 0, 6));

        resultList = new ListView(this);
        resultList.setDividerHeight(1);
        resultList.setBackgroundColor(Color.WHITE);
        root.addView(resultList, new LinearLayout.LayoutParams(-1, 0, 1f));
        adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, new ArrayList<>());
        resultList.setAdapter(adapter);
        resultList.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                if (position >= 0 && position < places.size()) {
                    select(places.get(position));
                }
            }
        });

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(actions, lp(-1, dp(46), 8, 0));

        Button clip = new Button(this);
        clip.setText("剪贴板导入");
        clip.setAllCaps(false);
        clip.setTextSize(13);
        actions.addView(clip, new LinearLayout.LayoutParams(0, dp(46), 1f));
        clip.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { importClipboard(); }
        });

        Button openMap = new Button(this);
        openMap.setText("腾讯地图 App");
        openMap.setAllCaps(false);
        openMap.setTextSize(13);
        actions.addView(openMap, new LinearLayout.LayoutParams(0, dp(46), 1f));
        openMap.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openTencentMap(); }
        });

        collectButton = new Button(this);
        collectButton.setText("收藏并返回");
        collectButton.setAllCaps(false);
        collectButton.setTextSize(15);
        collectButton.setEnabled(false);
        root.addView(collectButton, lp(-1, dp(50), 8, 0));
        collectButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { collect(); }
        });

        setContentView(root);
    }

    private LinearLayout.LayoutParams lp(int w, int h, int top, int bottom) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.topMargin = dp(top);
        p.bottomMargin = dp(bottom);
        return p;
    }

    private void search() {
        final String keyword = searchEdit.getText().toString().trim();
        if (keyword.isEmpty()) {
            Toast.makeText(this, "请输入地点名", Toast.LENGTH_SHORT).show();
            return;
        }
        selectedText.setText("正在搜索腾讯地图...");
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    String url = SEARCH_API + "?keyword=" + URLEncoder.encode(keyword, "UTF-8")
                            + "&boundary=region(" + URLEncoder.encode("全国", "UTF-8") + ",0)"
                            + "&page_size=20&key=" + KEY;
                    String body = httpGet(url);
                    JSONObject root = new JSONObject(body);
                    int status = root.optInt("status", -1);
                    if (status != 0) {
                        final String msg = root.optString("message", "搜索失败");
                        ui.post(() -> {
                            selectedText.setText("腾讯地图返回：" + msg);
                        });
                        return;
                    }
                    JSONArray data = root.optJSONArray("data");
                    final List<Place> found = new ArrayList<>();
                    if (data != null) {
                        for (int i = 0; i < data.length(); i++) {
                            JSONObject o = data.getJSONObject(i);
                            JSONObject loc = o.optJSONObject("location");
                            if (loc == null) continue;
                            found.add(new Place(
                                    o.optString("title"),
                                    o.optString("address"),
                                    loc.optDouble("lat"),
                                    loc.optDouble("lng")));
                        }
                    }
                    ui.post(() -> {
                        places.clear();
                        places.addAll(found);
                        rebuildList();
                        if (places.isEmpty()) {
                            selectedText.setText("没搜到，换个关键词试试");
                        } else {
                            selectedText.setText("找到 " + places.size() + " 个结果，点一条选中");
                            select(places.get(0));
                        }
                    });
                } catch (Throwable t) {
                    ui.post(() -> selectedText.setText("搜索失败：" + t.getMessage()));
                }
            }
        }).start();
    }

    private void rebuildList() {
        List<String> rows = new ArrayList<>();
        for (Place p : places) {
            rows.add(p.title + "\n" + p.address + "\n" + p.lat + ", " + p.lng);
        }
        adapter.clear();
        adapter.addAll(rows);
        adapter.notifyDataSetChanged();
    }

    private void select(Place p) {
        selected = p;
        collectButton.setEnabled(true);
        selectedText.setText("已选：" + p.title + "\n" + p.address + "\n"
                + p.lat + ", " + p.lng);
        loadStaticMap(p.lat, p.lng);
    }

    private void loadStaticMap(final double lat, final double lng) {
        mapPreview.setImageDrawable(null);
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    String url = STATIC_API + "?center=" + lat + "," + lng
                            + "&zoom=16&size=600x300&scale=2&key=" + KEY;
                    HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                    c.setConnectTimeout(15000);
                    c.setReadTimeout(15000);
                    c.setDoInput(true);
                    c.connect();
                    InputStream in = c.getInputStream();
                    final Bitmap bmp = BitmapFactory.decodeStream(in);
                    in.close();
                    c.disconnect();
                    if (bmp != null) {
                        ui.post(() -> mapPreview.setImageBitmap(bmp));
                    }
                } catch (Throwable ignored) {
                }
            }
        }).start();
    }

    private void collect() {
        if (selected == null) {
            Toast.makeText(this, "先搜索并选中一个点", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent out = new Intent();
        out.putExtra("name", selected.title);
        out.putExtra("addr", selected.address);
        out.putExtra("lat", selected.lat);
        out.putExtra("lng", selected.lng);

        if (getCallingActivity() == null) {
            FavoriteStore.add(this, new FavoriteStore.Item(
                    selected.title, selected.address, selected.lat, selected.lng));
            Toast.makeText(this, "已收藏：" + selected.title, Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        setResult(RESULT_OK, out);
        finish();
    }

    private void importClipboard() {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clip = cm.getPrimaryClip();
            if (clip == null || clip.getItemCount() == 0) {
                Toast.makeText(this, "剪贴板为空", Toast.LENGTH_SHORT).show();
                return;
            }
            CharSequence cs = clip.getItemAt(0).coerceToText(this);
            String text = cs == null ? "" : cs.toString();
            double[] ll = parseCoordinates(text);
            if (ll == null) {
                Toast.makeText(this, "没识别出坐标，复制腾讯地图分享链接再试", Toast.LENGTH_LONG).show();
                return;
            }
            Place p = new Place("腾讯地图选点", "来自剪贴板", ll[0], ll[1]);
            selected = p;
            collectButton.setEnabled(true);
            selectedText.setText("已从剪贴板识别：" + ll[0] + ", " + ll[1]);
            loadStaticMap(ll[0], ll[1]);
        } catch (Throwable t) {
            Toast.makeText(this, "读取剪贴板失败：" + t, Toast.LENGTH_SHORT).show();
        }
    }

    private static double[] parseCoordinates(String text) {
        if (text == null) return null;
        try {
            Matcher m = Pattern.compile("pointx=([0-9.]+).*?pointy=([0-9.]+)", Pattern.CASE_INSENSITIVE).matcher(text);
            if (m.find()) return new double[]{Double.parseDouble(m.group(2)), Double.parseDouble(m.group(1))};
            m = Pattern.compile("pointy=([0-9.]+).*?pointx=([0-9.]+)", Pattern.CASE_INSENSITIVE).matcher(text);
            if (m.find()) return new double[]{Double.parseDouble(m.group(1)), Double.parseDouble(m.group(2))};
            m = Pattern.compile("(?:lat|latitude)[=:]\\s*([0-9.-]+).*?(?:lng|lon|longitude)[=:]\\s*([0-9.-]+)", Pattern.CASE_INSENSITIVE).matcher(text);
            if (m.find()) return new double[]{Double.parseDouble(m.group(1)), Double.parseDouble(m.group(2))};
            m = Pattern.compile("(?:lng|lon|longitude)[=:]\\s*([0-9.-]+).*?(?:lat|latitude)[=:]\\s*([0-9.-]+)", Pattern.CASE_INSENSITIVE).matcher(text);
            if (m.find()) return new double[]{Double.parseDouble(m.group(2)), Double.parseDouble(m.group(1))};
            m = Pattern.compile("([0-9]{2,3}\\.[0-9]{3,})[,\\s]+([0-9]{2,3}\\.[0-9]{3,})").matcher(text);
            if (m.find()) return new double[]{Double.parseDouble(m.group(1)), Double.parseDouble(m.group(2))};
        } catch (Throwable ignored) {
        }
        return null;
    }

    private void openTencentMap() {
        try {
            String keyword = searchEdit.getText().toString().trim();
            String uri;
            if (keyword.isEmpty() && selected != null) {
                uri = "https://map.qq.com/?type=marker&isopeninfowin=1&markertype=1&pointx="
                        + selected.lng + "&pointy=" + selected.lat
                        + "&name=" + URLEncoder.encode(selected.title, "UTF-8");
            } else {
                uri = "https://map.qq.com/?type=search&keyword="
                        + URLEncoder.encode(keyword, "UTF-8");
            }
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(uri));
            startActivity(i);
        } catch (Throwable t) {
            Toast.makeText(this, "打开腾讯地图失败：" + t, Toast.LENGTH_SHORT).show();
        }
    }

    private static String httpGet(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(15000);
        c.setRequestProperty("Accept", "application/json");
        c.connect();
        InputStream in = c.getInputStream();
        BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = r.readLine()) != null) sb.append(line);
        r.close();
        c.disconnect();
        return sb.toString();
    }
}
