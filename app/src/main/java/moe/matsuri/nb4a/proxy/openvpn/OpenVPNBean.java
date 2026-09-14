package moe.matsuri.nb4a.proxy.openvpn;

import androidx.annotation.NonNull;

import com.esotericsoftware.kryo.io.ByteBufferInput;
import com.esotericsoftware.kryo.io.ByteBufferOutput;

import org.jetbrains.annotations.NotNull;

import io.nekohasekai.sagernet.fmt.AbstractBean;
import io.nekohasekai.sagernet.fmt.KryoConverters;

public class OpenVPNBean extends AbstractBean {

    // "tls" or "static_key"
    public String mode;
    public String network;
    public String username;
    public String password;

    // TLS mode
    public String caCertificate;
    public String clientCertificate;
    public String clientKey;
    public String tlsServerName;

    // static_key mode
    public String staticKey;
    public String keyDirection;

    // Optional control-channel wrapper, either mode
    public String controlWrapType;
    public String controlWrapKey;

    public String cipher;
    public String auth;
    public String compression;
    public Integer mssFix;

    @Override
    public void initializeDefaultValues() {
        super.initializeDefaultValues();
        if (mode == null) mode = "tls";
        if (network == null) network = "udp";
        if (username == null) username = "";
        if (password == null) password = "";
        if (caCertificate == null) caCertificate = "";
        if (clientCertificate == null) clientCertificate = "";
        if (clientKey == null) clientKey = "";
        if (tlsServerName == null) tlsServerName = "";
        if (staticKey == null) staticKey = "";
        if (keyDirection == null) keyDirection = "";
        if (controlWrapType == null) controlWrapType = "";
        if (controlWrapKey == null) controlWrapKey = "";
        if (cipher == null) cipher = "";
        if (auth == null) auth = "";
        if (compression == null) compression = "";
        if (mssFix == null) mssFix = 0;
    }

    @Override
    public void serialize(ByteBufferOutput output) {
        output.writeInt(0);
        super.serialize(output);
        output.writeString(mode);
        output.writeString(network);
        output.writeString(username);
        output.writeString(password);
        output.writeString(caCertificate);
        output.writeString(clientCertificate);
        output.writeString(clientKey);
        output.writeString(tlsServerName);
        output.writeString(staticKey);
        output.writeString(keyDirection);
        output.writeString(controlWrapType);
        output.writeString(controlWrapKey);
        output.writeString(cipher);
        output.writeString(auth);
        output.writeString(compression);
        output.writeInt(mssFix);
    }

    @Override
    public void deserialize(ByteBufferInput input) {
        int version = input.readInt();
        super.deserialize(input);
        mode = input.readString();
        network = input.readString();
        username = input.readString();
        password = input.readString();
        caCertificate = input.readString();
        clientCertificate = input.readString();
        clientKey = input.readString();
        tlsServerName = input.readString();
        staticKey = input.readString();
        keyDirection = input.readString();
        controlWrapType = input.readString();
        controlWrapKey = input.readString();
        cipher = input.readString();
        auth = input.readString();
        compression = input.readString();
        mssFix = input.readInt();
    }

    @Override
    public boolean canTCPing() {
        return false;
    }

    @NotNull
    @Override
    public OpenVPNBean clone() {
        return KryoConverters.deserialize(new OpenVPNBean(), KryoConverters.serialize(this));
    }

    public static final Creator<OpenVPNBean> CREATOR = new CREATOR<OpenVPNBean>() {
        @NonNull
        @Override
        public OpenVPNBean newInstance() {
            return new OpenVPNBean();
        }

        @Override
        public OpenVPNBean[] newArray(int size) {
            return new OpenVPNBean[size];
        }
    };
}
