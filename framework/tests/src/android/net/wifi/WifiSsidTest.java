/*
 * Copyright (C) 2017 The Android Open Source Project
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

package android.net.wifi;

import static com.google.common.truth.Truth.assertThat;

import android.net.wifi.util.HexEncoding;

import androidx.test.filters.SmallTest;

import org.junit.Test;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * Unit tests for {@link android.net.wifi.WifiSsid}.
 */
@SmallTest
public class WifiSsidTest {

    private static final String TEST_SSID_UTF_8 = "Test SSID";
    private static final String TEST_SSID_UTF_8_QUOTED = "\"" + TEST_SSID_UTF_8 + "\"";
    private static final byte[] TEST_SSID_UTF_8_BYTES =
            TEST_SSID_UTF_8.getBytes(StandardCharsets.UTF_8);

    private static final byte[] TEST_SSID_NON_UTF_8_BYTES =
            "服務集識別碼".getBytes(Charset.forName("GBK"));
    private static final String TEST_SSID_NON_UTF_8_HEX =
            HexEncoding.encodeToString(TEST_SSID_NON_UTF_8_BYTES);

    /**
     * Check that createFromByteArray() works.
     */
    @Test
    public void testCreateFromByteArray() {
        WifiSsid wifiSsidUtf8 = WifiSsid.createFromByteArray(TEST_SSID_UTF_8_BYTES);
        assertThat(wifiSsidUtf8).isNotNull();
        assertThat(wifiSsidUtf8.getOctets()).isEqualTo(TEST_SSID_UTF_8_BYTES);
        assertThat(wifiSsidUtf8.getUtf8Text()).isEqualTo(TEST_SSID_UTF_8);
        assertThat(wifiSsidUtf8.toString()).isEqualTo(TEST_SSID_UTF_8_QUOTED);

        WifiSsid wifiSsidNonUtf8 = WifiSsid.createFromByteArray(TEST_SSID_NON_UTF_8_BYTES);
        assertThat(wifiSsidNonUtf8).isNotNull();
        assertThat(wifiSsidNonUtf8.getOctets()).isEqualTo(TEST_SSID_NON_UTF_8_BYTES);
        assertThat(wifiSsidNonUtf8.getUtf8Text()).isNull();
        assertThat(wifiSsidNonUtf8.toString()).isEqualTo(TEST_SSID_NON_UTF_8_HEX);
    }

    /**
     * Verify that SSID created from byte array and string with the same content are equal.
     *
     * @throws Exception
     */
    @Test
    public void testEquals() throws Exception {
        WifiSsid fromBytesUtf8 = WifiSsid.createFromByteArray(TEST_SSID_UTF_8_BYTES);
        WifiSsid fromStringUtf8 = WifiSsid.createFromAsciiEncoded(TEST_SSID_UTF_8);
        assertThat(fromBytesUtf8).isNotNull();
        assertThat(fromStringUtf8).isNotNull();
        assertThat(fromBytesUtf8).isEqualTo(fromStringUtf8);

        WifiSsid fromBytesNonUtf8 = WifiSsid.createFromByteArray(TEST_SSID_NON_UTF_8_BYTES);
        WifiSsid fromStringNonUtf8 = WifiSsid.createFromHex(TEST_SSID_NON_UTF_8_HEX);
        assertThat(fromBytesNonUtf8).isNotNull();
        assertThat(fromStringNonUtf8).isNotNull();
        assertThat(fromBytesNonUtf8).isEqualTo(fromStringNonUtf8);
    }
}
