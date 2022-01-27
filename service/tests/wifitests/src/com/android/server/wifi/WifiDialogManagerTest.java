/*
 * Copyright 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.wifi;


import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.net.wifi.WifiContext;

import androidx.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Unit tests for {@link WifiDialogManager}.
 */
@SmallTest
public class WifiDialogManagerTest extends WifiBaseTest {

    @Mock WifiContext mWifiContext;
    @Mock WifiThreadRunner mCallbackThreadRunner;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        when(mWifiContext.getWifiDialogApkPkgName()).thenReturn("WifiDialogApkPkgName");
        when(mCallbackThreadRunner.post(any(Runnable.class))).then(i -> {
            ((Runnable) i.getArguments()[0]).run();
            return true;
        });
    }

    /**
     * Verifies that launching a P2P Invitation Received dialog with a callback will result in the
     * correct callback methods invoked when a response is received.
     */
    @Test
    public void testP2pInvitationReceivedDialog_launchAndResponse_notifiesCallback() {
        WifiDialogManager wifiDialogManager = new WifiDialogManager(mWifiContext);
        WifiDialogManager.P2pInvitationReceivedDialogCallback callback =
                mock(WifiDialogManager.P2pInvitationReceivedDialogCallback.class);
        int dialogId;

        // Accept without PIN
        dialogId = wifiDialogManager.launchP2pInvitationReceivedDialog(
                "deviceName", false, null, callback, mCallbackThreadRunner);
        wifiDialogManager.replyToP2pInvitationReceivedDialog(dialogId, true, null);
        verify(callback, times(1)).onAccepted(null);

        // Callback should be removed from callback list, so a second notification should be ignored
        wifiDialogManager.replyToP2pInvitationReceivedDialog(dialogId, true, "012345");
        verify(callback, times(0)).onAccepted("012345");

        // Accept with PIN
        dialogId = wifiDialogManager.launchP2pInvitationReceivedDialog(
                "deviceName", true, null, callback, mCallbackThreadRunner);
        wifiDialogManager.replyToP2pInvitationReceivedDialog(dialogId, true, "012345");
        verify(callback, times(1)).onAccepted("012345");

        // Accept with PIN but PIN was not requested
        dialogId = wifiDialogManager.launchP2pInvitationReceivedDialog(
                "deviceName", false, null, callback, mCallbackThreadRunner);
        wifiDialogManager.replyToP2pInvitationReceivedDialog(dialogId, true, "012345");
        verify(callback, times(2)).onAccepted("012345");

        // Accept without PIN but PIN was requested
        dialogId = wifiDialogManager.launchP2pInvitationReceivedDialog(
                "deviceName", true, null, callback, mCallbackThreadRunner);
        wifiDialogManager.replyToP2pInvitationReceivedDialog(dialogId, true, null);
        verify(callback, times(2)).onAccepted(null);

        // Decline without PIN
        dialogId = wifiDialogManager.launchP2pInvitationReceivedDialog(
                "deviceName", false, null, callback, mCallbackThreadRunner);
        wifiDialogManager.replyToP2pInvitationReceivedDialog(dialogId, false, null);
        verify(callback, times(1)).onDeclined();

        // Decline with PIN
        dialogId = wifiDialogManager.launchP2pInvitationReceivedDialog(
                "deviceName", true, null, callback, mCallbackThreadRunner);
        wifiDialogManager.replyToP2pInvitationReceivedDialog(dialogId, false, "012345");
        verify(callback, times(2)).onDeclined();

        // Decline with PIN but PIN was not requested
        dialogId = wifiDialogManager.launchP2pInvitationReceivedDialog(
                "deviceName", false, null, callback, mCallbackThreadRunner);
        wifiDialogManager.replyToP2pInvitationReceivedDialog(dialogId, false, "012345");
        verify(callback, times(3)).onDeclined();

        // Decline without PIN but PIN was requested
        dialogId = wifiDialogManager.launchP2pInvitationReceivedDialog(
                "deviceName", true, null, callback, mCallbackThreadRunner);
        wifiDialogManager.replyToP2pInvitationReceivedDialog(dialogId, false, null);
        verify(callback, times(4)).onDeclined();
    }

    /**
     * Verifies the right callback is notified for a response to a P2P Invitation Received dialog.
     */
    @Test
    public void testP2pInvitationReceivedDialog_multipleDialogs_responseMatchedToCorrectCallback() {
        WifiDialogManager wifiDialogManager = new WifiDialogManager(mWifiContext);

        // Launch Dialog1
        WifiDialogManager.P2pInvitationReceivedDialogCallback callback1 = mock(
                WifiDialogManager.P2pInvitationReceivedDialogCallback.class);
        int dialogId1 = wifiDialogManager.launchP2pInvitationReceivedDialog(
                "deviceName", false, null, callback1, mCallbackThreadRunner);

        // Launch Dialog2
        WifiDialogManager.P2pInvitationReceivedDialogCallback callback2 = mock(
                WifiDialogManager.P2pInvitationReceivedDialogCallback.class);
        int dialogId2 = wifiDialogManager.launchP2pInvitationReceivedDialog(
                "deviceName", false, null, callback2, mCallbackThreadRunner);

        // callback1 notified
        wifiDialogManager.replyToP2pInvitationReceivedDialog(dialogId1, true, null);
        verify(callback1, times(1)).onAccepted(null);
        verify(callback2, times(0)).onAccepted(null);

        // callback2 notified
        wifiDialogManager.replyToP2pInvitationReceivedDialog(dialogId2, true, null);
        verify(callback1, times(1)).onAccepted(null);
        verify(callback2, times(1)).onAccepted(null);
    }
}
