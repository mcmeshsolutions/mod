package dev.mcmesh.core;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class DoctorTest {

    @Test
    void testDoctorChecksMemoryAndProtocol() {
        var mem = Doctor.checkMemory();
        assertNotNull(mem);
        assertTrue(mem.status() == Doctor.DiagnosticItem.Status.PASS || mem.status() == Doctor.DiagnosticItem.Status.WARN);

        var proto = Doctor.checkProtocol();
        assertEquals(Doctor.DiagnosticItem.Status.PASS, proto.status());

        var pack = Doctor.checkModpack("custom-pack", "1.2.0");
        assertEquals(Doctor.DiagnosticItem.Status.PASS, pack.status());
        assertTrue(pack.message().contains("custom-pack"));

        var missingGw = Doctor.checkGateway("");
        assertEquals(Doctor.DiagnosticItem.Status.FAIL, missingGw.status());
        assertNotNull(missingGw.fix());
    }

    @Test
    void testFullDiagnosisList() {
        List<Doctor.DiagnosticItem> items = Doctor.diagnose("http://127.0.0.1:9", "test", "1");
        assertEquals(5, items.size());
    }
}
