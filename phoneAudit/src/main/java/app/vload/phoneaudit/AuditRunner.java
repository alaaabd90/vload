package app.vload.phoneaudit;

import android.app.Instrumentation;
import android.app.Activity;
import android.os.Bundle;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.security.keystore.KeyInfo;
import android.util.Base64;
import org.json.*;
import java.io.File;
import java.nio.ByteBuffer;
import java.security.*;
import java.lang.reflect.*;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;

/** Manually installed signed audit. No AndroidX/Kotlin/desugar dependencies, no writes to saved profiles.
 * Only hashes and test booleans leave the app process. Never run a connected-test uninstall workflow on a phone.
 */
public class AuditRunner extends Instrumentation {
    private Bundle args;
    private final byte[] magic={0x56,0x4c,0x44,0x53,1};
    @Override public void onCreate(Bundle arguments) {super.onCreate(arguments);args=arguments;start();}
    private byte[] open(byte[] b) throws Exception {
        if(b==null || b.length<5 || !Arrays.equals(Arrays.copyOf(b,5),magic)) return b;
        KeyStore ks=KeyStore.getInstance("AndroidKeyStore");ks.load(null);
        Cipher c=Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE,(SecretKey)ks.getKey("vload.profile.storage.v1",null),new GCMParameterSpec(128,Arrays.copyOfRange(b,5,17)));
        c.updateAAD(magic);return c.doFinal(Arrays.copyOfRange(b,17,b.length));
    }
    private String hex(byte[] b) {StringBuilder s=new StringBuilder();for(byte v:b)s.append(String.format("%02x",v));return s.toString();}
    private Method find(String cls,String name) throws Exception {
        Class<?> c=getTargetContext().getClassLoader().loadClass(cls);
        for(Method m:c.getDeclaredMethods()) if(m.getName().equals(name)){m.setAccessible(true);return m;}
        throw new NoSuchMethodException();
    }
    private Object invoke(Method m,Object... params) throws Exception {
        Object receiver=null;
        if(!Modifier.isStatic(m.getModifiers())) for(Field f:m.getDeclaringClass().getDeclaredFields()) if(Modifier.isStatic(f.getModifiers()) && f.getType()==m.getDeclaringClass()){f.setAccessible(true);receiver=f.get(null);break;}
        return m.invoke(receiver,params);
    }
    @Override public void onStart() {
        Bundle result=new Bundle();
        try {
            Context context=getTargetContext();
            if(!context.getPackageName().equals("app.vload.android"))throw new SecurityException();
            JSONObject report=new JSONObject();JSONArray profiles=new JSONArray();
            int encrypted=0,payloads=0,cryptoChecks=0;
            Method encrypt=null,decrypt=null;
            String recipient=null;
            if(args.containsKey("encryptClass")) {
                encrypt=find(args.getString("encryptClass"),args.getString("encryptMethod"));
                decrypt=find(args.getString("decryptClass"),args.getString("decryptMethod"));
                KeyStore ks=KeyStore.getInstance("AndroidKeyStore");ks.load(null);
                recipient="VLP3:"+Base64.encodeToString(ks.getCertificate("vload.recipient.rsa.v3").getPublicKey().getEncoded(),Base64.URL_SAFE|Base64.NO_WRAP|Base64.NO_PADDING);
                report.put("recipient_public_key",recipient);
                KeyInfo info=KeyFactory.getInstance("RSA","AndroidKeyStore").getKeySpec((PrivateKey)ks.getKey("vload.recipient.rsa.v3",null),KeyInfo.class);
                report.put("recipient_key_hardware_backed",info.isInsideSecureHardware());
            }
            try(SQLiteDatabase db=SQLiteDatabase.openDatabase(context.getDatabasePath("sager_net.db").getPath(),null,SQLiteDatabase.OPEN_READONLY);
                Cursor rows=db.rawQuery("SELECT * FROM proxy_entities ORDER BY id",null)) {
                while(rows.moveToNext()) {
                    MessageDigest hash=MessageDigest.getInstance("SHA-256");
                    JSONObject beanHashes=new JSONObject();
                    for(int col=0;col<rows.getColumnCount();col++) {
                        String name=rows.getColumnName(col);
                        if(Arrays.asList("tx","rx","status","ping","error").contains(name))continue;
                        hash.update(name.getBytes("UTF-8"));hash.update((byte)0);
                        byte[] value;
                        if(rows.isNull(col))value=new byte[]{0};
                        else if(rows.getType(col)==Cursor.FIELD_TYPE_BLOB) {
                            value=rows.getBlob(col);
                            if(name.endsWith("Bean") && value.length>0) {
                                payloads++;
                                if(value.length>=5 && Arrays.equals(Arrays.copyOf(value,5),magic))encrypted++;
                                value=open(value);
                                beanHashes.put(name,hex(MessageDigest.getInstance("SHA-256").digest(value)));
                                if(encrypt!=null) {
                                    String plaintext=Base64.encodeToString(value,Base64.NO_WRAP);
                                    byte[] sealed=(byte[])invoke(encrypt,plaintext,recipient);
                                    Object clear=decrypt.getParameterCount()==1?invoke(decrypt,(Object)sealed):invoke(decrypt,sealed,"");
                                    boolean matched=false;
                                    for(Field f:clear.getClass().getDeclaredFields())if(f.getType()==String.class){f.setAccessible(true);if(plaintext.equals(f.get(clear)))matched=true;}
                                    if(!matched)throw new AssertionError("Locked round trip");
                                    sealed[sealed.length-1]^=1;
                                    Object corrupt=decrypt.getParameterCount()==1?invoke(decrypt,(Object)sealed):invoke(decrypt,sealed,"");
                                    if(corrupt.getClass()==clear.getClass())throw new AssertionError("Tampering accepted");
                                    cryptoChecks++;
                                }
                            }
                        } else value=rows.getString(col).getBytes("UTF-8");
                        hash.update(ByteBuffer.allocate(4).putInt(value.length).array());hash.update(value);
                    }
                    profiles.put(new JSONObject().put("id",rows.getLong(rows.getColumnIndexOrThrow("id")))
                        .put("type",rows.getInt(rows.getColumnIndexOrThrow("type")))
                        .put("bean_sha256",beanHashes)
                        .put("locked_import",rows.getInt(rows.getColumnIndexOrThrow("lockedImport"))!=0)
                        .put("logical_sha256",hex(hash.digest())));
                }
            }
            report.put("profiles",profiles).put("profile_count",profiles.length()).put("encrypted_payload_count",encrypted).put("payload_count",payloads).put("locked_roundtrips_and_tamper_checks",cryptoChecks);
            File config=context.createDeviceProtectedStorageContext().getDatabasePath("configuration.db");
            if(!config.exists())config=context.getDatabasePath("configuration.db");
            if(config.exists())try(SQLiteDatabase db=SQLiteDatabase.openDatabase(config.getPath(),null,SQLiteDatabase.OPEN_READONLY);
                Cursor c=db.rawQuery("SELECT value FROM KeyValuePair WHERE key='profileId'",null)){
                if(c.moveToFirst())report.put("selected_profile_id",ByteBuffer.wrap(c.getBlob(0)).getLong());
            }
            result.putString("audit",report.toString());finish(Activity.RESULT_OK,result);
        } catch(Throwable e) {
            Throwable cause=e instanceof InvocationTargetException?((InvocationTargetException)e).getTargetException():e;
            result.putString("failure_class",cause.getClass().getName());
            // Stack frame names have no config contents; exception messages deliberately omitted.
            result.putString("failure_location",cause.getStackTrace().length>0?cause.getStackTrace()[0].toString():"unknown");
            finish(Activity.RESULT_CANCELED,result);
        }
    }
}
