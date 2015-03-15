package com.android.server.wifi.hotspot2;

import android.net.wifi.ScanResult;

import com.android.server.wifi.anqp.ANQPElement;
import com.android.server.wifi.anqp.Constants;
import com.android.server.wifi.anqp.VenueNameElement;

import java.net.ProtocolException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static com.android.server.wifi.anqp.Constants.BYTES_IN_EUI48;
import static com.android.server.wifi.anqp.Constants.BYTE_MASK;
import static com.android.server.wifi.anqp.Constants.getInteger;

public class NetworkDetail {

    private static final int EID_SSID = 0;
    private static final int EID_BSSLoad = 11;
    private static final int EID_Interworking = 107;
    private static final int EID_RoamingConsortium = 111;
    private static final int EID_ExtendedCaps = 127;
    private static final int EID_VSA = 221;
    private static final int ANQP_DOMID_BIT = 0x04;

    private static final long SSID_UTF8_BIT = 0x0001000000000000L;

    public enum Ant {
        Private,
        PrivateWithGuest,
        ChargeablePublic,
        FreePublic,
        Personal,
        EmergencyOnly,
        Resvd6,
        Resvd7,
        Resvd8,
        Resvd9,
        Resvd10,
        Resvd11,
        Resvd12,
        Resvd13,
        TestOrExperimental,
        Wildcard
    }

    public enum HSRelease {
        R1,
        R2,
        Unknown
    }

    // General identifiers:
    private final String mSSID;
    private final long mHESSID;
    private final long mBSSID;

    // BSS Load element:
    private final int mStationCount;
    private final int mChannelUtilization;
    private final int mCapacity;

    /*
     * From Interworking element:
     * mAnt non null indicates the presence of Interworking, i.e. 802.11u
     * mVenueGroup and mVenueType may be null if not present in the Interworking element.
     */
    private final Ant mAnt;
    private final boolean mInternet;
    private final VenueNameElement.VenueGroup mVenueGroup;
    private final VenueNameElement.VenueType mVenueType;

    /*
     * From HS20 Indication element:
     * mHSRelease is null only if the HS20 Indication element was not present.
     * mAnqpDomainID is set to -1 if not present in the element.
     */
    private final HSRelease mHSRelease;
    private final int mAnqpDomainID;

    /*
     * From beacon:
     * mAnqpOICount is how many additional OIs are available through ANQP.
     * mRoamingConsortiums is either null, if the element was not present, or is an array of
     * 1, 2 or 3 longs in which the roaming consortium values occupy the LSBs.
     */
    private final int mAnqpOICount;
    private final long[] mRoamingConsortiums;

    private final Long mExtendedCapabilities;

    private final Map<Constants.ANQPElementType, ANQPElement> mANQPElements;

