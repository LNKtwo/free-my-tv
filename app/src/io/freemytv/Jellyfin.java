package io.freemytv;

import android.content.Context;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * Picks from your own Jellyfin server (config.properties "jellyfin_url"; tools/jellyfin_login.py signs in and sends the
 * token to the TV's Secrets, never into the APK, which any app on the TV can read): what you're in the middle of, then what's new in your library. "Watch" opens the title's
 * page in the Jellyfin app. The server is on your network, so these need neither a TMDB key nor TMDB itself.
 */
final class Jellyfin {
    private Jellyfin() {}

    static final String PKG = "org.jellyfin.androidtv";
    private static final String SERVER = Config.JELLYFIN_URL.trim().replaceAll("/+$", "");
    /** The token is a small file read, and on() can run often: it is read again at most this often. */
    private static final long TOKEN_EVERY = 30_000L;
    private static volatile String token = "";
    private static volatile long tokenAt = -TOKEN_EVERY;

    private static String token(Context c) {
        long now = SystemClock.elapsedRealtime();
        if (now - tokenAt >= TOKEN_EVERY) {
            token = Secrets.get(c, Secrets.JELLYFIN_TOKEN);
            tokenAt = now;
        }
        return token;
    }

    static boolean on(Context c) {
        return !SERVER.isEmpty() && !token(c).isEmpty();
    }

    /** Up to 4 unfinished titles, then the newest movies and shows, 12 in all; only those with a wide photo. */
    static List<Recs.Pick> picks(Context c) throws Exception {
        String token = token(c);
        List<Recs.Pick> out = new ArrayList<>();
        String fields = "&fields=Genres&enableUserData=false&imageTypeLimit=1";
        JSONArray resume = new JSONObject(get("/UserItems/Resume?limit=4&mediaTypes=Video" + fields, token)).getJSONArray("Items");
        for (int i = 0; i < resume.length(); i++) add(out, resume.getJSONObject(i), true);
        // Episodes come grouped by show (a Series item), or alone when just one is new.
        JSONArray latest = new JSONArray(get("/Items/Latest?limit=16&includeItemTypes=Movie,Episode" + fields, token));
        for (int i = 0; i < latest.length() && out.size() < 12; i++) add(out, latest.getJSONObject(i), false);
        return out;
    }

    private static void add(List<Recs.Pick> out, JSONObject o, boolean resume) {
        String type = o.optString("Type");
        boolean episode = type.equals("Episode");
        if (!episode && !type.equals("Movie") && !type.equals("Series")) return;
        String title = episode ? o.optString("SeriesName", o.optString("Name")) : o.optString("Name");
        for (Recs.Pick p : out) if (p.title.equals(title)) return;   // the show you're watching is often the newest too
        // The home screen needs a wide photo: the title's own backdrop, or for an episode its show's.
        String imageItem = o.optString("Id");
        String tag = first(o.optJSONArray("BackdropImageTags"));
        if (tag == null) {
            imageItem = o.optString("ParentBackdropItemId");
            tag = first(o.optJSONArray("ParentBackdropImageTags"));
        }
        if (tag == null || imageItem.isEmpty()) return;
        Recs.Pick p = new Recs.Pick();
        p.item = o.optString("Id");
        p.resume = resume;
        p.kind = type.equals("Movie") ? "movie" : "tv";
        p.title = title;
        p.backdrop = SERVER + "/Items/" + imageItem + "/Images/Backdrop/0?maxWidth=1920&quality=90&tag=" + tag;
        List<String> parts = new ArrayList<>();
        parts.add(resume ? Ui.tr("Continue Watching") : Ui.tr("New in Your Library"));
        if (episode && o.has("ParentIndexNumber") && o.has("IndexNumber")) {
            parts.add("S" + o.optInt("ParentIndexNumber") + ":E" + o.optInt("IndexNumber"));
        }
        JSONArray genres = o.optJSONArray("Genres");
        if (genres != null && genres.length() > 0) parts.add(genres.optString(0));
        parts.add("Jellyfin");
        p.meta = String.join(" · ", parts);
        p.app = "jellyfin";
        p.pkg = PKG;
        p.detailed = true;
        out.add(p);
    }

    private static String first(JSONArray a) {
        return a == null || a.length() == 0 ? null : a.optString(0, null);
    }

    private static String get(String path, String token) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(SERVER + path).openConnection();
        c.setConnectTimeout(5000);
        c.setReadTimeout(8000);
        c.setInstanceFollowRedirects(false);   // HttpURLConnection keeps custom headers across hosts: the token must not follow a redirect
        c.setRequestProperty("Accept", "application/json");
        c.setRequestProperty("Authorization", "MediaBrowser Token=\"" + token + "\"");
        try {
            int code = c.getResponseCode();
            if (code == 401) throw new IOException("Jellyfin rejected the token (run tools/jellyfin_login.py again)");
            if (code != 200) throw new IOException("Jellyfin returned HTTP " + code);
            return Recs.read(c.getInputStream());
        } finally {
            c.disconnect();
        }
    }
}
