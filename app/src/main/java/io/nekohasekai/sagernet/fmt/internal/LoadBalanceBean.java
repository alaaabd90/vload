package io.nekohasekai.sagernet.fmt.internal;

import androidx.annotation.NonNull;

import com.esotericsoftware.kryo.io.ByteBufferInput;
import com.esotericsoftware.kryo.io.ByteBufferOutput;

import org.jetbrains.annotations.NotNull;

import io.nekohasekai.sagernet.fmt.KryoConverters;
import moe.matsuri.nb4a.utils.JavaUtil;

/**
 * A vload load-balance profile: references two other existing profiles by
 * id (slot A / slot B), each bound to a physical network (Wi-Fi or a
 * specific SIM subscription) with a relative speed weight. Modeled on
 * {@link ChainBean} - a profile whose "bean" just points at other profiles
 * rather than describing a proxy protocol of its own.
 */
public class LoadBalanceBean extends InternalBean {

    public static final int NETWORK_WIFI = 0;
    public static final int NETWORK_SIM = 1;

    public int slotANetworkKind;
    public int slotASubscriptionId;
    public long slotAProxyId;
    public int slotAWeight;

    public int slotBNetworkKind;
    public int slotBSubscriptionId;
    public long slotBProxyId;
    public int slotBWeight;

    @Override
    public String displayName() {
        if (JavaUtil.isNotBlank(name)) {
            return name;
        } else {
            return "Load Balance " + Math.abs(hashCode());
        }
    }

    @Override
    public void initializeDefaultValues() {
        super.initializeDefaultValues();
        if (name == null) name = "";
        if (slotAProxyId == 0) slotAProxyId = -1;
        if (slotBProxyId == 0) slotBProxyId = -1;
        if (slotASubscriptionId == 0) slotASubscriptionId = -1;
        if (slotBSubscriptionId == 0) slotBSubscriptionId = -1;
        if (slotAWeight <= 0) slotAWeight = 50;
        if (slotBWeight <= 0) slotBWeight = 50;
    }

    @Override
    public void serialize(ByteBufferOutput output) {
        output.writeInt(0);
        output.writeInt(slotANetworkKind);
        output.writeInt(slotASubscriptionId);
        output.writeLong(slotAProxyId);
        output.writeInt(slotAWeight);
        output.writeInt(slotBNetworkKind);
        output.writeInt(slotBSubscriptionId);
        output.writeLong(slotBProxyId);
        output.writeInt(slotBWeight);
    }

    @Override
    public void deserialize(ByteBufferInput input) {
        input.readInt(); // version
        slotANetworkKind = input.readInt();
        slotASubscriptionId = input.readInt();
        slotAProxyId = input.readLong();
        slotAWeight = input.readInt();
        slotBNetworkKind = input.readInt();
        slotBSubscriptionId = input.readInt();
        slotBProxyId = input.readLong();
        slotBWeight = input.readInt();
    }

    @NotNull
    @Override
    public LoadBalanceBean clone() {
        return KryoConverters.deserialize(new LoadBalanceBean(), KryoConverters.serialize(this));
    }

    public static final Creator<LoadBalanceBean> CREATOR = new CREATOR<LoadBalanceBean>() {
        @NonNull
        @Override
        public LoadBalanceBean newInstance() {
            return new LoadBalanceBean();
        }

        @Override
        public LoadBalanceBean[] newArray(int size) {
            return new LoadBalanceBean[size];
        }
    };
}