    public NetworkDetail(String bssid, String infoElements, List<String> anqpLines) {

        if (infoElements == null) {
            throw new IllegalArgumentException("Null information element string");
        }
        int separator = infoElements.indexOf('=');
        if (separator<0) {
            throw new IllegalArgumentException("No element separator");
        }

        mBSSID = parseMac(bssid);

        ByteBuffer data = ByteBuffer.wrap(Utils.hexToBytes(infoElements.substring(separator + 1)))
                .order(ByteOrder.LITTLE_ENDIAN);

        String ssid = null;
        byte[] ssidOctets = null;
        int stationCount = 0;
        int channelUtilization = 0;
        int capacity = 0;

        Ant ant = null;
        boolean internet = false;
        VenueNameElement.VenueGroup venueGroup = null;
        VenueNameElement.VenueType venueType = null;
        long hessid = 0L;

        int anqpOICount = 0;
        long[] roamingConsortiums = null;

        HSRelease hsRelease = null;
        int anqpDomainID = -1;

        Long extendedCapabilities = null;

        while (data.hasRemaining()) {
            int eid = data.get() & Constants.BYTE_MASK;
            int elementLength = data.get() & Constants.BYTE_MASK;
            if (elementLength > data.remaining()) {
                throw new IllegalArgumentException("Length out of bounds: " + elementLength);
            }

            switch (eid) {
                case EID_SSID:
                    ssidOctets = new byte[elementLength];
                    data.get(ssidOctets);
                    break;
                case EID_BSSLoad:
                    if (elementLength != 5) {
                        throw new IllegalArgumentException("BSS Load element length is not 5: " +
                                elementLength);
                    }
                    stationCount = data.getShort() & Constants.SHORT_MASK;
                    channelUtilization = data.get() & Constants.BYTE_MASK;
                    capacity = data.getShort() & Constants.SHORT_MASK;
                    break;
                case EID_Interworking:
                    int anOptions = data.get() & Constants.BYTE_MASK;
                    ant = Ant.values()[anOptions&0x0f];
                    internet = ( anOptions & 0x10 ) != 0;
                    // Len 1 none, 3 venue-info, 7 HESSID, 9 venue-info & HESSID
                    if (elementLength == 3 || elementLength == 9) {
                        try {
                            ByteBuffer vinfo = data.duplicate();
                            vinfo.limit(vinfo.position() + 2);
                            VenueNameElement vne =
                                    new VenueNameElement(Constants.ANQPElementType.ANQPVenueName,
                                            vinfo);
                            venueGroup = vne.getGroup();
                            venueType = vne.getType();
                            data.getShort();
                        }
                        catch ( ProtocolException pe ) {
                            /*Cannot happen*/
                        }
                    }
                    if (elementLength == 7 || elementLength == 9) {
                        hessid = getInteger(data, ByteOrder.BIG_ENDIAN, 6);
                    }
                    break;
                case EID_RoamingConsortium:
                    anqpOICount = data.get() & Constants.BYTE_MASK;

                    int oi12Length = data.get() & Constants.BYTE_MASK;
                    int oi1Length = oi12Length & Constants.NIBBLE_MASK;
                    int oi2Length = (oi12Length >>> 4) & Constants.NIBBLE_MASK;
                    int oi3Length = elementLength - 2 - oi1Length - oi2Length;
                    int oiCount = 0;
                    if (oi1Length > 0) {
                        oiCount++;
                        if (oi2Length > 0) {
                            oiCount++;
                            if (oi3Length > 0) {
                                oiCount++;
                            }
                        }
                    }
                    roamingConsortiums = new long[oiCount];
                    if (oi1Length > 0 ) {
                        roamingConsortiums[0] = getInteger(data, ByteOrder.BIG_ENDIAN, oi1Length);
                    }
                    if (oi2Length > 0 ) {
                        roamingConsortiums[1] = getInteger(data, ByteOrder.BIG_ENDIAN, oi2Length);
                    }
                    if (oi3Length > 0 ) {
                        roamingConsortiums[2] = getInteger(data, ByteOrder.BIG_ENDIAN, oi3Length);
                    }
                    break;
                case EID_VSA:
                    if (elementLength < 5) {
                        data.position(data.position() + elementLength);
                    }
                    else if (data.getInt() != Constants.HS20_FRAME_PREFIX) {
                        data.position(data.position() + elementLength - Constants.BYTES_IN_INT);
                    }
                    else {
                        int hsConf = data.get() & Constants.BYTE_MASK;
                        switch ((hsConf>>4) & Constants.NIBBLE_MASK) {
                            case 0:
                                hsRelease = HSRelease.R1;
                                break;
                            case 1:
                                hsRelease = HSRelease.R2;
                                break;
                            default:
                                hsRelease = HSRelease.Unknown;
                                break;
                        }
                        if ((hsConf & ANQP_DOMID_BIT) != 0) {
                            anqpDomainID = data.getShort() & Constants.SHORT_MASK;
                        }
                    }
                    break;
                case EID_ExtendedCaps:
                    extendedCapabilities =
                            Constants.getInteger(data, ByteOrder.LITTLE_ENDIAN, elementLength);
                    break;
                default:
                    data.position(data.position()+elementLength);
                    break;
            }
        }

        if (ssidOctets != null) {
            Charset encoding;
            if (extendedCapabilities != null && (extendedCapabilities & SSID_UTF8_BIT) != 0) {
                encoding = StandardCharsets.UTF_8;
            }
            else {
                encoding = StandardCharsets.ISO_8859_1;
            }
            ssid = new String(ssidOctets, encoding);
        }

        mSSID = ssid;
        mHESSID = hessid;
        mStationCount = stationCount;
        mChannelUtilization = channelUtilization;
        mCapacity = capacity;
        mAnt = ant;
        mInternet = internet;
        mVenueGroup = venueGroup;
        mVenueType = venueType;
        mHSRelease = hsRelease;
        mAnqpDomainID = anqpDomainID;
        mAnqpOICount = anqpOICount;
        mRoamingConsortiums = roamingConsortiums;
        mExtendedCapabilities = extendedCapabilities;
        mANQPElements = SupplicantBridge.parseANQPLines(anqpLines);
    }

    private NetworkDetail(NetworkDetail base, Map<Constants.ANQPElementType, ANQPElement> anqpElements) {
        mSSID = base.mSSID;
        mBSSID = base.mBSSID;
        mHESSID = base.mHESSID;
        mStationCount = base.mStationCount;
        mChannelUtilization = base.mChannelUtilization;
        mCapacity = base.mCapacity;
        mAnt = base.mAnt;
        mInternet = base.mInternet;
        mVenueGroup = base.mVenueGroup;
        mVenueType = base.mVenueType;
        mHSRelease = base.mHSRelease;
        mAnqpDomainID = base.mAnqpDomainID;
        mAnqpOICount = base.mAnqpOICount;
        mRoamingConsortiums = base.mRoamingConsortiums;
        mExtendedCapabilities = base.mExtendedCapabilities;
        mANQPElements = anqpElements;
    }

