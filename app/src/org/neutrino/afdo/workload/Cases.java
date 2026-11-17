package org.neutrino.afdo.workload;

/** The scenarios this app implements. */
final class Cases {
    private Cases() {}

    static Case create(Run a, Case.Params p) {
        switch (p.scenario) {
            case "ui-list": return new UiList(a, p);
            case "storage": return new Storage(a, p);
            case "ui-anim": return new UiAnim(a, p);
            case "ui-web": return new UiWeb(a, p);
            case "gpu": return new Gpu(a, p);
            case "video-local": return new Video(a, p, false);
            case "video-stream": return new Video(a, p, true);
            case "camera": return new Camera(a, p);
            case "audio-off": return new Audio(a, p);
            case "net-rpc": return new Net(a, p, false);
            case "net-bulk": return new Net(a, p, true);
            case "mem-fixture": return new Mem(a, p);
            case "ipc": return new Ipc(a, p);
            default: return new Unknown(a, p);
        }
    }

    /** A scenario this app version doesn't have: fails at once, with a result. */
    static final class Unknown extends Case {
        Unknown(Run a, Params p) { super(a, p); }
        @Override void prepare() { fail("unknown scenario " + p.scenario); }
        @Override void run() {}
        @Override void validate() {}
    }
}
