/*
 * Copyright (C) 2020 The Android Open Source Project
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

import static com.android.server.wifi.ActiveModeManager.ROLE_CLIENT_PRIMARY;
import static com.android.server.wifi.ActiveModeManager.ROLE_CLIENT_SECONDARY_LONG_LIVED;
import static com.android.server.wifi.ActiveModeManager.ROLE_CLIENT_SECONDARY_TRANSIENT;

import static org.mockito.Mockito.*;

import android.content.Context;
import android.os.WorkSource;

import androidx.test.filters.SmallTest;

import com.android.server.wifi.ActiveModeWarden.ModeChangeCallback;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Arrays;

/** Unit tests for {@link MakeBeforeBreakManager}. */
@SmallTest
public class MakeBeforeBreakManagerTest extends WifiBaseTest {

    @Mock private ActiveModeWarden mActiveModeWarden;
    @Mock private FrameworkFacade mFrameworkFacade;
    @Mock private Context mContext;
    @Mock private ConcreteClientModeManager mOldPrimaryCmm;
    @Mock private ConcreteClientModeManager mNewPrimaryCmm;
    @Mock private ConcreteClientModeManager mUnrelatedCmm;
    @Mock private WorkSource mSettingsWorkSource;
    @Mock private ClientModeImplMonitor mCmiMonitor;
    @Mock private ClientModeManagerBroadcastQueue mBroadcastQueue;
    @Mock private WifiMetrics mWifiMetrics;
    @Mock private Runnable mOnStoppedListener;
    @Captor private ArgumentCaptor<ModeChangeCallback> mModeChangeCallbackCaptor;
    @Captor private ArgumentCaptor<ClientModeImplListener> mCmiListenerCaptor;

    private MakeBeforeBreakManager mMbbManager;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        when(mActiveModeWarden.isMakeBeforeBreakEnabled()).thenReturn(true);
        when(mNewPrimaryCmm.getRole()).thenReturn(ROLE_CLIENT_SECONDARY_TRANSIENT);
        when(mFrameworkFacade.getSettingsWorkSource(mContext)).thenReturn(mSettingsWorkSource);
        when(mActiveModeWarden.getPrimaryClientModeManagerNullable()).thenReturn(mOldPrimaryCmm);
        when(mOldPrimaryCmm.getRole()).thenReturn(ROLE_CLIENT_PRIMARY);
        when(mActiveModeWarden.getClientModeManagersInRoles(ROLE_CLIENT_SECONDARY_TRANSIENT))
                .thenReturn(Arrays.asList(mNewPrimaryCmm));

        mMbbManager = new MakeBeforeBreakManager(mActiveModeWarden, mFrameworkFacade, mContext,
                mCmiMonitor, mBroadcastQueue, mWifiMetrics);

