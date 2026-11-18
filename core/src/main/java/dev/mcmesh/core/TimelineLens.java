package dev.mcmesh.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Manages timeline scrubbing and holographic diff lens state.
 * Allows scrubbing backwards through historical CAS revisions to inspect base evolution,
 * damage timelines, or compare live blocks against older snapshots.
 */
public final class TimelineLens {
    private final List<String> revisions = new ArrayList<>();
    private int currentIndex = -1;
    private boolean diffOverlayActive = false;

    public TimelineLens() {}

    public synchronized void setRevisions(List<String> revs) {
        revisions.clear();
        if (revs != null) {
            revisions.addAll(revs);
        }
        currentIndex = revisions.isEmpty() ? -1 : 0;
    }

    public synchronized void selectRevision(String revId) {
        int idx = revisions.indexOf(revId);
        if (idx != -1) {
            currentIndex = idx;
        } else {
            // Append or switch directly
            revisions.add(revId);
            currentIndex = revisions.size() - 1;
        }
        diffOverlayActive = true;
    }

    public synchronized void setDiffOverlay(boolean active) {
        this.diffOverlayActive = active;
    }

    public synchronized boolean isDiffOverlayActive() {
        return diffOverlayActive && currentIndex >= 0 && currentIndex < revisions.size();
    }

    public synchronized String getActiveRevision() {
        if (currentIndex >= 0 && currentIndex < revisions.size()) {
            return revisions.get(currentIndex);
        }
        return null;
    }

    public synchronized boolean stepBack() {
        if (currentIndex > 0) {
            currentIndex--;
            return true;
        }
        return false;
    }

    public synchronized boolean stepForward() {
        if (currentIndex + 1 < revisions.size()) {
            currentIndex++;
            return true;
        }
        return false;
    }

    public synchronized void reset() {
        currentIndex = revisions.isEmpty() ? -1 : revisions.size() - 1;
        diffOverlayActive = false;
    }

    public synchronized String getStatusLine() {
        if (!diffOverlayActive || currentIndex < 0) {
            return null;
        }
        return String.format("Timeline Diff: %s (%d/%d)", revisions.get(currentIndex), currentIndex + 1, revisions.size());
    }

    public synchronized List<String> getRevisions() {
        return Collections.unmodifiableList(revisions);
    }
}
