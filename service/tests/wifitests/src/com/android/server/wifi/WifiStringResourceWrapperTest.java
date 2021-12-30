/*
 * Copyright (C) 2021 The Android Open Source Project
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

import static com.android.dx.mockito.inline.extended.ExtendedMockito.mockitoSession;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.validateMockitoUsage;
import static org.mockito.Mockito.when;

import android.content.res.Resources;
import android.telephony.SubscriptionManager;

import androidx.test.filters.SmallTest;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.MockitoSession;

/**
 * Unit tests for {@link WifiStringResourceWrapper}
 */
@SmallTest
public class WifiStringResourceWrapperTest extends WifiBaseTest {
    private MockitoSession mStaticMockSession = null;

    @Mock WifiContext mContext;
    @Mock Resources mResources;

    WifiStringResourceWrapper mDut;

    private static final int SUB_ID = 123;
    private static final int ID_1 = 32764;
    private static final String NAME_1 = "Name";
    private static final String STRING_1 = "Some message";

    /**
     * Sets up for unit test
     */
    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        // static mocking
        mStaticMockSession = mockitoSession()
                .mockStatic(SubscriptionManager.class)
                .startMocking();
        lenient().when(SubscriptionManager.getResourcesForSubId(any(), eq(SUB_ID)))
                .thenReturn(mResources);

        when(mResources.getIdentifier(eq(NAME_1), eq("string"), any())).thenReturn(ID_1);
        when(mResources.getString(eq(ID_1), any())).thenReturn(STRING_1);

        mDut = new WifiStringResourceWrapper(mContext, SUB_ID);
    }

    @After
    public void cleanUp() throws Exception {
        validateMockitoUsage();
        if (mStaticMockSession != null) {
            mStaticMockSession.finishMocking();
        }
    }

    @Test
    public void testBasicOperations() {
        assertEquals(STRING_1, mDut.getString(NAME_1));
        assertNull(mDut.getString("something else"));
    }
}
