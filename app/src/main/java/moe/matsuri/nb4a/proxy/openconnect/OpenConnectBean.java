package moe.matsuri.nb4a.proxy.openconnect;

import androidx.annotation.NonNull;

import com.esotericsoftware.kryo.io.ByteBufferInput;
import com.esotericsoftware.kryo.io.ByteBufferOutput;

import org.jetbrains.annotations.NotNull;

import io.nekohasekai.sagernet.fmt.AbstractBean;
import io.nekohasekai.sagernet.fmt.KryoConverters;

public class OpenConnectBean extends AbstractBean {

    // anyconnect/gp/fortinet/f5/pulse/nc
    public String flavor;
    public String username;
    public String password;
    public String authGroup;
    public String cookie;
    public String userAgent;

    public Boolean insecure;
    public String tlsServerName;
    public String caCertificate;
    public String clientCertificate;
    public String clientKey;
    public String clientKeyPassword;

    public Boolean noUdp;
    public Integer mtu;

    @Override
    public void initializeDefaultValues() {
        super.initializeDefaultValues();
        if (flavor == null) flavor = "anyconnect";
        if (username == null) username = "";
        if (password == null) password = "";
        if (authGroup == null) authGroup = "";
        if (cookie == null) cookie = "";
        if (userAgent == null) userAgent = "";
        if (insecure == null) insecure = false;
        if (tlsServerName == null) tlsServerName = "";
        if (caCertificate == null) caCertificate = "";
        if (clientCertificate == null) clientCertificate = "";
        if (clientKey == null) clientKey = "";
        if (clientKeyPassword == null) clientKeyPassword = "";
        if (noUdp == null) noUdp = false;
        if (mtu == null) mtu = 0;
    }

    @Override
    public void serialize(ByteBufferOutput output) {
        output.writeInt(0);
        super.serialize(output);
        output.writeString(flavor);
        output.writeString(username);
        output.writeString(password);
        output.writeString(authGroup);
        output.writeString(cookie);
        output.writeString(userAgent);
        output.writeBoolean(insecure);
        output.writeString(tlsServerName);
        output.writeString(caCertificate);
        output.writeString(clientCertificate);
        output.writeString(clientKey);
        output.writeString(clientKeyPassword);
        output.writeBoolean(noUdp);
        output.writeInt(mtu);
    }

    @Override
    public void deserialize(ByteBufferInput input) {
        int version = input.readInt();
        super.deserialize(input);
        flavor = input.readString();
        username = input.readString();
        password = input.readString();
        authGroup = input.readString();
        cookie = input.readString();
        userAgent = input.readString();
        insecure = input.readBoolean();
        tlsServerName = input.readString();
        caCertificate = input.readString();
        clientCertificate = input.readString();
        clientKey = input.readString();
        clientKeyPassword = input.readString();
        noUdp = input.readBoolean();
        mtu = input.readInt();
    }

    @Override
    public boolean canTCPing() {
        return false;
    }

    @NotNull
    @Override
    public OpenConnectBean clone() {
        return KryoConverters.deserialize(new OpenConnectBean(), KryoConverters.serialize(this));
    }

    public static final Creator<OpenConnectBean> CREATOR = new CREATOR<OpenConnectBean>() {
        @NonNull
        @Override
        public OpenConnectBean newInstance() {
            return new OpenConnectBean();
        }

        @Override
        public OpenConnectBean[] newArray(int size) {
            return new OpenConnectBean[size];
        }
    };
}
