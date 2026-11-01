package ch.asds.mobile

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.URLEncoder

class LegacyOfflineTimeSyncTest {
    private fun queue(time: JSONObject, id: String = "off-time-stable-0001"): JSONObject {
        val body = "client_id=" + URLEncoder.encode(id, "UTF-8") +
            "&kind=time&payload=" + URLEncoder.encode(time.toString(), "UTF-8")
        return JSONObject().put("id", id)
            .put("url", "https://portal.ihre-wegbegleiterin.ch/custom/asds_mobile/mobile/offline_sync.php?stage=10")
            .put("body", body)
    }

    @Test fun offlineExtendedFieldsRequireCapableServer() {
        val row = queue(JSONObject().put("km", "3.35").put("billable_km", "3.35")
            .put("km_rate", "0.70").put("appointment_id", 42))
        assertTrue(LegacyOfflineTimeSync.requiresV201Server(row))
        assertFalse(LegacyOfflineTimeSync.supportsV201Server("""{"ok":true,"server_version":"2.0.0"}"""))
        assertTrue(LegacyOfflineTimeSync.supportsV201Server("""{"ok":true,"contract_version":"1.0","capabilities":{"features":{"billable_km_on_time":true}}}"""))
        assertFalse(LegacyOfflineTimeSync.requiresV201Server(queue(JSONObject().put("km", "0.00"))))
        assertTrue(LegacyOfflineTimeSync.requiresV201Server(queue(JSONObject().put("appointment_id", 42))))
    }

    @Test fun keepQueueOnRedirectInvalidJsonOrBusinessError() {
        val row = queue(JSONObject().put("km", "2.00"))
        assertEquals(LegacyOfflineTimeSync.Decision.HOLD,
            LegacyOfflineTimeSync.decision(row, 302, ""))
        assertEquals(LegacyOfflineTimeSync.Decision.TRANSIENT,
            LegacyOfflineTimeSync.decision(row, 200, "<html>Login</html>"))
        assertEquals(LegacyOfflineTimeSync.Decision.HOLD,
            LegacyOfflineTimeSync.decision(row, 409, """{"ok":false,"code":"DUPLICATE_MISMATCH"}"""))
        assertEquals(LegacyOfflineTimeSync.Decision.HOLD,
            LegacyOfflineTimeSync.decision(row, 200, """{"ok":false,"error":"not_saved"}"""))
        assertEquals(LegacyOfflineTimeSync.Decision.TRANSIENT,
            LegacyOfflineTimeSync.decision(row, 500, """{"ok":false}"""))
    }

    @Test fun confirmOnlyPersistedRowsLegacyAndR1Server() {
        val row = queue(JSONObject().put("billable_km", "3.35"))
        assertEquals(LegacyOfflineTimeSync.Decision.CONFIRMED,
            LegacyOfflineTimeSync.decision(row, 200, """{"ok":true,"target_id":123,"kind":"time"}"""))
        assertEquals(LegacyOfflineTimeSync.Decision.CONFIRMED,
            LegacyOfflineTimeSync.decision(row, 200, """{"ok":true,"status":"success","target_id":123}"""))
        assertEquals(LegacyOfflineTimeSync.Decision.TRANSIENT,
            LegacyOfflineTimeSync.decision(row, 200, """{"ok":true}"""))
    }
}
