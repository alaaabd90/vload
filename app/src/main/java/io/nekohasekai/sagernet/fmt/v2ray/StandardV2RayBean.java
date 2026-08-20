package io.nekohasekai.sagernet.fmt.v2ray;

import com.esotericsoftware.kryo.io.ByteBufferInput;
import com.esotericsoftware.kryo.io.ByteBufferOutput;

import io.nekohasekai.sagernet.fmt.AbstractBean;
import io.nekohasekai.sagernet.fmt.trojan.TrojanBean;
import moe.matsuri.nb4a.utils.JavaUtil;

public abstract class StandardV2RayBean extends AbstractBean {

    public String uuid;
    public String encryption; // or VLESS flow

    //////// End of VMess & VLESS ////////

    // "V2Ray Transport" tcp/http/ws/quic/grpc/httpupgrade
    public String type;

    public String host;

    public String path;

    // --------------------------------------- tls?

    public String security;

    public String sni;

    public String alpn;

    public String utlsFingerprint;

    public Boolean allowInsecure;

    public Boolean sniFragment;

    // --------------------------------------- reality


    public String realityPubKey;

    public String realityShortId;


    // --------------------------------------- //

    public Integer wsMaxEarlyData;
    public String earlyDataHeaderName;

    public String certificates;

    // --------------------------------------- ech

    public Boolean enableECH;

    public String echConfig;

    // --------------------------------------- Mux

    public Boolean enableMux;
    public Boolean muxPadding;
    public Integer muxType;
    public Integer muxConcurrency;

    // --------------------------------------- //

    public Boolean tcpFastOpen;

    // --------------------------------------- //

    public Integer packetEncoding; // 1:packet 2:xudp

    @Override
    public void initializeDefaultValues() {
        super.initializeDefaultValues();

        if (JavaUtil.isNullOrBlank(uuid)) uuid = "";

        if (JavaUtil.isNullOrBlank(type)) type = "tcp";
        else if ("h2".equals(type)) type = "http";

        type = type.toLowerCase();

        if (JavaUtil.isNullOrBlank(host)) host = "";
        if (JavaUtil.isNullOrBlank(path)) path = "";

        if (JavaUtil.isNullOrBlank(security)) {
            if (this instanceof TrojanBean) {
                security = "tls";
            } else {
                security = "none";
            }
        }
        if (JavaUtil.isNullOrBlank(sni)) sni = "";
        if (JavaUtil.isNullOrBlank(alpn)) alpn = "";

        if (JavaUtil.isNullOrBlank(certificates)) certificates = "";
        if (JavaUtil.isNullOrBlank(earlyDataHeaderName)) earlyDataHeaderName = "";
        if (JavaUtil.isNullOrBlank(utlsFingerprint)) utlsFingerprint = "";

        if (wsMaxEarlyData == null) wsMaxEarlyData = 0;
        if (allowInsecure == null) allowInsecure = false;
        if (sniFragment == null) sniFragment = false;
        // 0 maps to packet_encoding="" in V2RayFmt.kt, which enables
        // neither xudp nor packetaddr in sing-box's VLESS/VMess outbound
        // (protocol/vless/outbound.go) - that default path can't encode a
        // domain destination ("unsupported address"), and fakeip's reverse
        // mapping means the outbound almost always receives a domain, not
        // a resolved IP, for UDP/QUIC traffic. sing-box's own default when
        // packet_encoding is omitted entirely is xudp=true; match that
        // here instead of silently downgrading every profile to the
        // broken mode. Confirmed live: this was the actual cause of
        // intermittent ERR_CONNECTION_REFUSED on dual-stack/QUIC domains,
        // not IPv4/IPv6 (ruled out separately).
        if (packetEncoding == null) packetEncoding = 2;

        if (realityPubKey == null) realityPubKey = "";
        if (realityShortId == null) realityShortId = "";

        if (enableECH == null) enableECH = false;
        if (JavaUtil.isNullOrBlank(echConfig)) echConfig = "";

        if (enableMux == null) enableMux = false;
        if (muxPadding == null) muxPadding = false;
        if (muxType == null) muxType = 0;
        if (muxConcurrency == null) muxConcurrency = 1;

        if (tcpFastOpen == null) tcpFastOpen = false;
    }

