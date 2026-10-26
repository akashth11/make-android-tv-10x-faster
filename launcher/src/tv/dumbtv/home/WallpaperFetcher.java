package tv.dumbtv.home;

import android.content.Context;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class WallpaperFetcher {
    private static final String BING_API = "https://www.bing.com/HPImageArchive.aspx?format=js&idx=0&n=8&mkt=en-US";
    private static final String PICSUM_URL = "https://picsum.photos/1920/1080";
    private static final Pattern URL_PATTERN = Pattern.compile("\"url\":\"([^\"]+)\"");
    private static volatile boolean fetching;

    private WallpaperFetcher() {}

    static void fetchRandom(Context c, File targetDir, Runnable onDone) {
        if (fetching || targetDir == null) return;
        fetching = true;
        new Thread(() -> {
            try {
                if (!targetDir.exists()) targetDir.mkdirs();
                String downloadUrl = fetchBingImageUrl();
                if (downloadUrl == null) {
                    downloadUrl = PICSUM_URL;
                }

                URL url = new URL(downloadUrl);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setInstanceFollowRedirects(true);
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(15000);
                conn.connect();
                int code = conn.getResponseCode();
                if (code == HttpURLConnection.HTTP_OK || code == HttpURLConnection.HTTP_MOVED_TEMP) {
                    InputStream in = conn.getInputStream();
                    File temp = new File(targetDir, "online_" + System.currentTimeMillis() + ".jpg");
                    FileOutputStream out = new FileOutputStream(temp);
                    byte[] buf = new byte[8192];
                    int len;
                    while ((len = in.read(buf)) != -1) {
                        out.write(buf, 0, len);
                    }
                    out.flush();
                    out.close();
                    in.close();

                    // Keep at most 8 online wallpapers to avoid disk bloat
                    File[] files = targetDir.listFiles((dir, name) -> name.startsWith("online_"));
                    if (files != null && files.length > 8) {
                        java.util.Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
                        for (int i = 0; i < files.length - 8; i++) {
                            files[i].delete();
                        }
                    }
                    if (onDone != null) onDone.run();
                }
                conn.disconnect();
            } catch (Throwable ignored) {
            } finally {
                fetching = false;
            }
        }).start();
    }

    private static String fetchBingImageUrl() {
        try {
            URL url = new URL(BING_API);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(6000);
            conn.setReadTimeout(8000);
            conn.connect();
            if (conn.getResponseCode() == HttpURLConnection.HTTP_OK) {
                InputStream in = conn.getInputStream();
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                byte[] buf = new byte[2048];
                int n;
                while ((n = in.read(buf)) != -1) baos.write(buf, 0, n);
                in.close();
                String json = baos.toString("UTF-8");
                Matcher m = URL_PATTERN.matcher(json);
                List<String> list = new ArrayList<>();
                while (m.find()) {
                    String part = m.group(1).replace("\\/", "/");
                    list.add("https://www.bing.com" + part);
                }
                if (!list.isEmpty()) {
                    int idx = (int) (System.currentTimeMillis() % list.size());
                    return list.get(idx);
                }
            }
            conn.disconnect();
        } catch (Throwable ignored) {}
        return null;
    }
}
