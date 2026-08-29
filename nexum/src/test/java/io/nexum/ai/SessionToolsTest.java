package io.nexum.ai;

import io.nexum.transport.RecordingTransport;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * That an agent can run the sessions, not just the orders on them.
 *
 * <p>An agent given order tools alone is stuck the moment a session is down:
 * the order goes nowhere and the reason is invisible. These are the operations
 * a person performs on an ordinary day.
 */
class SessionToolsTest {

    private static final String SESSION = "OMS->LSE";

    private RecordingTransport transport;
    private Map<String, AiTool> tools;

    @BeforeEach
    void setUp() {
        transport = new RecordingTransport(SESSION, "OMS->FUNDX");
        tools = new java.util.LinkedHashMap<>();
        new SessionTools(transport).all().forEach(tool -> tools.put(tool.name(), tool));
    }

    @Test
    @DisplayName("an agent can see which sessions exist and whether they are up")
    void sessionsAreVisible() {
        AiTool.Result listed = call("list_sessions", Map.of());

        assertTrue(listed.ok());
        assertTrue(listed.content().contains(SESSION), listed.content());
        assertTrue(listed.content().contains("logged on"), listed.content());
    }

    @Test
    @DisplayName("a session that is down says so")
    void aDownSessionIsReported() {
        transport.takeDown(SESSION);

        AiTool.Result status = call("session_status", Map.of("sessionId", SESSION));

        // The word an agent has to be able to find when an order goes nowhere.
        assertTrue(status.content().contains("DOWN"), status.content());
        assertEquals(Boolean.FALSE, status.data().get("loggedOn"));
    }

    @Test
    @DisplayName("logging on brings a session back")
    void logonBringsASessionUp() {
        transport.takeDown(SESSION);
        assertFalse(transport.isLoggedOn(SESSION));

        AiTool.Result done = call("logon_session", Map.of("sessionId", SESSION));

        assertTrue(done.ok(), done.content());
        assertTrue(transport.isLoggedOn(SESSION));
        assertEquals("logon", transport.controls().get(0).action());
    }

    @Test
    @DisplayName("logging out carries a reason to the counterparty")
    void logoutCarriesItsReason() {
        AiTool.Result done = call("logout_session",
                Map.of("sessionId", SESSION, "reason", "end of day"));

        assertTrue(done.ok(), done.content());
        assertFalse(transport.isLoggedOn(SESSION));

        // Text(58) is what makes the counterparty's log say what happened
        // rather than showing an unexplained disconnect.
        RecordingTransport.Control control = transport.controls().get(0);
        assertEquals("logout", control.action());
        assertEquals("end of day", control.detail());
    }

    @Test
    @DisplayName("a sequence reset is carried out and reported")
    void resettingSequenceNumbers() {
        AiTool.Result done = call("reset_session_sequence", Map.of("sessionId", SESSION));

        assertTrue(done.ok(), done.content());
        assertEquals("reset", transport.controls().get(0).action());

        // No sequence numbers in the reply: a reset logs the session out and
        // back on, and the numbers do not land until that completes. Reporting
        // the pre-reset values reads as the reset having failed.
        assertFalse(done.data().containsKey("nextSenderSeqNum"), done.content());
        assertTrue(done.content().contains("session_status"), done.content());
    }

    @Test
    @DisplayName("the reset tool warns that both sides must do it")
    void theResetToolSaysWhatItCosts() {
        // A reset on one side alone leaves the two disagreeing about every
        // message from then on. The model reads this before deciding.
        String described = tools.get("reset_session_sequence").description();
        assertTrue(described.contains("COUNTERPARTY MUST RESET"), described);
    }

    @Test
    @DisplayName("a targeted resequence sets only what was asked for")
    void resequencingIsTargeted() {
        AiTool.Result done = call("set_session_sequence",
                Map.of("sessionId", SESSION, "nextSender", 42));

        assertTrue(done.ok(), done.content());
        assertEquals(42, done.data().get("nextSenderSeqNum"));

        RecordingTransport.Control control = transport.controls().get(0);
        assertEquals("resequence", control.action());
        assertTrue(control.detail().contains("sender=42"), control.detail());
        assertTrue(control.detail().contains("target=null"),
                "an omitted number should be left alone: " + control.detail());
    }

    @Test
    @DisplayName("a resequence that changes nothing is refused")
    void anEmptyResequenceIsRefused() {
        // Silently doing nothing would report success for a call that had none.
        AiTool.Result done = call("set_session_sequence", Map.of("sessionId", SESSION));

        assertFalse(done.ok());
        assertTrue(transport.controls().isEmpty());
    }

    @Test
    @DisplayName("naming a session that does not exist is refused, not ignored")
    void anUnknownSessionIsRefused() {
        for (String name : List.of(
                "logon_session", "logout_session", "disconnect_session",
                "reset_session_sequence", "session_status")) {

            AiTool.Result done = call(name, Map.of("sessionId", "OMS->NOWHERE"));
            assertFalse(done.ok(), name + " should have refused: " + done.content());
        }
        assertTrue(transport.controls().isEmpty(),
                "nothing should have been done to any session");
    }

    @Test
    @DisplayName("acting on a session is gated like sending an order")
    void actingIsMarkedAsSuchi() {
        // A client decides whether to ask a person first from this.
        assertEquals(AiTool.Effect.READ_ONLY, tools.get("list_sessions").effect());
        assertEquals(AiTool.Effect.READ_ONLY, tools.get("session_status").effect());

        for (String name : List.of(
                "logon_session", "logout_session", "disconnect_session",
                "reset_session_sequence", "set_session_sequence")) {
            assertEquals(AiTool.Effect.SENDS_TO_VENUE, tools.get(name).effect(), name);
        }
    }

    // ------------------------------------------------------------------

    private AiTool.Result call(String name, Map<String, Object> arguments) {
        AiTool tool = tools.get(name);
        if (tool == null) {
            throw new AssertionError("no tool " + name + "; have " + tools.keySet());
        }
        return tool.call(arguments);
    }
}