    @Override
    public void serialize(ByteBufferOutput output) {
        output.writeInt(6);
        super.serialize(output);
        output.writeString(uuid);
        output.writeString(encryption);
        if (this instanceof VMessBean) {
            output.writeInt(((VMessBean) this).alterId);
        }

        output.writeString(type);
        switch (type) {
            case "tcp":
            case "quic": {
                break;
            }
            case "ws": {
                output.writeString(host);
                output.writeString(path);
                output.writeInt(wsMaxEarlyData);
                output.writeString(earlyDataHeaderName);
                break;
            }
            case "http":
            case "httpupgrade": {
                output.writeString(host);
                output.writeString(path);
                break;
            }
            case "grpc": {
                output.writeString(path);
                break;
            }
        }

        output.writeString(security);
        if ("tls".equals(security)) {
            output.writeString(sni);
            output.writeString(alpn);
            output.writeString(certificates);
            output.writeBoolean(allowInsecure);
            output.writeString(utlsFingerprint);
            output.writeString(realityPubKey);
            output.writeString(realityShortId);
        }

        output.writeBoolean(enableECH);
        output.writeString(echConfig);

        output.writeInt(packetEncoding);

        output.writeBoolean(enableMux);
        output.writeBoolean(muxPadding);
        output.writeInt(muxType);
        output.writeInt(muxConcurrency);

        output.writeBoolean(tcpFastOpen);

        output.writeBoolean(sniFragment);
    }

    @Override
    public void deserialize(ByteBufferInput input) {
        int version = input.readInt();
        super.deserialize(input);
        uuid = input.readString();
        encryption = input.readString();
        if (this instanceof VMessBean) {
            ((VMessBean) this).alterId = input.readInt();
        }

        type = input.readString();
        switch (type) {
            case "tcp":
            case "quic": {
                break;
            }
            case "ws": {
                host = input.readString();
                path = input.readString();
                wsMaxEarlyData = input.readInt();
                earlyDataHeaderName = input.readString();
                break;
            }
            case "http":
            case "httpupgrade": {
                host = input.readString();
                path = input.readString();
                break;
            }
            case "grpc": {
                path = input.readString();
                if (version < 4) {
                    // 解决老版本数据的读取问题
                    input.readString();
                    input.readString();
                }
                break;
            }
        }

        security = input.readString();
        if ("tls".equals(security)) {
            sni = input.readString();
            alpn = input.readString();
            certificates = input.readString();
            allowInsecure = input.readBoolean();
            utlsFingerprint = input.readString();
            realityPubKey = input.readString();
            realityShortId = input.readString();
        }

        if (version >= 1) {
            enableECH = input.readBoolean();
            if (version >= 3) {
                echConfig = input.readString();
            } else {
                if (enableECH) {
                    input.readBoolean();
                    input.readBoolean();
                    echConfig = input.readString();
                }
            }
        } else if (version == 0) {
            // 从老版本升级上来但是 version == 0, 可能有 enableECH 也可能没有，需要做判断
            int position = input.getByteBuffer().position(); // 当前位置

            boolean tmpEnableECH = input.readBoolean();
            int tmpPacketEncoding = input.readInt();

            input.setPosition(position); // 读后归位

            if (tmpPacketEncoding != 1 && tmpPacketEncoding != 2) {
                enableECH = tmpEnableECH;
                if (enableECH) {
                    input.readBoolean();
                    input.readBoolean();
                    echConfig = input.readString();
                }
            } // 否则后一位就是 packetEncoding
        }

        packetEncoding = input.readInt();

        if (version >= 2) {
            enableMux = input.readBoolean();
            muxPadding = input.readBoolean();
            muxType = input.readInt();
            muxConcurrency = input.readInt();
        }

        if (version >= 5) {
            tcpFastOpen = input.readBoolean();
        }

        if (version >= 6) {
            sniFragment = input.readBoolean();
        }
    }

    public boolean isVLESS() {
        if (this instanceof VMessBean) {
            Integer aid = ((VMessBean) this).alterId;
            return aid != null && aid == -1;
        }
        return false;
    }

}
