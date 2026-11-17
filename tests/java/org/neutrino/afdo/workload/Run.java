package org.neutrino.afdo.workload;

import java.io.File;

/** Host stub of the activity: a files directory and the main looper. */
public class Run {
    final File files;
    Run(File files) { this.files = files; }
    public File getFilesDir() { return files; }
    public android.os.Looper getMainLooper() { return android.os.Looper.MAIN; }
    void caseFinished(Case c) {}
}
