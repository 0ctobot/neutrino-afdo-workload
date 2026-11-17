package android.content;

import java.util.HashMap;
import java.util.Map;

public class Intent {
    public final Map<String, Object> extras = new HashMap<>();
    public Intent put(String k, Object v) { extras.put(k, v); return this; }
    public String getStringExtra(String k) { Object v = extras.get(k); return v instanceof String ? (String) v : null; }
    public int getIntExtra(String k, int d) { Object v = extras.get(k); return v instanceof Integer ? (Integer) v : d; }
    public long getLongExtra(String k, long d) { Object v = extras.get(k); return v instanceof Long ? (Long) v : d; }
}
