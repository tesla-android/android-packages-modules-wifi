/*
 * Copyright (C) 2012 The Android Open Source Project
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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.compat.annotation.UnsupportedAppUsage;
import android.net.wifi.util.HexEncoding;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Stores SSID octets and handles conversion.
 *
 * For Ascii encoded string, any octet < 32 or > 127 is encoded as
 * a "\x" followed by the hex representation of the octet.
 * Exception chars are ", \, \e, \n, \r, \t which are escaped by a \
 * See src/utils/common.c for the implementation in the supplicant.
 *
 * @hide
 */
public final class WifiSsid implements Parcelable {
    private static final String TAG = "WifiSsid";

    @UnsupportedAppUsage
    public final ByteArrayOutputStream octets = new ByteArrayOutputStream(32);

    private static final int HEX_RADIX = 16;

    @UnsupportedAppUsage
    public static final String NONE = WifiManager.UNKNOWN_SSID;

    private WifiSsid() {
    }

    /**
     * Create a WifiSsid from a raw byte array. If the byte array is null, return an empty WifiSsid
     * object.
     */
    @NonNull
    public static WifiSsid createFromByteArray(@Nullable byte[] ssid) {
        WifiSsid wifiSsid = new WifiSsid();
        if (ssid != null) {
            wifiSsid.octets.write(ssid, 0 /* the start offset */, ssid.length);
        }
        return wifiSsid;
    }

    @UnsupportedAppUsage
    public static WifiSsid createFromAsciiEncoded(String asciiEncoded) {
        WifiSsid a = new WifiSsid();
        a.convertToBytes(asciiEncoded);
        return a;
    }

    public static WifiSsid createFromHex(String hexStr) {
        WifiSsid a = new WifiSsid();
        if (hexStr == null) return a;

        if (hexStr.startsWith("0x") || hexStr.startsWith("0X")) {
            hexStr = hexStr.substring(2);
        }

        for (int i = 0; i < hexStr.length()-1; i += 2) {
            int val;
            try {
                val = Integer.parseInt(hexStr.substring(i, i + 2), HEX_RADIX);
            } catch(NumberFormatException e) {
                val = 0;
            }
            a.octets.write(val);
        }
        return a;
    }

    /* This function is equivalent to printf_decode() at src/utils/common.c in
     * the supplicant */
    private void convertToBytes(String asciiEncoded) {
        int i = 0;
        int val = 0;
        while (i< asciiEncoded.length()) {
            char c = asciiEncoded.charAt(i);
            switch (c) {
                case '\\':
                    i++;
                    switch(asciiEncoded.charAt(i)) {
                        case '\\':
                            octets.write('\\');
                            i++;
                            break;
                        case '"':
                            octets.write('"');
                            i++;
                            break;
                        case 'n':
                            octets.write('\n');
                            i++;
                            break;
                        case 'r':
                            octets.write('\r');
                            i++;
                            break;
                        case 't':
                            octets.write('\t');
                            i++;
                            break;
                        case 'e':
                            octets.write(27); //escape char
                            i++;
                            break;
                        case 'x':
                            i++;
                            try {
                                val = Integer.parseInt(asciiEncoded.substring(i, i + 2), HEX_RADIX);
                            } catch (NumberFormatException e) {
                                val = -1;
                            } catch (StringIndexOutOfBoundsException e) {
                                val = -1;
                            }
                            if (val < 0) {
                                val = Character.digit(asciiEncoded.charAt(i), HEX_RADIX);
                                if (val < 0) break;
                                octets.write(val);
                                i++;
                            } else {
                                octets.write(val);
                                i += 2;
                            }
                            break;
                        case '0':
                        case '1':
                        case '2':
                        case '3':
                        case '4':
                        case '5':
                        case '6':
                        case '7':
                            val = asciiEncoded.charAt(i) - '0';
                            i++;
                            if (asciiEncoded.charAt(i) >= '0' && asciiEncoded.charAt(i) <= '7') {
                                val = val * 8 + asciiEncoded.charAt(i) - '0';
                                i++;
                            }
                            if (asciiEncoded.charAt(i) >= '0' && asciiEncoded.charAt(i) <= '7') {
                                val = val * 8 + asciiEncoded.charAt(i) - '0';
                                i++;
                            }
                            octets.write(val);
                            break;
                        default:
                            break;
                    }
                    break;
                default:
                    octets.write(c);
                    i++;
                    break;
            }
        }
    }

    /**
     * If the SSID is encoded with UTF-8, this method returns the decoded SSID as plaintext.
     * Otherwise, it returns {@code null}.
     * @return the SSID
     */
    @Nullable
    public CharSequence getUtf8Text() {
        byte[] ssidBytes = octets.toByteArray();
        return decodeSsid(ssidBytes, StandardCharsets.UTF_8);
    }

    /**
     * Returns the string representation of the WifiSsid. If the SSID can be decoded as UTF-8, it
     * will be returned in plain text surrounded by double quotation marks. Otherwise, it is
     * returned as an unquoted string of hex digits. This format is consistent with
     * {@link WifiInfo#getSSID()} and {@link WifiConfiguration#SSID}.
     *
     * @return SSID as double-quoted plain text from UTF-8 or unquoted hex digits
     */
    @Override
    @NonNull
    public String toString() {
        byte[] ssidBytes = octets.toByteArray();
        String utf8String = decodeSsid(ssidBytes, StandardCharsets.UTF_8);
        if (TextUtils.isEmpty(utf8String)) {
            return HexEncoding.encodeToString(ssidBytes);
        }
        return "\"" + utf8String + "\"";
    }

    /**
     * Returns the given SSID bytes as a String decoded using the given Charset. If the bytes cannot
     * be decoded, then this returns {@code null}.
     * @param ssidBytes SSID as bytes
     * @param charset Charset to decode with
     * @return SSID as string, or {@code null}.
     */
    @Nullable
    private static String decodeSsid(@NonNull byte[] ssidBytes, @NonNull Charset charset) {
        CharsetDecoder decoder = charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        CharBuffer out = CharBuffer.allocate(32);
        CoderResult result = decoder.decode(ByteBuffer.wrap(ssidBytes), out, true);
        out.flip();
        if (result.isError()) {
            return null;
        }
        return out.toString();
    }

    @Override
    public boolean equals(Object thatObject) {
        if (this == thatObject) {
            return true;
        }
        if (!(thatObject instanceof WifiSsid)) {
            return false;
        }
        WifiSsid that = (WifiSsid) thatObject;
        return Arrays.equals(octets.toByteArray(), that.octets.toByteArray());
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(octets.toByteArray());
    }

    /** @hide */
    @UnsupportedAppUsage
    public byte[] getOctets() {
        return octets.toByteArray();
    }

    /** Implement the Parcelable interface */
    @Override
    public int describeContents() {
        return 0;
    }

    /** Implement the Parcelable interface */
    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(octets.size());
        dest.writeByteArray(octets.toByteArray());
    }

    /** Implement the Parcelable interface */
    @UnsupportedAppUsage
    public static final @NonNull Creator<WifiSsid> CREATOR =
            new Creator<WifiSsid>() {
                @Override
                public WifiSsid createFromParcel(Parcel in) {
                    WifiSsid ssid = new WifiSsid();
                    int length = in.readInt();
                    byte[] b = new byte[length];
                    in.readByteArray(b);
                    ssid.octets.write(b, 0, length);
                    return ssid;
                }

                @Override
                public WifiSsid[] newArray(int size) {
                    return new WifiSsid[size];
                }
            };
}
