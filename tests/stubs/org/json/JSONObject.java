package org.json;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/** Host stub: enough of org.json for Case (put, keys, opt, toString as real JSON). */
public class JSONObject {
    final Map<String, Object> m = new LinkedHashMap<>();
    public JSONObject put(String k, Object v) throws JSONException { m.put(k, v); return this; }
    public Iterator<String> keys() { return m.keySet().iterator(); }
    public Object opt(String k) { return m.get(k); }
    public String toString(int indent) throws JSONException { return toString(); }
    @Override public String toString() {
        StringBuilder b = new StringBuilder("{");
        for (Map.Entry<String, Object> e : m.entrySet())
            b.append(b.length() > 1 ? "," : "").append(quote(e.getKey())).append(':').append(quote(e.getValue()));
        return b.append('}').toString();
    }
    static String quote(Object v) {
        if (v == null) return "null";
        if (v instanceof Number || v instanceof Boolean || v instanceof JSONObject || v instanceof JSONArray) {
            if (v instanceof Double && (((Double) v).isNaN() || ((Double) v).isInfinite())) return "null";
            return v.toString();
        }
        StringBuilder b = new StringBuilder("\"");
        for (char c : v.toString().toCharArray()) {
            if (c == '"' || c == '\\') b.append('\\').append(c);
            else if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
            else b.append(c);
        }
        return b.append('"').toString();
    }
}
