package dev.mcmesh.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SleepSignalTest {
    private final SleepSignal s = new SleepSignal();

    private static String dbusHeader(String member) {
        return "signal time=1794692114.766276 sender=:1.3 -> destination=(null destination) serial=1234 "
                + "path=/org/freedesktop/login1; interface=org.freedesktop.login1.Manager; member=" + member;
    }

    private static String gdbus(String member, boolean arg) {
        return "/org/freedesktop/login1: org.freedesktop.login1.Manager." + member + " (" + arg + ",)";
    }

    @Test
    void dbusMonitorPutsTheArgumentOnTheNextLine() {
        assertFalse(s.accept(dbusHeader("PrepareForSleep")));
        assertTrue(s.accept("   boolean true"));
    }

    @Test
    void shutdownCountsTheSameAsSuspend() {
        assertFalse(s.accept(dbusHeader("PrepareForShutdown")));
        assertTrue(s.accept("   boolean true"));
    }

    @Test
    void theWakeUpHalfOfTheSignalIsIgnored() {
        assertFalse(s.accept(dbusHeader("PrepareForSleep")));
        assertFalse(s.accept("   boolean false"));
        assertFalse(s.accept("   boolean true"), "a later stray argument must not fire");
    }

    @Test
    void gdbusPutsTheArgumentOnTheHeaderLine() {
        assertTrue(s.accept(gdbus("PrepareForSleep", true)));
        assertFalse(s.accept(gdbus("PrepareForSleep", false)));
    }

    @Test
    void anotherSignalInBetweenCancelsThePendingArgument() {
        assertFalse(s.accept(dbusHeader("PrepareForSleep")));
        assertFalse(s.accept(dbusHeader("SessionNew")));
        assertFalse(s.accept("   string \":1.98\""));
        assertFalse(s.accept("   boolean true"));
    }

    @Test
    void unrelatedTrafficNeverFires() {
        assertFalse(s.accept(null));
        assertFalse(s.accept(""));
        assertFalse(s.accept("   boolean true"));
        assertFalse(s.accept("signal time=1 sender=org.freedesktop.DBus -> destination=:1.98 member=NameAcquired"));
        assertFalse(s.accept("   string \":1.98\""));
    }
}
