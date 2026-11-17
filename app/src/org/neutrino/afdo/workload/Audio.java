package org.neutrino.afdo.workload;

import android.content.Intent;
import android.content.res.AssetFileDescriptor;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.PowerManager;

/**
 * audio-off: music-style playback of a local AAC file with the screen off.
 * The foreground service (mediaPlayback), the player and audio focus are set
 * up while the app is on top; the runner then turns the screen off and sends
 * RUN, and the player plays (looping, at a low player volume: no global
 * volume is changed) until the deadline.  Evidence: playback position
 * advancing across loops, the device the player is routed to (sampled every
 * second: the route must not change during the case, so a run compares one
 * audio path; which one is recorded), routing callbacks, errors and focus
 * losses (an interruption fails the case).
 */
final class Audio extends Case {
    private MediaPlayer player;
    private AudioManager am;
    private AudioFocusRequest focus;
    private int duration, lastPos, routeChanges, focusLosses, errors, samples;
    private long playedMs;
    private String route = "";
    private final java.util.Set<String> routes = new java.util.LinkedHashSet<>();

    Audio(Run a, Params p) { super(a, p); }

    @Override
    void prepare() {
        am = activity.getSystemService(AudioManager.class);
        AudioService.onStarted = () -> main.post(this::preparePlayer);
        activity.startForegroundService(new Intent(activity, AudioService.class));
    }

    private void preparePlayer() {
        try {
            AudioAttributes aa = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build();
            player = new MediaPlayer();
            player.setAudioAttributes(aa);
            try (AssetFileDescriptor fd = activity.getAssets().openFd("media/tone.m4a")) {
                player.setDataSource(fd);
            }
            player.setLooping(true);
            player.setVolume(0.15f, 0.15f);
            player.setWakeMode(activity, PowerManager.PARTIAL_WAKE_LOCK);
            player.setOnErrorListener((mp, what, extra) -> { errors++; problems.add("player error " + what + "/" + extra); return true; });
            player.addOnRoutingChangedListener(r -> routeChanges++, worker);
            player.prepare();
            duration = player.getDuration();
            focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(aa)
                    .setOnAudioFocusChangeListener(c -> { if (c < 0 && runStartNs != 0 && runEndNs == 0) focusLosses++; }, worker)
                    .build();
            if (am.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) { fail("audio focus not granted"); return; }
            put(requested, "asset", "media/tone.m4a");
            put(requested, "player_volume", 0.15);
            ready();
        } catch (Exception e) {
            fail("player: " + e);
        }
    }

    @Override
    void run() {
        player.start();
        lastPos = player.getCurrentPosition();
        sample();
    }

    // Once a second (light): playback position across loops and the route.
    private void sample() {
        main.postDelayed(() -> {
            if (cancelled) return;
            int pos = player.getCurrentPosition();
            playedMs += pos >= lastPos ? pos - lastPos : duration - lastPos + pos;
            lastPos = pos;
            samples++;
            AudioDeviceInfo d = player.getRoutedDevice();
            if (d != null) { route = d.getType() + " " + d.getProductName(); routes.add(route); }
            if (pastDeadline()) done(); else sample();
        }, 1000);
    }

    @Override
    boolean stop() {
        if (player != null) { try { player.pause(); } catch (IllegalStateException ignored) {} }
        return true;
    }

    @Override
    void validate() {
        double s = activeSeconds();
        put(completed, "played_seconds", playedMs / 1000.0);
        put(evidence, "route", route);
        put(evidence, "routes", new org.json.JSONArray(routes));
        put(evidence, "route_callbacks", routeChanges);
        put(evidence, "focus_losses", focusLosses);
        put(evidence, "errors", errors);
        put(evidence, "samples", samples);
        need(playedMs / 1000.0 >= 0.9 * s, "played " + playedMs / 1000.0 + " s in " + s + " s");
        need(!route.isEmpty(), "the player was never routed to a device");
        need(routes.size() <= 1, "the audio route changed during the case: " + routes);
        need(focusLosses == 0, "playback was interrupted (audio focus lost " + focusLosses + " time(s))");
        need(errors == 0, errors + " player error(s)");
    }

    @Override
    void cleanup() {
        if (player != null) { try { player.stop(); } catch (IllegalStateException ignored) {} player.release(); }
        if (am != null && focus != null) am.abandonAudioFocusRequest(focus);
        AudioService.onStarted = null;
        activity.stopService(new Intent(activity, AudioService.class));
    }
}
