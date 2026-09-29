package io.nekohasekai.sagernet.fmt;

import androidx.room.TypeConverter;

import com.esotericsoftware.kryo.KryoException;
import com.esotericsoftware.kryo.io.ByteBufferInput;
import com.esotericsoftware.kryo.io.ByteBufferOutput;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import io.nekohasekai.sagernet.database.SubscriptionBean;
import io.nekohasekai.sagernet.fmt.http.HttpBean;
import io.nekohasekai.sagernet.fmt.hysteria.HysteriaBean;
import io.nekohasekai.sagernet.fmt.internal.ChainBean;
import io.nekohasekai.sagernet.fmt.internal.LoadBalanceBean;
import io.nekohasekai.sagernet.fmt.mieru.MieruBean;
import io.nekohasekai.sagernet.fmt.naive.NaiveBean;
import io.nekohasekai.sagernet.fmt.shadowsocks.ShadowsocksBean;
import moe.matsuri.nb4a.proxy.anytls.AnyTLSBean;
import moe.matsuri.nb4a.proxy.openconnect.OpenConnectBean;
import moe.matsuri.nb4a.proxy.openvpn.OpenVPNBean;
import moe.matsuri.nb4a.proxy.shadowtls.ShadowTLSBean;
import moe.matsuri.nb4a.proxy.snell.SnellBean;
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean;
import io.nekohasekai.sagernet.fmt.ssh.SSHBean;
import io.nekohasekai.sagernet.fmt.trojan.TrojanBean;
import io.nekohasekai.sagernet.fmt.trojan_go.TrojanGoBean;
import io.nekohasekai.sagernet.fmt.tuic.TuicBean;
import io.nekohasekai.sagernet.fmt.v2ray.VMessBean;
import io.nekohasekai.sagernet.fmt.wireguard.WireGuardBean;
import io.nekohasekai.sagernet.ktx.KryosKt;
import io.nekohasekai.sagernet.ktx.Logs;
import moe.matsuri.nb4a.proxy.config.ConfigBean;
import moe.matsuri.nb4a.proxy.neko.NekoBean;
import moe.matsuri.nb4a.utils.JavaUtil;

public class SecureDatabaseConverters {
    @TypeConverter public static byte[] serialize(Serializable bean) { return io.nekohasekai.sagernet.security.ProfileStorage.seal(KryoConverters.serialize(bean)); }
    @TypeConverter public static SOCKSBean socksDeserialize(byte[] bytes) { return KryoConverters.socksDeserialize(io.nekohasekai.sagernet.security.ProfileStorage.open(bytes)); }
    @TypeConverter public static HttpBean httpDeserialize(byte[] bytes) { return KryoConverters.httpDeserialize(io.nekohasekai.sagernet.security.ProfileStorage.open(bytes)); }
    @TypeConverter public static ShadowsocksBean shadowsocksDeserialize(byte[] bytes) { return KryoConverters.shadowsocksDeserialize(io.nekohasekai.sagernet.security.ProfileStorage.open(bytes)); }
    @TypeConverter public static ConfigBean configDeserialize(byte[] bytes) { return KryoConverters.configDeserialize(io.nekohasekai.sagernet.security.ProfileStorage.open(bytes)); }
    @TypeConverter public static VMessBean vmessDeserialize(byte[] bytes) { return KryoConverters.vmessDeserialize(io.nekohasekai.sagernet.security.ProfileStorage.open(bytes)); }
    @TypeConverter public static TrojanBean trojanDeserialize(byte[] bytes) { return KryoConverters.trojanDeserialize(io.nekohasekai.sagernet.security.ProfileStorage.open(bytes)); }
    @TypeConverter public static TrojanGoBean trojanGoDeserialize(byte[] bytes) { return KryoConverters.trojanGoDeserialize(io.nekohasekai.sagernet.security.ProfileStorage.open(bytes)); }
    @TypeConverter public static MieruBean mieruDeserialize(byte[] bytes) { return KryoConverters.mieruDeserialize(io.nekohasekai.sagernet.security.ProfileStorage.open(bytes)); }
    @TypeConverter public static NaiveBean naiveDeserialize(byte[] bytes) { return KryoConverters.naiveDeserialize(io.nekohasekai.sagernet.security.ProfileStorage.open(bytes)); }
    @TypeConverter public static HysteriaBean hysteriaDeserialize(byte[] bytes) { return KryoConverters.hysteriaDeserialize(io.nekohasekai.sagernet.security.ProfileStorage.open(bytes)); }
    @TypeConverter public static SSHBean sshDeserialize(byte[] bytes) { return KryoConverters.sshDeserialize(io.nekohasekai.sagernet.security.ProfileStorage.open(bytes)); }
    @TypeConverter public static WireGuardBean wireguardDeserialize(byte[] bytes) { return KryoConverters.wireguardDeserialize(io.nekohasekai.sagernet.security.ProfileStorage.open(bytes)); }
    @TypeConverter public static TuicBean tuicDeserialize(byte[] bytes) { return KryoConverters.tuicDeserialize(io.nekohasekai.sagernet.security.ProfileStorage.open(bytes)); }
    @TypeConverter public static ShadowTLSBean shadowTLSDeserialize(byte[] bytes) { return KryoConverters.shadowTLSDeserialize(io.nekohasekai.sagernet.security.ProfileStorage.open(bytes)); }
    @TypeConverter public static AnyTLSBean anyTLSDeserialize(byte[] bytes) { return KryoConverters.anyTLSDeserialize(io.nekohasekai.sagernet.security.ProfileStorage.open(bytes)); }
    @TypeConverter public static SnellBean snellDeserialize(byte[] bytes) { return KryoConverters.snellDeserialize(io.nekohasekai.sagernet.security.ProfileStorage.open(bytes)); }
    @TypeConverter public static OpenVPNBean openVPNDeserialize(byte[] bytes) { return KryoConverters.openVPNDeserialize(io.nekohasekai.sagernet.security.ProfileStorage.open(bytes)); }
    @TypeConverter public static OpenConnectBean openConnectDeserialize(byte[] bytes) { return KryoConverters.openConnectDeserialize(io.nekohasekai.sagernet.security.ProfileStorage.open(bytes)); }
    @TypeConverter public static ChainBean chainDeserialize(byte[] bytes) { return KryoConverters.chainDeserialize(io.nekohasekai.sagernet.security.ProfileStorage.open(bytes)); }
    @TypeConverter public static LoadBalanceBean loadBalanceDeserialize(byte[] bytes) { return KryoConverters.loadBalanceDeserialize(io.nekohasekai.sagernet.security.ProfileStorage.open(bytes)); }
    @TypeConverter public static NekoBean nekoDeserialize(byte[] bytes) { return KryoConverters.nekoDeserialize(io.nekohasekai.sagernet.security.ProfileStorage.open(bytes)); }
    @TypeConverter public static SubscriptionBean subscriptionDeserialize(byte[] bytes) { return KryoConverters.subscriptionDeserialize(io.nekohasekai.sagernet.security.ProfileStorage.open(bytes)); }
}
