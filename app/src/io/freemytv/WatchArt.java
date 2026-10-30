package io.freemytv;

import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.Socket;
import java.net.URL;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The "Continue watching" cards' pictures, decoded in the background once, at the card's size on screen (16:9,
 * centre-cropped), and kept in memory. Pictures already on the TV (content:// and android.resource://, from the apps
 * themselves) are read directly. Pictures the apps only wrote as https addresses (Netflix, Disney+) are downloaded,
 * under tight rules: https only, every host resolved before connecting and refused if it's the TV, the home network,
 * link-local, multicast, CGNAT or IPv6 ULA (the connected socket's real address is re-checked under TLS, closing the
 * gap between resolution and connect), redirects followed by hand (3 at most, each target re-checked), image bodies
 * of at most 3 MB, one plain User-Agent, no caches, and 20 s from start to finish (only the DNS lookup itself can't be
 * interrupted). A picture that can't be got shows the app's TV banner instead. Connection problems retry after 20 s,
 * three times (right after the TV starts, Wi-Fi comes later); refusals and other failures after 10 min.
 */
final class WatchArt {

    /** A refusal the rules or the server decided: no point retrying quickly (bad URL, bad response, not an image). */
    private static final class Refused extends IOException {
        Refused(String message) { super(message); }
    }

    /** The card in the 1920×1080 layout; the pictures are made at this times the UI's scale (setScale). */
    static final int W = 520, H = 292;
    private static final int MAX_BYTES = 8 << 20;
    /** At most this many pixels decoded (~8 MB): a 60000×300 picture is sampled down before it's decoded. */
    private static final long MAX_PIXELS = 2_000_000;

    /** Remote https pictures: image bodies of at most 3 MB, 6 s to connect, 8 s to read, 3 redirects followed by hand,
     * 20 s from start to finish. */
    private static final int NET_MAX_BYTES = 3 << 20;
    private static final int NET_CONNECT_MS = 6000, NET_READ_MS = 8000, NET_REDIRECTS = 3;
    private static final long NET_RETRY_MS = 20_000L, NET_TOTAL_MS = 20_000L;
    private static final String NET_USER_AGENT = "FreeMyTV";

