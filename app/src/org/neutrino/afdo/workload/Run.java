package org.neutrino.afdo.workload;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.WindowManager;

/**
 * The activity a case runs in.  suite.sh starts it with the case's
 * parameters (am start -n .../.Run --es run ... --es scenario ...); the app
 * then reports through the case directory and takes RUN/VALIDATE/ABORT from
 * Ctl.  It can show over the lock screen (the screen-off tail).
 */
public class Run extends Activity {
    private Case current;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        handle(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handle(intent);
    }

    private void handle(Intent intent) {
        Case.Params p;
        try {
            p = new Case.Params(intent);
        } catch (IllegalArgumentException e) {
            Log.e(Case.TAG, "rejected: " + e.getMessage());
            return;
        }
        if (current != null) current.fail("superseded by " + p.key());
        current = Cases.create(this, p);
        current.start();
    }

    void caseFinished(Case c) {
        if (c == current) current = null;
    }

    @Override
    protected void onDestroy() {
        if (current != null) current.fail("activity destroyed");
        super.onDestroy();
    }
}
