package io.nekohasekai.sagernet.fmt.tuic;

import androidx.annotation.NonNull;

import com.esotericsoftware.kryo.io.ByteBufferInput;
import com.esotericsoftware.kryo.io.ByteBufferOutput;

import org.jetbrains.annotations.NotNull;

import io.nekohasekai.sagernet.fmt.AbstractBean;
import io.nekohasekai.sagernet.fmt.KryoConverters;

public class TuicBean extends AbstractBean {

    public String token;
    public String caText;
    public String udpRelayMode;
    public String congestionController;
    public String alpn;
    public Boolean disableSNI;
    public Boolean reduceRTT;
    public Integer mtu;
    public String sni;

    // TUIC zep

    public Boolean fastConnect;
    public Boolean allowInsecure;

    // TUIC v5

    public String customJSON;
    public Integer protocolVersion;
    public String uuid;

    // vload: sing-box 1.14.0 unified QUIC options (also apply to Hysteria2)
    public Integer quicIdleTimeout;
    public Integer quicKeepAlivePeriod;
    public Integer quicStreamReceiveWindow;
    public Integer quicConnectionReceiveWindow;
    public Integer quicMaxConcurrentStreams;
    public Integer quicInitialPacketSize;
    public Boolean quicDisablePathMtuDiscovery;

    @Override
    public void initializeDefaultValues() {
        super.initializeDefaultValues();
        if (token == null) token = "";
        if (caText == null) caText = "";
        if (udpRelayMode == null) udpRelayMode = "native";
        if (congestionController == null) congestionController = "cubic";
        if (alpn == null) alpn = "";
        if (disableSNI == null) disableSNI = false;
        if (reduceRTT == null) reduceRTT = false;
        if (mtu == null) mtu = 1400;
        if (sni == null) sni = "";
        if (fastConnect == null) fastConnect = false;
        if (allowInsecure == null) allowInsecure = false;
        if (customJSON == null) customJSON = "";
        if (protocolVersion == null) protocolVersion = 5;
        if (uuid == null) uuid = "";
        if (quicIdleTimeout == null) quicIdleTimeout = 0;
        if (quicKeepAlivePeriod == null) quicKeepAlivePeriod = 0;
        if (quicStreamReceiveWindow == null) quicStreamReceiveWindow = 0;
        if (quicConnectionReceiveWindow == null) quicConnectionReceiveWindow = 0;
        if (quicMaxConcurrentStreams == null) quicMaxConcurrentStreams = 0;
        if (quicInitialPacketSize == null) quicInitialPacketSize = 0;
        if (quicDisablePathMtuDiscovery == null) quicDisablePathMtuDiscovery = false;
    }

    @Override
    public void serialize(ByteBufferOutput output) {
        output.writeInt(3);
        super.serialize(output);
        output.writeString(token);
        output.writeString(caText);
        output.writeString(udpRelayMode);
        output.writeString(congestionController);
        output.writeString(alpn);
        output.writeBoolean(disableSNI);
        output.writeBoolean(reduceRTT);
        output.writeInt(mtu);
        output.writeString(sni);
        output.writeBoolean(fastConnect);
        output.writeBoolean(allowInsecure);
        output.writeString(customJSON);
        output.writeInt(protocolVersion);
        output.writeString(uuid);
        output.writeInt(quicIdleTimeout);
        output.writeInt(quicKeepAlivePeriod);
        output.writeInt(quicStreamReceiveWindow);
        output.writeInt(quicConnectionReceiveWindow);
        output.writeInt(quicMaxConcurrentStreams);
        output.writeInt(quicInitialPacketSize);
        output.writeBoolean(quicDisablePathMtuDiscovery);
    }

    @Override
    public void deserialize(ByteBufferInput input) {
        int version = input.readInt();
        super.deserialize(input);
        token = input.readString();
        caText = input.readString();
        udpRelayMode = input.readString();
        congestionController = input.readString();
        alpn = input.readString();
        disableSNI = input.readBoolean();
        reduceRTT = input.readBoolean();
        mtu = input.readInt();
        sni = input.readString();
        if (version >= 1) {
            fastConnect = input.readBoolean();
            allowInsecure = input.readBoolean();
        }
        if (version >= 2) {
            customJSON = input.readString();
            protocolVersion = input.readInt();
            uuid = input.readString();
        } else {
            protocolVersion = 4;
        }
        if (version >= 3) {
            quicIdleTimeout = input.readInt();
            quicKeepAlivePeriod = input.readInt();
            quicStreamReceiveWindow = input.readInt();
            quicConnectionReceiveWindow = input.readInt();
            quicMaxConcurrentStreams = input.readInt();
            quicInitialPacketSize = input.readInt();
            quicDisablePathMtuDiscovery = input.readBoolean();
        }
    }

    @Override
    public boolean canTCPing() {
        return false;
    }

    @NotNull
    @Override
    public TuicBean clone() {
        return KryoConverters.deserialize(new TuicBean(), KryoConverters.serialize(this));
    }

    public static final Creator<TuicBean> CREATOR = new CREATOR<TuicBean>() {
        @NonNull
        @Override
        public TuicBean newInstance() {
            return new TuicBean();
        }

        @Override
        public TuicBean[] newArray(int size) {
            return new TuicBean[size];
        }
    };
}