    private final Context app;
    private final Runnable onLoaded;
    private final Handler main = new Handler(Looper.getMainLooper());
    /** One thread for the whole process: a home screen started over (new settings) doesn't leave one behind. */
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    /** Downloads get their own thread: one slow CDN can't hold up the rest. */
    private static final ExecutorService NET_IO = Executors.newSingleThreadExecutor();
    /** Cuts a connection at the deadline: DNS can't be interrupted, everything after it can. */
    private static final java.util.concurrent.ScheduledExecutorService WATCHDOG =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
    private volatile boolean closed;
    private volatile int pw = W, ph = H;   // the pictures' size in pixels
    private static final long RETRY_MS = 10 * 60_000L;
    private final Set<String> pending = new HashSet<>();
    /** Each failed key's retry deadline: now + RETRY_MS (on-TV pictures) or now + NET_RETRY_MS (downloads). */
    private final Map<String, Long> failed = new HashMap<>();
    /** How often a remote picture failed in a row; resets when it loads. */
    private final Map<String, Integer> attempts = new HashMap<>();
    /** One pending retry-redraw at a time; close() takes it back out of the queue so the instance can go. */
    private final Runnable retryRedraw;
    private final Map<String, Integer> colors = new HashMap<>();   // each picture's colour, for the background
    private final Map<Bitmap, BitmapShader> shaders = new HashMap<>();   // for the rounded corners, made with the picture
    private final Map<String, Bitmap> cache = new LinkedHashMap<String, Bitmap>(16, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Bitmap> eldest) {
            if (size() <= 16) return false;   // ~600 KB each at 1080p: the row and a little more
            shaders.remove(eldest.getValue());
            colors.remove(eldest.getKey());
            return true;
        }
    };

    WatchArt(Context c, Runnable onLoaded) {
        app = c.getApplicationContext();
        this.onLoaded = onLoaded;
        retryRedraw = () -> { if (!closed) onLoaded.run(); };
    }

    /**
     * The card's picture, or the app's banner if it has none or it failed; null while loading (in the background;
     * onLoaded runs on the main thread when it's in). A remote picture that is loading or retrying falls to the app's
     * banner right away: the card never sits blank between the retries.
     */
    Bitmap get(Uri uri, String key, String bannerKey, String pkg) {
        if (uri != null) {
            Bitmap b = fetch(key, uri, pkg);
            if (b != null || (!remote(uri) && !failedRecently(key))) return b;
        }
        return fetch(bannerKey, null, pkg);
    }

    /** The UI's scale (width / 1920): pictures already made at another size are made again. */
    void setScale(float k) {
        int width = Math.max(1, Math.round(W * k)), height = Math.max(1, Math.round(H * k));
        if (width == pw && height == ph) return;
        pw = width;
        ph = height;
        synchronized (cache) {
            cache.clear();
            shaders.clear();
        }
    }

    /** The picture's shader (get's pictures only). */
    BitmapShader shader(Bitmap b) {
        synchronized (cache) {
            return shaders.get(b);
        }
    }

    /** The colour of the card's picture (or of the banner in its place), or null until it's decoded. */
    Integer color(String key, String bannerKey) {
        synchronized (cache) {
            if (key != null) {
                Integer c = colors.get(key);
                if (c != null) return c;
                if (key.regionMatches(true, 0, "https:", 0, 6)) return colors.get(bannerKey);
                Long until = failed.get(key);
                if (until == null || System.currentTimeMillis() >= until) return c;
            }
            return colors.get(bannerKey);
        }
    }

    /** The home screen went away: nothing more is posted to it, and the pictures go. */
    void close() {
        closed = true;
        main.removeCallbacks(retryRedraw);
        synchronized (cache) {
            cache.clear();
            shaders.clear();
            colors.clear();
            attempts.clear();
        }
    }

    private boolean failedRecently(String uri) {
        synchronized (cache) {
            Long until = failed.get(uri);
            return until != null && System.currentTimeMillis() < until;
        }
    }

    private Bitmap fetch(String key, Uri uri, String pkg) {
        if (closed) return null;
        synchronized (cache) {
            Bitmap b = cache.get(key);
            if (b != null || pending.contains(key)) return b;
            Long until = failed.get(key);
            if (until != null && System.currentTimeMillis() < until) return null;
            pending.add(key);
        }
        (remote(uri) ? NET_IO : IO).execute(() -> {
            if (closed) return;
            Bitmap b = null;
            Exception failure = null;
            try {
                b = uri == null ? banner(pkg) : load(uri);
            } catch (OutOfMemoryError e) {
                AppLog.w("continue watching picture: out of memory");
            } catch (IOException | RuntimeException e) {
                failure = e;
                AppLog.w("continue watching picture: " + e.getMessage());
            }
            Integer color = b == null ? null : colorOf(b);
            BitmapShader shader = b == null ? null : new BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            synchronized (cache) {
                pending.remove(key);
                if (closed) return;
                if (b != null) {
                    failed.remove(key);
                    attempts.remove(key);
                    shaders.put(b, shader);
                    cache.put(key, b);
                    colors.put(key, color);
                } else {
                    boolean remotePicture = remote(uri);
                    long wait = RETRY_MS;
                    if (remotePicture) {
                        // A connection problem gets the short window (Wi-Fi comes after the screen, and the first few
                        // tries are cheap); a refusal is permanent for now and gets the long one.
                        boolean connectionProblem = failure != null && !(failure instanceof Refused);
                        Integer tries = attempts.get(key);
                        int n = tries == null ? 0 : tries;
                        wait = connectionProblem && n < 3 ? NET_RETRY_MS : RETRY_MS;
                        attempts.put(key, n + 1);
                    }
                    for (java.util.Iterator<Map.Entry<String, Long>> it = failed.entrySet().iterator(); it.hasNext(); ) {
                        if (System.currentTimeMillis() >= it.next().getValue()) it.remove();
                    }
                    failed.put(key, System.currentTimeMillis() + wait);
                    // Nobody redraws on their own: ask the home screen for a new pass when the retry window is over.
                    if (remotePicture) main.postDelayed(retryRedraw, wait);
                }
            }
            main.post(retryRedraw);   // the picture, or the banner in its place
        });
        return null;
    }

    private Bitmap load(Uri uri) throws IOException {
        byte[] data = remote(uri) ? download(uri) : read(uri);
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw new Refused("unreadable picture");
        int cw = pw, ch = ph, bw = bounds.outWidth, bh = bounds.outHeight;
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = 1;
        // Halved while both sides stay at least the card's, or while it's still too many pixels (either side too big).
        for (int s = 1; bw / (s * 2) >= cw && bh / (s * 2) >= ch || (long) (bw / s) * (bh / s) > MAX_PIXELS; s *= 2) {
            opts.inSampleSize = s * 2;
        }
        Bitmap src = BitmapFactory.decodeByteArray(data, 0, data.length, opts);
        if (src == null) throw new Refused("unreadable picture");
        // Centre crop to 16:9.
        int sw = src.getWidth(), sh = src.getHeight();
        Rect from = (long) sw * ch > (long) sh * cw ? new Rect((sw - sh * cw / ch) / 2, 0, (sw + sh * cw / ch) / 2, sh)
                : new Rect(0, (sh - sw * ch / cw) / 2, sw, (sh + sw * ch / cw) / 2);
        Bitmap out = Bitmap.createBitmap(cw, ch, Bitmap.Config.ARGB_8888);
        new Canvas(out).drawBitmap(src, from, new Rect(0, 0, cw, ch), new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG));
        src.recycle();
        return out;
    }

    /**
     * One colour for the whole picture, worked out once here in the background: the average of a tiny copy, with its
     * saturation lifted (a plain average comes out greyish) and its brightness kept in the middle.
     */
    private static int colorOf(Bitmap b) {
        Bitmap tiny = Bitmap.createScaledBitmap(b, 8, 5, true);
        int[] px = new int[40];
        tiny.getPixels(px, 0, 8, 0, 0, 8, 5);
        tiny.recycle();
        long r = 0, g = 0, bl = 0;
        for (int p : px) {
            r += (p >> 16) & 255;
            g += (p >> 8) & 255;
            bl += p & 255;
        }
        float[] hsv = new float[3];
        android.graphics.Color.RGBToHSV((int) (r / 40), (int) (g / 40), (int) (bl / 40), hsv);
        hsv[1] = Math.min(1, hsv[1] * 1.5f + 0.08f);
        hsv[2] = Math.max(0.45f, Math.min(0.85f, hsv[2]));
        return android.graphics.Color.HSVToColor(hsv);
    }

    /** The app's TV banner (or its icon) on a dark card, as the shelf shows apps without a logo. */
    private Bitmap banner(String pkg) throws IOException {
        PackageManager pm = app.getPackageManager();
        Drawable d;
        try {
            d = pm.getApplicationBanner(pkg);
            if (d == null) d = pm.getApplicationIcon(pkg);
        } catch (PackageManager.NameNotFoundException e) {
            throw new IOException("no app " + pkg);
        }
        int cw = pw, ch = ph;
        Bitmap out = Bitmap.createBitmap(cw, ch, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        c.drawColor(0xFF15161C);
        // TV banners are 16:9 already: edge to edge; an icon goes in the middle.
        boolean wide = d.getIntrinsicWidth() > d.getIntrinsicHeight();
        int s = Math.round(ch * 0.5f);
        if (wide) d.setBounds(0, 0, cw, ch);
        else d.setBounds((cw - s) / 2, (ch - s) / 2, (cw + s) / 2, (ch + s) / 2);
        d.draw(c);
        return out;
    }

    /** Only content:// and android.resource:// get here (https goes through download). */
    private byte[] read(Uri uri) throws IOException {
        try (InputStream in = app.getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IOException("no picture there");
            return readAll(in, MAX_BYTES, Long.MAX_VALUE);
        }
    }

    /** https addresses (Netflix & co) go through download; plain http stays refused. */
    private static boolean remote(Uri uri) {
        String s = uri != null ? uri.getScheme() : null;
        return "https".equalsIgnoreCase(s);
    }

    /** A URL without userinfo or backslash tricks: java.net.URL and other parsers don't agree on those. */
    private static URL checked(URL url) throws IOException {
        if (url.getUserInfo() != null) throw new Refused("userinfo refused");
        if (url.toString().indexOf('\\') >= 0) throw new Refused("backslash refused");
        return url;
    }

    private void checkDeadline(long deadline) throws IOException {
        if (closed) throw new IOException("closed");
        if (System.nanoTime() > deadline) throw new IOException("too slow");
    }

    /**
     * A remote https picture, under the rules in the class javadoc: the TV's own addresses are enumerated first
     * (trouble there refuses the download, fail closed), every resolved host is checked before connecting, the TLS
     * socket's real peer is checked again under handshake, redirects are followed by hand and re-checked, and the
     * whole attempt has one 20 s deadline.
     */
    private byte[] download(Uri uri) throws IOException {
        Set<InetAddress> own = ownAddresses();
        long deadline = System.nanoTime() + NET_TOTAL_MS * 1_000_000L;
        URL url = checked(new URL(uri.toString()));
        for (int redirects = 0; ; redirects++, checkDeadline(deadline)) {
            if (redirects > NET_REDIRECTS) throw new Refused("too many redirects");
            for (InetAddress a : InetAddress.getAllByName(url.getHost())) {
                if (!allowed(a, own)) throw new Refused("host refused: " + url.getHost());
            }
            checkDeadline(deadline);
            javax.net.ssl.HttpsURLConnection c = (javax.net.ssl.HttpsURLConnection)
                    url.openConnection(java.net.Proxy.NO_PROXY);
            final javax.net.ssl.HttpsURLConnection conn = c;
            final long remaining = Math.max(1_000L, deadline - System.nanoTime());
            java.util.concurrent.ScheduledFuture<?> watchdog =
                    WATCHDOG.schedule(conn::disconnect, remaining, java.util.concurrent.TimeUnit.NANOSECONDS);
            try {
                c.setInstanceFollowRedirects(false);
                c.setSSLSocketFactory(tls(own));
                c.setConnectTimeout(NET_CONNECT_MS);
                c.setReadTimeout(NET_READ_MS);
                c.setUseCaches(false);
                c.setRequestProperty("User-Agent", NET_USER_AGENT);
                int code = c.getResponseCode();
                if (code == HttpURLConnection.HTTP_MOVED_PERM || code == HttpURLConnection.HTTP_MOVED_TEMP
                        || code == HttpURLConnection.HTTP_SEE_OTHER || code == 307 || code == 308) {
                    String to = c.getHeaderField("Location");
                    if (to == null) throw new Refused("redirect without a target");
                    URL next = checked(new URL(url, to));
                    if (!"https".equalsIgnoreCase(next.getProtocol())) throw new Refused("redirect leaves https");
                    url = next;
                } else if (code != HttpURLConnection.HTTP_OK) {
                    throw new Refused("http " + code);
                } else {
                    if (c.getContentLengthLong() > NET_MAX_BYTES) throw new Refused("picture too large");
                    String type = c.getContentType();
                    if (type == null || !type.toLowerCase(Locale.ROOT).startsWith("image/"))
                        throw new Refused("not an image (" + type + ")");
                    try (InputStream in = c.getInputStream()) {
                        return readAll(in, NET_MAX_BYTES, deadline);
                    }
                }
            } finally {
                watchdog.cancel(false);
                c.disconnect();
            }
        }
    }

    /** TLS only on an allowed peer: the wrapped factory re-checks the socket's real address before the handshake. */
    private javax.net.ssl.SSLSocketFactory tls(final Set<InetAddress> own) {
        final javax.net.ssl.SSLSocketFactory base =
                (javax.net.ssl.SSLSocketFactory) javax.net.ssl.SSLSocketFactory.getDefault();
        return new javax.net.ssl.SSLSocketFactory() {
            @Override public String[] getDefaultCipherSuites() { return base.getDefaultCipherSuites(); }
            @Override public String[] getSupportedCipherSuites() { return base.getSupportedCipherSuites(); }
            @Override public java.net.Socket createSocket(Socket s, String host, int port, boolean autoClose)
                    throws IOException {
                if (!allowed(s.getInetAddress(), own)) throw new IOException("connected host refused: " + s.getInetAddress());
                return base.createSocket(s, host, port, autoClose);
            }
            @Override public java.net.Socket createSocket(String host, int port) throws IOException {
                throw new IOException("refused");
            }
            @Override public java.net.Socket createSocket(String host, int port, java.net.InetAddress localHost, int localPort)
                    throws IOException {
                throw new IOException("refused");
            }
            @Override public java.net.Socket createSocket(java.net.InetAddress host, int port) throws IOException {
                throw new IOException("refused");
            }
            @Override public java.net.Socket createSocket(java.net.InetAddress address, int port,
                    java.net.InetAddress localAddress, int localPort) throws IOException {
                throw new IOException("refused");
            }
        };
    }

    /** The TV's own addresses, from its interfaces; trouble enumerating refuses the download (fail closed). */
    private static Set<InetAddress> ownAddresses() throws IOException {
        try {
            Set<InetAddress> out = new HashSet<>();
            for (java.util.Enumeration<java.net.NetworkInterface> n = java.net.NetworkInterface.getNetworkInterfaces();
                    n.hasMoreElements(); ) {
                out.addAll(java.util.Collections.list(n.nextElement().getInetAddresses()));
            }
            return out;
        } catch (RuntimeException e) {
            throw new IOException("cannot enumerate the TV's addresses", e);
        }
    }

    /** Only global addresses pass: the TV (own), the home network and the reserved ranges stay closed. */
    private static boolean allowed(InetAddress a, Set<InetAddress> own) throws IOException {
        if (own.contains(a)) return false;
        if (a.isAnyLocalAddress() || a.isLoopbackAddress() || a.isLinkLocalAddress() || a.isMulticastAddress()
                || a.isSiteLocalAddress())   // 10/8, 172.16/12, 192.168/16
            return false;
        byte[] b = a.getAddress();
        if (b.length == 4) {
            int f = b[0] & 0xff;
            if (f == 0 || f >= 240) return false;   // 0/8 ("this network"), 240/4 (reserved, broadcast)
            if (f == 192 && (b[1] & 0xff) == 0 && (b[2] & 0xff) == 0) return false;   // 192.0.0/24
            if (f == 198 && (b[1] & 0xff) >= 18 && (b[1] & 0xff) <= 19) return false;   // 198.18/15 benchmarking
            if (f == 100 && (b[1] & 0xff) >= 64 && (b[1] & 0xff) <= 127) return false;   // CGNAT 100.64/10
        }
        if (b.length == 16) {
            int first = b[0] & 0xff;
            if (first >= 0xfc && first <= 0xfd) return false;   // IPv6 ULA fc00::/7
            boolean v4tail = true;   // ::/96 and ::ffff:0:0/96 carry an IPv4 in the tail: check it as IPv4
            for (int i = 0; i < 10; i++) if (b[i] != 0) { v4tail = false; break; }
            if (v4tail && (((b[10] & 0xff) == 0 && (b[11] & 0xff) == 0) || ((b[10] & 0xff) == 0xff && (b[11] & 0xff) == 0xff))) {
                return allowed(InetAddress.getByAddress(new byte[] {b[12], b[13], b[14], b[15]}), own);
            }
            if ((b[0] & 0xff) == 0 && (b[1] & 0xff) == 0x64 && (b[2] & 0xff) == 0xff && (b[3] & 0xff) == 0x9b
                    && (b[4] & 0xff) == 0 && (b[5] & 0xff) == 0x01) return false;   // 64:ff9b:1::/48, local use
            boolean nat64 = true;   // 64:ff9b::/96 (bytes 00 64 ff 9b) carries an IPv4 in the tail too: check it as one,
            for (int i = 4; i < 12; i++) if (b[i] != 0) { nat64 = false; break; }   // don't refuse the whole range
            if (nat64 && (b[0] & 0xff) == 0 && (b[1] & 0xff) == 0x64 && (b[2] & 0xff) == 0xff
                    && (b[3] & 0xff) == 0x9b) {
                return allowed(InetAddress.getByAddress(new byte[] {b[12], b[13], b[14], b[15]}), own);
            }
            if (first == 0x20 && (b[1] & 0xff) == 0x02) return false;   // 2002::/16 6to4
        }
        return true;
    }

    /** Streams a body of at most maxBytes, giving up when the deadline is past or the home screen went away. */
    private byte[] readAll(InputStream in, int maxBytes, long deadlineNano) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[16384];
        int n;
        while ((n = in.read(buffer)) > 0) {
            if (closed || System.nanoTime() > deadlineNano) throw new IOException("gave up");
            out.write(buffer, 0, n);
            if (out.size() > maxBytes) throw new Refused("picture too large");
        }
        return out.toByteArray();
    }
}