        verify(mActiveModeWarden).registerModeChangeCallback(mModeChangeCallbackCaptor.capture());
        verify(mCmiMonitor).registerListener(mCmiListenerCaptor.capture());
    }

    @Test
    public void makeBeforeBreakDisabled_noOp() {
        when(mActiveModeWarden.isMakeBeforeBreakEnabled()).thenReturn(false);

        mCmiListenerCaptor.getValue().onInternetValidated(mNewPrimaryCmm);
        mModeChangeCallbackCaptor.getValue().onActiveModeManagerRemoved(mNewPrimaryCmm);
        mModeChangeCallbackCaptor.getValue().onActiveModeManagerRoleChanged(mNewPrimaryCmm);
        mModeChangeCallbackCaptor.getValue().onActiveModeManagerAdded(mNewPrimaryCmm);

        verify(mNewPrimaryCmm, never()).setRole(any(), any());
    }

    @Test
    public void onL3ValidatedNonSecondaryTransient_noOp() {
        when(mNewPrimaryCmm.getRole()).thenReturn(ROLE_CLIENT_SECONDARY_LONG_LIVED);
        mCmiListenerCaptor.getValue().onInternetValidated(mNewPrimaryCmm);

        verify(mNewPrimaryCmm, never()).setRole(any(), any());
    }

    @Test
    public void onL3Validated_noPrimary_immediatelyMakeValidatedNetworkPrimary() {
        when(mActiveModeWarden.getPrimaryClientModeManagerNullable()).thenReturn(null);
        mCmiListenerCaptor.getValue().onInternetValidated(mNewPrimaryCmm);

        verify(mNewPrimaryCmm).setRole(ROLE_CLIENT_PRIMARY, mSettingsWorkSource);
    }

    @Test
    public void makeBeforeBreakSuccess() {
        mCmiListenerCaptor.getValue().onInternetValidated(mNewPrimaryCmm);

        verify(mOldPrimaryCmm).setRole(ROLE_CLIENT_SECONDARY_TRANSIENT,
                ActiveModeWarden.INTERNAL_REQUESTOR_WS);
        verify(mBroadcastQueue).fakeDisconnectionBroadcasts();

        when(mOldPrimaryCmm.getRole()).thenReturn(ROLE_CLIENT_SECONDARY_TRANSIENT);
        when(mActiveModeWarden.getClientModeManagersInRoles(ROLE_CLIENT_SECONDARY_TRANSIENT))
                .thenReturn(Arrays.asList(mNewPrimaryCmm, mOldPrimaryCmm));
        mModeChangeCallbackCaptor.getValue().onActiveModeManagerRoleChanged(mOldPrimaryCmm);

        verify(mNewPrimaryCmm).setRole(ROLE_CLIENT_PRIMARY, mSettingsWorkSource);
        verify(mOldPrimaryCmm).setShouldReduceNetworkScore(true);
    }

    @Test
    public void makeBeforeBreakEnded_mMakeBeforeBreakInfoCleared() {
        makeBeforeBreakSuccess();
        // only called once
        verify(mOldPrimaryCmm).setRole(any(), any());
        verify(mNewPrimaryCmm).setRole(any(), any());

        when(mActiveModeWarden.getPrimaryClientModeManagerNullable()).thenReturn(mNewPrimaryCmm);
        when(mNewPrimaryCmm.getRole()).thenReturn(ROLE_CLIENT_PRIMARY);
        when(mOldPrimaryCmm.getRole()).thenReturn(ROLE_CLIENT_SECONDARY_TRANSIENT);
        when(mActiveModeWarden.getClientModeManagersInRoles(ROLE_CLIENT_SECONDARY_TRANSIENT))
                .thenReturn(Arrays.asList(mOldPrimaryCmm));
        mModeChangeCallbackCaptor.getValue().onActiveModeManagerRoleChanged(mOldPrimaryCmm);

        // still only called once
        verify(mOldPrimaryCmm).setRole(any(), any());
        verify(mNewPrimaryCmm).setRole(any(), any());
    }

    @Test
    public void modeChanged_anotherCmm_noOp() {
        mCmiListenerCaptor.getValue().onInternetValidated(mNewPrimaryCmm);

        verify(mOldPrimaryCmm).setRole(ROLE_CLIENT_SECONDARY_TRANSIENT,
                ActiveModeWarden.INTERNAL_REQUESTOR_WS);

        when(mOldPrimaryCmm.getRole()).thenReturn(ROLE_CLIENT_SECONDARY_TRANSIENT);
        when(mActiveModeWarden.getClientModeManagersInRoles(ROLE_CLIENT_SECONDARY_TRANSIENT))
                .thenReturn(Arrays.asList(mNewPrimaryCmm, mOldPrimaryCmm));
        mModeChangeCallbackCaptor.getValue().onActiveModeManagerRoleChanged(mUnrelatedCmm);

        verify(mUnrelatedCmm, never()).setRole(any(), any());
        verify(mOldPrimaryCmm).setRole(any(), any());
        verify(mNewPrimaryCmm, never()).setRole(any(), any());
    }

    @Test
    public void modeChanged_noMakeBeforeBreak_noOp() {
        when(mOldPrimaryCmm.getRole()).thenReturn(ROLE_CLIENT_SECONDARY_TRANSIENT);
        when(mActiveModeWarden.getClientModeManagersInRoles(ROLE_CLIENT_SECONDARY_TRANSIENT))
                .thenReturn(Arrays.asList(mNewPrimaryCmm, mOldPrimaryCmm));
        mModeChangeCallbackCaptor.getValue().onActiveModeManagerRoleChanged(mOldPrimaryCmm);

        verify(mOldPrimaryCmm, never()).setRole(any(), any());
        verify(mNewPrimaryCmm, never()).setRole(any(), any());
    }

    @Test
    public void modeChanged_oldPrimaryDidntBecomeSecondaryTransient_abortMbb() {
        mCmiListenerCaptor.getValue().onInternetValidated(mNewPrimaryCmm);

        verify(mOldPrimaryCmm).setRole(ROLE_CLIENT_SECONDARY_TRANSIENT,
                ActiveModeWarden.INTERNAL_REQUESTOR_WS);

        // didn't become SECONDARY_TRANSIENT
        when(mOldPrimaryCmm.getRole()).thenReturn(ROLE_CLIENT_SECONDARY_LONG_LIVED);
        when(mActiveModeWarden.getClientModeManagersInRoles(ROLE_CLIENT_SECONDARY_TRANSIENT))
                .thenReturn(Arrays.asList(mNewPrimaryCmm));
        mModeChangeCallbackCaptor.getValue().onActiveModeManagerRoleChanged(mOldPrimaryCmm);

        // no-op, abort MBB
        verify(mOldPrimaryCmm).setRole(any(), any());
        verify(mNewPrimaryCmm, never()).setRole(any(), any());

        // became SECONDARY_TRANSIENT
        when(mOldPrimaryCmm.getRole()).thenReturn(ROLE_CLIENT_SECONDARY_TRANSIENT);
        when(mActiveModeWarden.getClientModeManagersInRoles(ROLE_CLIENT_SECONDARY_TRANSIENT))
                .thenReturn(Arrays.asList(mNewPrimaryCmm, mOldPrimaryCmm));
        mModeChangeCallbackCaptor.getValue().onActiveModeManagerRoleChanged(mOldPrimaryCmm);

        // but since aborted, still no-op
        verify(mOldPrimaryCmm).setRole(any(), any());
        verify(mNewPrimaryCmm, never()).setRole(any(), any());
    }

    @Test
    public void modeChanged_newPrimaryNoLongerSecondaryTransient_abortMbb() {
        mCmiListenerCaptor.getValue().onInternetValidated(mNewPrimaryCmm);

        verify(mOldPrimaryCmm).setRole(ROLE_CLIENT_SECONDARY_TRANSIENT,
                ActiveModeWarden.INTERNAL_REQUESTOR_WS);

        when(mOldPrimaryCmm.getRole()).thenReturn(ROLE_CLIENT_SECONDARY_TRANSIENT);
        // new primary's role became something else
        when(mNewPrimaryCmm.getRole()).thenReturn(ROLE_CLIENT_SECONDARY_LONG_LIVED);
        when(mActiveModeWarden.getClientModeManagersInRoles(ROLE_CLIENT_SECONDARY_TRANSIENT))
                .thenReturn(Arrays.asList(mOldPrimaryCmm));
        mModeChangeCallbackCaptor.getValue().onActiveModeManagerRoleChanged(mOldPrimaryCmm);

        // no-op, abort MBB
        verify(mOldPrimaryCmm).setRole(any(), any());
        verify(mNewPrimaryCmm, never()).setRole(any(), any());

        // both became SECONDARY_TRANSIENT
        when(mOldPrimaryCmm.getRole()).thenReturn(ROLE_CLIENT_SECONDARY_TRANSIENT);
        when(mNewPrimaryCmm.getRole()).thenReturn(ROLE_CLIENT_SECONDARY_TRANSIENT);
        when(mActiveModeWarden.getClientModeManagersInRoles(ROLE_CLIENT_SECONDARY_TRANSIENT))
                .thenReturn(Arrays.asList(mNewPrimaryCmm, mOldPrimaryCmm));
        mModeChangeCallbackCaptor.getValue().onActiveModeManagerRoleChanged(mOldPrimaryCmm);

        // but since aborted, still no-op
        verify(mOldPrimaryCmm).setRole(any(), any());
        verify(mNewPrimaryCmm, never()).setRole(any(), any());
    }

    @Test
    public void recovery() {
        when(mOldPrimaryCmm.getRole()).thenReturn(ROLE_CLIENT_SECONDARY_TRANSIENT);
        when(mActiveModeWarden.getClientModeManagersInRoles(ROLE_CLIENT_SECONDARY_TRANSIENT))
                .thenReturn(Arrays.asList(mNewPrimaryCmm, mOldPrimaryCmm));
        when(mActiveModeWarden.getPrimaryClientModeManagerNullable()).thenReturn(null);
        mModeChangeCallbackCaptor.getValue().onActiveModeManagerRoleChanged(mOldPrimaryCmm);

        verify(mNewPrimaryCmm).setRole(ROLE_CLIENT_PRIMARY, mSettingsWorkSource);
        verify(mOldPrimaryCmm).stop();
    }

    @Test
    public void stopAllSecondaryTransientCmms_noSecondaryTransientCmm_triggerImmediately() {
        when(mActiveModeWarden.getClientModeManagerInRole(ROLE_CLIENT_SECONDARY_TRANSIENT))
                .thenReturn(null);

        mMbbManager.stopAllSecondaryTransientClientModeManagers(mOnStoppedListener);

        verify(mOnStoppedListener).run();
    }

    @Test
    public void stopAllSecondaryTransientCmms_hasSecondaryTransientCmm_triggerAfterStopped() {
        when(mActiveModeWarden.getClientModeManagerInRole(ROLE_CLIENT_SECONDARY_TRANSIENT))
                .thenReturn(mNewPrimaryCmm);

        mMbbManager.stopAllSecondaryTransientClientModeManagers(mOnStoppedListener);

        verify(mOnStoppedListener, never()).run();

        when(mActiveModeWarden.getClientModeManagerInRole(ROLE_CLIENT_SECONDARY_TRANSIENT))
                .thenReturn(null);

        mModeChangeCallbackCaptor.getValue().onActiveModeManagerRemoved(mNewPrimaryCmm);

        verify(mOnStoppedListener).run();
    }
}
