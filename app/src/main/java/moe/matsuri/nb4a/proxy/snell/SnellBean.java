package moe.matsuri.nb4a.proxy.snell;

import androidx.annotation.NonNull;

import com.esotericsoftware.kryo.io.ByteBufferInput;
import com.esotericsoftware.kryo.io.ByteBufferOutput;

import org.jetbrains.annotations.NotNull;

import io.nekohasekai.sagernet.fmt.AbstractBean;
import io.nekohasekai.sagernet.fmt.KryoConverters;

public class SnellBean extends AbstractBean {

    public Integer version;
    public String psk;
    public String userkey;
    public Boolean reuse;
    public String obfsMode;
    public String obfsHost;
    public String v6Mode;

    @Override
    public void initializeDefaultValues() {
        super.initializeDefaultValues();
        if (version == null) version = 4;
        if (psk == null) psk = "";
        if (userkey == null) userkey = "";
        if (reuse == null) reuse = false;
        if (obfsMode == null) obfsMode = "none";
        if (obfsHost == null) obfsHost = "";
        if (v6Mode == null) v6Mode = "default";
    }

    @Override
    public void serialize(ByteBufferOutput output) {
        output.writeInt(0);
        super.serialize(output);
        output.writeInt(version);
        output.writeString(psk);
        output.writeString(userkey);
        output.writeBoolean(reuse);
        output.writeString(obfsMode);
        output.writeString(obfsHost);
        output.writeString(v6Mode);
    }

    @Override
    public void deserialize(ByteBufferInput input) {
        int version_ = input.readInt();
        super.deserialize(input);
        version = input.readInt();
        psk = input.readString();
        userkey = input.readString();
        reuse = input.readBoolean();
        obfsMode = input.readString();
        obfsHost = input.readString();
        v6Mode = input.readString();
    }

    @Override
    public boolean canTCPing() {
        return false;
    }

    @NotNull
    @Override
    public SnellBean clone() {
        return KryoConverters.deserialize(new SnellBean(), KryoConverters.serialize(this));
    }

    public static final Creator<SnellBean> CREATOR = new CREATOR<SnellBean>() {
        @NonNull
        @Override
        public SnellBean newInstance() {
            return new SnellBean();
        }

        @Override
        public SnellBean[] newArray(int size) {
            return new SnellBean[size];
        }
    };
}
