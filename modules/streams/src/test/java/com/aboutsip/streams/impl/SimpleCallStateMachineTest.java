/**
 * 
 */
package com.aboutsip.streams.impl;

import static com.aboutsip.streams.SipStream.CallState.COMPLETED;
import static com.aboutsip.streams.SipStream.CallState.INITIAL;
import static com.aboutsip.streams.SipStream.CallState.IN_CALL;
import static com.aboutsip.streams.SipStream.CallState.RINGING;
import static com.aboutsip.streams.SipStream.CallState.TRYING;
import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertThat;

import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.aboutsip.streams.SipStream.CallState;
import com.aboutsip.streams.StreamsTestBase;
import com.aboutsip.yajpcap.packet.sip.SipMessage;
import com.aboutsip.yajpcap.packet.sip.impl.SipParseException;

/**
 * @author jonas@jonasborjesson.com
 * 
 */
public class SimpleCallStateMachineTest extends StreamsTestBase {

    /**
     * @throws java.lang.Exception
     */
    @Override
    @Before
    public void setUp() throws Exception {
    }

    /**
     * @throws java.lang.Exception
     */
    @Override
    @After
    public void tearDown() throws Exception {
    }

    /**
     * Just a very basic call, which contains everything in the correct order.
     * Hence, no surprises...
     */
    @Test
    public void testBasicTransitions() throws Exception {
        final String resource = "simple_invite_scenario.pcap";
        SimpleCallStateMachine fsm = driveTraffic(resource);
        assertStates(fsm, INITIAL, TRYING, RINGING, IN_CALL, COMPLETED);

        final List<SipMessage> messages = loadMessages(resource);
        messages.set(1, null); // remove the 100 Trying
        fsm = driveTraffic(messages);
        assertStates(fsm, INITIAL, RINGING, IN_CALL, COMPLETED);

        messages.set(2, null); // remove the 180 Ringing
        fsm = driveTraffic(messages);
        assertStates(fsm, INITIAL, IN_CALL, COMPLETED);
    }

    /**
     * Since a pcap can have "half" dialogs in them, such as the first part of
     * the call is missing we need to be able to accommodate for that.
     * 
     * @throws Exception
     */
    @Test
    public void testMissingTraffic() throws Exception {
        final List<SipMessage> messages = loadMessages("simple_invite_scenario.pcap");
        messages.set(0, null); // remove the INVITE
        messages.set(1, null); // remove the 100 Trying
        SimpleCallStateMachine fsm = driveTraffic(messages);
        assertStates(fsm, RINGING, IN_CALL, COMPLETED);

        messages.set(2, null); // remove the 180
        messages.set(3, null); // remove the 200 as well
        fsm = driveTraffic(messages);
        assertStates(fsm, IN_CALL, COMPLETED);

        messages.set(4, null); // remove the ACK
        fsm = driveTraffic(messages);
        assertStates(fsm, COMPLETED);

        messages.set(5, null); // remove the BYE
        fsm = driveTraffic(messages);
        assertStates(fsm, COMPLETED);
    }

    /**
     * Make sure that if someone merges an newer pcap with an older traffic and
     * as such, the "missing" traffic now shows up then we should re-drive all
     * traffic again to move the state machine according to the full traffic.
     * 
     * @throws Exception
     */
    @Test
    public void testEarlierTrafficAppears() throws Exception {
        final List<SipMessage> messagesFirst = loadMessages("simple_invite_scenario.pcap");
        final List<SipMessage> messagesSecond = loadMessages("simple_invite_scenario.pcap");

        // scenario one, the INVITE didn't make it in the pcap
        // that we first push through the system but shows up in the second...
        wipeOutMessages(messagesFirst, 1, 2, 3, 4, 5, 6); // all but the INVITE
        wipeOutMessages(messagesSecond, 0); // only remove INVITE

        final SimpleCallStateMachine fsm = driveTraffic(messagesSecond);
        driveTraffic(fsm, messagesFirst);
        assertStates(fsm, INITIAL, TRYING, RINGING, IN_CALL, COMPLETED);
    }

    /**
     * Helper method for "deleting" a bunch of messages. They become "lost" so
     * to speak...
     * 
     * @param messages
     * @param toRemove
     */
    private void wipeOutMessages(final List<SipMessage> messages, final int... toRemove) {
        for (final int i : toRemove) {
            messages.set(i, null);
        }
    }

    /**
     * Even though it really doesn't make sense, make sure that nothing bad
     * happens if you try and access the transitions out of the
     * {@link SimpleCallStateMachine} before it has received any traffic.
     * 
     * @throws Exception
     */
    @Test
    public void testNoTraffic() throws Exception {
        final SimpleCallStateMachine fsm = new SimpleCallStateMachine("abcd-123");
        assertThat(fsm.getTransitions().isEmpty(), is(true));
    }

    /**
     * Convenience method for making sure that the
     * {@link SimpleCallStateMachine} made the transitions that we expected it
     * to.
     * 
     * @param fsm
     * @param expected
     */
    private void assertStates(final SimpleCallStateMachine fsm, final CallState... expected) {
        final List<CallState> actual = fsm.getTransitions();
        assertThat(actual.size(), is(expected.length));
        for (int i = 0; i < expected.length; ++i) {
            assertThat(actual.get(i), is(expected[i]));
        }
        // make sure that the current state is the same as our
        // last expected state
        assertThat(fsm.getCallState(), is(expected[expected.length - 1]));
    }

    /**
     * Helper method for pushing traffic through an already defined
     * {@link SimpleCallStateMachine}
     * 
     * @param fsm
     *            the {@link SimpleCallStateMachine} to push the traffic through
     * @param messages
     *            the traffic to push
     * @throws Exception
     *             boom!
     */
    private void driveTraffic(final SimpleCallStateMachine fsm, final List<SipMessage> messages) throws Exception {
        for (final SipMessage msg : messages) {
            if (msg != null) {
                fsm.onEvent(msg);
            }
        }
    }

    /**
     * Helper method for creating a new {@link SimpleCallStateMachine} and push
     * the traffic through it.
     * 
     * @param messages
     *            the {@link SipMessage}s to push through the state machine
     * @return the newly created state machine
     * @throws Exception
     */
    private SimpleCallStateMachine driveTraffic(final List<SipMessage> messages) throws Exception {
        final String callId = getCallId(messages);
        final SimpleCallStateMachine fsm = new SimpleCallStateMachine(callId);
        driveTraffic(fsm, messages);
        return fsm;
    }

    /**
     * Load {@link SipMessage}s from the specified resource (has to be a pcap
     * file) and then create a new {@link SimpleCallStateMachine} and push that
     * traffic through it.
     * 
     * @param resource
     * @return the newly created state machine
     * @throws Exception
     */
    private SimpleCallStateMachine driveTraffic(final String resource) throws Exception {
        final List<SipMessage> messages = loadMessages(resource);
        return driveTraffic(messages);
    }

    /**
     * Helper method for finding a call id from the list of {@link SipMessage}s.
     * 
     * @param messages
     * @return
     * @throws SipParseException
     */
    private String getCallId(final List<SipMessage> messages) throws SipParseException {
        for (final SipMessage msg : messages) {
            if (msg != null) {
                return msg.getCallIDHeader().getValue().toString();
            }
        }

        return "N/A";
    }

}
