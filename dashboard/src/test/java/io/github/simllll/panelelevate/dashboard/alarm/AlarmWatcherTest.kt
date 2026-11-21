package io.github.simllll.panelelevate.dashboard.alarm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AlarmWatcherTest {
    private val id = "alarm_control_panel.alarmanlage"
    private fun parse(json: String) = AlarmWatcher.parseState(Json.parseToJsonElement(json).jsonObject, id)

    @Test
    fun initialState() = assertEquals("disarmed", parse("""{"a":{"$id":{"s":"disarmed","a":{},"c":"x","lc":1.0}}}"""))

    @Test
    fun changedState() = assertEquals("triggered", parse("""{"c":{"$id":{"+":{"s":"triggered","lc":2.0}}}}"""))

    @Test
    fun attributeOnlyChangeKeepsState() = assertNull(parse("""{"c":{"$id":{"+":{"a":{"changed_by":"x"}}}}}"""))

    @Test
    fun otherEntityIgnored() = assertNull(parse("""{"a":{"light.x":{"s":"on"}}}"""))

    @Test
    fun activeStates() {
        assertTrue(AlarmWatcher.isActive("triggered"))
        assertTrue(AlarmWatcher.isActive("pending"))
        assertFalse(AlarmWatcher.isActive("armed_away"))
        assertFalse(AlarmWatcher.isActive(null))
    }
}
