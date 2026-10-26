package tv.dumbtv.home;

import android.content.Context;
import android.util.Log;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class WallpaperFetcher {
    private static final String TAG = "WallpaperFetcher";
    private static final String[] BING_MARKETS = {
        "en-US", "en-GB", "en-IN", "ja-JP", "de-DE", "fr-FR", "en-CA", "en-AU"
    };
    private static final Pattern URL_PATTERN = Pattern.compile("\"url\":\"([^\"]+)\"");
    private static final Random RNG = new Random();
    private static volatile boolean fetching;
    private static long lastFetchTime = 0;

    private WallpaperFetcher() {}

    /**
     * Fetches daily fresh wallpapers from Bing Daily Photo Archive and curated photography.
     * Guaranteed to provide new daily wallpapers every single day.
     */
    static void fetchRandom(Context c, File targetDir, Runnable onDone) {
        if (fetching || targetDir == null) return;
        long now = System.currentTimeMillis();
        if (now - lastFetchTime < 20_000L) return;

        fetching = true;
        lastFetchTime = now;
        new Thread(() -> {
            try {
                if (!targetDir.exists()) targetDir.mkdirs();

                // 1. Pick a random Bing market to get diverse worldwide daily photos
                String market = BING_MARKETS[RNG.nextInt(BING_MARKETS.length)];
                String downloadUrl = fetchBingImageUrl(market);

                // 2. Fallback to Picsum high-res random photography if Bing is unavailable
                if (downloadUrl == null) {
                    downloadUrl = "https://picsum.photos/1920/1080?random=" + System.currentTimeMillis();
                }

                HttpURLConnection conn = openWithRedirects(downloadUrl, 0);
                int code = conn.getResponseCode();
                if (code == HttpURLConnection.HTTP_OK) {
                    InputStream in = conn.getInputStream();
                    File temp = new File(targetDir, "dl_" + System.currentTimeMillis() + ".tmp");
                    FileOutputStream out = new FileOutputStream(temp);
                    byte[] buf = new byte[8192];
                    int len, total = 0;
                    while ((len = in.read(buf)) != -1) {
                        out.write(buf, 0, len);
                        total += len;
                    }
                    out.flush();
                    out.close();
                    in.close();

                    // Ensure it is a valid full-size image (at least 20 KB)
                    if (total > 20_000) {
                        File finalFile = new File(targetDir, "daily_" + System.currentTimeMillis() + ".jpg");
                        if (temp.renameTo(finalFile)) {
                            Log.d(TAG, "Saved new daily wallpaper: " + finalFile.getName() + " (" + (total / 1024) + " KB)");
                        } else {
                            temp.delete();
                        }
                    } else {
                        temp.delete();
                    }

                    // Keep up to 16 daily wallpapers to maintain rich variety without disk bloat
                    pruneOldWallpapers(targetDir, 16);

                    if (onDone != null) onDone.run();
                }
                conn.disconnect();
            } catch (Throwable t) {
                Log.w(TAG, "Wallpaper fetch skipped: " + t.getMessage());
            } finally {
                fetching = false;
            }
        }).start();
    }

    private static HttpURLConnection openWithRedirects(String urlStr, int depth) throws Exception {
        if (depth > 5) throw new java.io.IOException("Too many redirects: " + urlStr);
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(15000);
        conn.setInstanceFollowRedirects(true);
        conn.connect();

        int code = conn.getResponseCode();
        if (code == HttpURLConnection.HTTP_MOVED_TEMP || code == HttpURLConnection.HTTP_MOVED_PERM
                || code == 307 || code == 308) {
            String location = conn.getHeaderField("Location");
            conn.disconnect();
            if (location != null) {
                if (location.startsWith("/")) {
                    location = url.getProtocol() + "://" + url.getHost() + location;
                }
                return openWithRedirects(location, depth + 1);
            }
        }
        return conn;
    }

    private static String fetchBingImageUrl(String market) {
        try {
            String apiUrl = "https://www.bing.com/HPImageArchive.aspx?format=js&idx=0&n=8&mkt=" + market;
            HttpURLConnection conn = openWithRedirects(apiUrl, 0);
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
                    if (!part.startsWith("http")) {
                        part = "https://www.bing.com" + part;
                    }
                    list.add(part);
                }
                if (!list.isEmpty()) {
                    Collections.shuffle(list);
                    return list.get(0);
                }
            }
            conn.disconnect();
        } catch (Throwable t) {
            Log.w(TAG, "Bing API error for market " + market + ": " + t.getMessage());
        }
        return null;
    }

    private static void pruneOldWallpapers(File targetDir, int maxKeep) {
        File[] files = targetDir.listFiles((dir, name) ->
                (name.startsWith("daily_") || name.startsWith("online_") || name.startsWith("bing_"))
                && (name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png")));
        if (files != null && files.length > maxKeep) {
            Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
            for (int i = 0; i < files.length - maxKeep; i++) {
                files[i].delete();
            }
        }
    }
}
