package chat.stoat.air;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * AIR Chat: a signed event of the Nostr protocol (NIP-01), used to find peers and to reserve
 * usernames on public relays.
 */
public final class NostrEvent {
    public final String id;
    public final String pubkey;
    public final long createdAt;
    public final int kind;
    public final List<List<String>> tags;
    public final String content;
    public final String sig;

    public NostrEvent(String id, String pubkey, long createdAt, int kind, List<List<String>> tags, String content, String sig) {
        this.id = id;
        this.pubkey = pubkey;
        this.createdAt = createdAt;
        this.kind = kind;
        this.tags = Collections.unmodifiableList(new ArrayList<>(tags));
        this.content = content;
        this.sig = sig;
    }

    public static NostrEvent create(byte[] privateKey, int kind, List<List<String>> tags, String content) {
        return create(privateKey, kind, tags, content, System.currentTimeMillis() / 1000L);
    }

    public static NostrEvent create(byte[] privateKey, int kind, List<List<String>> tags, String content, long createdAt) {
        String pubkey = Secp256k1.hex(Secp256k1.publicKey(privateKey));
        byte[] idBytes = computeId(pubkey, createdAt, kind, tags, content);
        String sig = Secp256k1.hex(Secp256k1.signSchnorr(idBytes, privateKey));
        return new NostrEvent(Secp256k1.hex(idBytes), pubkey, createdAt, kind, tags, content, sig);
    }

    public static byte[] computeId(String pubkey, long createdAt, int kind, List<List<String>> tags, String content) {
        StringBuilder sb = new StringBuilder();
        sb.append("[0,").append(quote(pubkey)).append(',').append(createdAt).append(',').append(kind).append(',');
        appendTags(sb, tags);
        sb.append(',').append(quote(content)).append(']');
        try {
            return MessageDigest.getInstance("SHA-256").digest(sb.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public boolean verify() {
        try {
            byte[] expected = computeId(pubkey, createdAt, kind, tags, content);
            if (!Secp256k1.hex(expected).equals(id)) return false;
            return Secp256k1.verifySchnorr(expected, Secp256k1.unhex(pubkey), Secp256k1.unhex(sig));
        } catch (RuntimeException e) {
            return false;
        }
    }

    public String firstTag(String name) {
        for (List<String> tag : tags) {
            if (tag.size() >= 2 && tag.get(0).equals(name)) return tag.get(1);
        }
        return null;
    }

    public String toJson() {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"id\":").append(quote(id))
                .append(",\"pubkey\":").append(quote(pubkey))
                .append(",\"created_at\":").append(createdAt)
                .append(",\"kind\":").append(kind)
                .append(",\"tags\":");
        appendTags(sb, tags);
        sb.append(",\"content\":").append(quote(content))
                .append(",\"sig\":").append(quote(sig)).append('}');
        return sb.toString();
    }

    public static NostrEvent fromJson(org.json.JSONObject json) throws org.json.JSONException {
        List<List<String>> tags = new ArrayList<>();
        org.json.JSONArray rawTags = json.optJSONArray("tags");
        if (rawTags != null) {
            for (int i = 0; i < rawTags.length(); i++) {
                org.json.JSONArray t = rawTags.getJSONArray(i);
                List<String> tag = new ArrayList<>();
                for (int j = 0; j < t.length(); j++) tag.add(t.getString(j));
                tags.add(tag);
            }
        }
        return new NostrEvent(
                json.getString("id"),
                json.getString("pubkey"),
                json.getLong("created_at"),
                json.getInt("kind"),
                tags,
                json.getString("content"),
                json.getString("sig")
        );
    }

    private static void appendTags(StringBuilder sb, List<List<String>> tags) {
        sb.append('[');
        for (int i = 0; i < tags.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append('[');
            List<String> tag = tags.get(i);
            for (int j = 0; j < tag.size(); j++) {
                if (j > 0) sb.append(',');
                sb.append(quote(tag.get(j)));
            }
            sb.append(']');
        }
        sb.append(']');
    }

    /** JSON string escaping exactly as required by NIP-01 for event ids. */
    public static String quote(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 2);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        sb.append('"');
        return sb.toString();
    }
}
