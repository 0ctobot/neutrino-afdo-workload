package org.neutrino.afdo.workload;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/** RUN, VALIDATE and ABORT for the live case named by --es key (DUMP holders only). */
public class Ctl extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction() == null ? "" : intent.getAction();
        String key = intent.getStringExtra("key");
        Case c = key == null ? null : Case.live(key);
        if (c == null || !c.p.run.equals(intent.getStringExtra("run"))) {
            Log.w(Case.TAG, "no live case " + key + " for " + action);
            return;
        }
        String verb = action.substring(action.lastIndexOf('.') + 1);
        switch (verb) {
            case "RUN": c.main.post(c::onRun); break;
            case "VALIDATE": c.main.post(c::onValidate); break;
            case "ABORT": c.main.post(c::onAbort); break;
            default: Log.w(Case.TAG, "unknown action " + action);
        }
    }
}
