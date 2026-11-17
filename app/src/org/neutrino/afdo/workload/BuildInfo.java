package org.neutrino.afdo.workload;

import android.content.Context;
import android.content.pm.PackageManager;

final class BuildInfo {
    private BuildInfo() {}

    static String versionName(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "";
        }
    }
}
