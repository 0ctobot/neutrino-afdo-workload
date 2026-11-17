package org.json;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public class JSONArray {
    final List<Object> v = new ArrayList<>();
    public JSONArray(Collection<?> c) { v.addAll(c); }
    @Override public String toString() {
        StringBuilder b = new StringBuilder("[");
        for (int i = 0; i < v.size(); i++) b.append(i > 0 ? "," : "").append(JSONObject.quote(v.get(i)));
        return b.append(']').toString();
    }
}