    public NetworkDetail complete(Map<Constants.ANQPElementType, ANQPElement> anqpElements) {
        return new NetworkDetail(this, anqpElements);
    }

    private static long parseMac(String s) {

        long mac = 0;
        int count = 0;
        for (int n = 0; n < s.length(); n++) {
            int nibble = Utils.fromHex(s.charAt(n), true);
            if (nibble >= 0) {
                mac = (mac << 4) | nibble;
                count++;
            }
        }
        if (count < 12 || (count&1) == 1) {
            throw new IllegalArgumentException("Bad MAC address: '" + s + "'");
        }
        return mac;
    }

    public boolean has80211uInfo() {
        return mAnt != null || mRoamingConsortiums != null || mHSRelease != null;
    }

    public boolean hasInterworking() {
        return mAnt != null;
    }

    public String getSSID() {
        return mSSID;
    }

    public long getHESSID() {
        return mHESSID;
    }

    public long getBSSID() {
        return mBSSID;
    }

    public int getStationCount() {
        return mStationCount;
    }

    public int getChannelUtilization() {
        return mChannelUtilization;
    }

    public int getCapacity() {
        return mCapacity;
    }

    public boolean isInterworking() {
        return mAnt != null;
    }

    public Ant getAnt() {
        return mAnt;
    }

    public boolean isInternet() {
        return mInternet;
    }

    public VenueNameElement.VenueGroup getVenueGroup() {
        return mVenueGroup;
    }

    public VenueNameElement.VenueType getVenueType() {
        return mVenueType;
    }

    public HSRelease getHSRelease() {
        return mHSRelease;
    }

    public int getAnqpDomainID() {
        return mAnqpDomainID;
    }

    public int getAnqpOICount() {
        return mAnqpOICount;
    }

    public long[] getRoamingConsortiums() {
        return mRoamingConsortiums;
    }

    public Long getExtendedCapabilities() {
        return mExtendedCapabilities;
    }

    public Map<Constants.ANQPElementType, ANQPElement> getANQPElements() {
        return mANQPElements;
    }

    public boolean isSSID_UTF8() {
        return mExtendedCapabilities != null && (mExtendedCapabilities & SSID_UTF8_BIT) != 0;
    }

    @Override
    public boolean equals(Object thatObject) {
        if (this == thatObject) {
            return true;
        }
        if (thatObject == null || getClass() != thatObject.getClass()) {
            return false;
        }

        NetworkDetail that = (NetworkDetail)thatObject;

        return getSSID().equals(that.getSSID()) && getBSSID() == that.getBSSID();
    }

    @Override
    public int hashCode() {
        return ((mSSID.hashCode() * 31) + (int)(mBSSID >>> 32)) * 31 + (int)mBSSID;
    }

    @Override
    public String toString() {
        return String.format("NetworkInfo{mSSID='%s', mHESSID=%x, mBSSID=%x, mStationCount=%d, " +
                "mChannelUtilization=%d, mCapacity=%d, mAnt=%s, mInternet=%s, " +
                "mVenueGroup=%s, mVenueType=%s, mHSRelease=%s, mAnqpDomainID=%d, " +
                "mAnqpOICount=%d, mRoamingConsortiums=%s}",
                mSSID, mHESSID, mBSSID, mStationCount,
                mChannelUtilization, mCapacity, mAnt, mInternet,
                mVenueGroup, mVenueType, mHSRelease, mAnqpDomainID,
                mAnqpOICount, Utils.roamingConsortiumsToString(mRoamingConsortiums));
    }

    public String toKeyString() {
        return String.format("'%s':%s", mSSID, getBSSIDString());
    }

    public String getBSSIDString() {
        return toMACString(mBSSID);
    }

    private static String toMACString(long mac) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (int n = BYTES_IN_EUI48 - 1; n >= 0; n--) {
            if (first) {
                first = false;
            } else {
                sb.append(':');
            }
            sb.append(String.format("%02x", (mac >>> (n * Byte.SIZE)) & BYTE_MASK));
        }
        return sb.toString();
    }

    private static final String IE = "ie=" +
            "000477696e67" +                // SSID wing
            "0b052a00cf611e" +              // BSS Load 42:207:7777
            "6b091e0a01610408621205" +      // internet:Experimental:Vehicular:Auto:hessid
            "6f0a0e530111112222222229" +    // 14:111111:2222222229
            "dd07506f9a10143a01";           // r2:314

    private static final String IE2 = "ie=000f4578616d706c65204e6574776f726b010882848b960c1218240301012a010432043048606c30140100000fac040100000fac040100000fac0100007f04000000806b091e07010203040506076c027f006f1001531122331020304050010203040506dd05506f9a1000";

    public static void main(String[] args) {
        ScanResult scanResult = new ScanResult();
        scanResult.SSID = "wing";
        scanResult.BSSID = "610408";
        NetworkDetail nwkDetail = new NetworkDetail(scanResult.BSSID, IE2, null);
        System.out.println(nwkDetail);
    }
}
