package gov.anl.coar.meg.pgp;

import android.app.Application;
import android.util.Log;

import org.spongycastle.jce.provider.BouncyCastleProvider;
import org.spongycastle.openpgp.PGPCompressedData;
import org.spongycastle.openpgp.PGPCompressedDataGenerator;
import org.spongycastle.openpgp.PGPEncryptedData;
import org.spongycastle.openpgp.PGPEncryptedDataGenerator;
import org.spongycastle.openpgp.PGPEncryptedDataList;
import org.spongycastle.openpgp.PGPException;
import org.spongycastle.openpgp.PGPLiteralData;
import org.spongycastle.openpgp.PGPLiteralDataGenerator;
import org.spongycastle.openpgp.PGPOnePassSignatureList;
import org.spongycastle.openpgp.PGPPublicKey;
import org.spongycastle.openpgp.PGPPublicKeyEncryptedData;
import org.spongycastle.openpgp.PGPUtil;
import org.spongycastle.openpgp.jcajce.JcaPGPObjectFactory;
import org.spongycastle.openpgp.operator.jcajce.JcePGPDataEncryptorBuilder;
import org.spongycastle.openpgp.operator.jcajce.JcePublicKeyDataDecryptorFactoryBuilder;
import org.spongycastle.openpgp.operator.jcajce.JcePublicKeyKeyEncryptionMethodGenerator;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.SecureRandom;
import java.security.Security;
import java.util.Date;

import gov.anl.coar.meg.Constants;
import gov.anl.coar.meg.Util;

/**
 * Created by greg on 3/6/16.
 */
public class EncryptionLogic {
    // Motivating examples for this code can be found here:
    //
    // https://github.com/bcgit/bc-java/blob/master/pg/src/main/java/org/bouncycastle/openpgp/examples/KeyBasedFileProcessor.java
    //
    public static final String TAG = "EncryptionLogic";

    public EncryptionLogic() {
        Security.addProvider(new BouncyCastleProvider());
    }

    public BufferedInputStream decryptMessageWithPK(
            InputStream inBuffer,
            Application application
    )
        throws IllegalArgumentException
    {
        try {
            InputStream input = PGPUtil.getDecoderStream(inBuffer);
            JcaPGPObjectFactory pgpFactory = new JcaPGPObjectFactory(input);
            Object object = pgpFactory.nextObject();
            PGPEncryptedDataList encrypted;
            // the first object might be a PGP marker packet.
            if (object instanceof PGPEncryptedDataList)
                encrypted = (PGPEncryptedDataList) object;
            else
                encrypted = (PGPEncryptedDataList) pgpFactory.nextObject();
            PrivateKeyCache pkCache = (PrivateKeyCache) application;
            // XXX TODO I haven't quite figured out what to do if the PK isn't cached
            if (pkCache.needsRefresh())
                throw new IllegalArgumentException("Private key is not cached. Cannot decrypt message");
            // TODO Should only be encrypted with one public key right??
            PGPPublicKeyEncryptedData pbe = (PGPPublicKeyEncryptedData) encrypted.getEncryptedDataObjects().next();
            Log.d(TAG, "Message intended for id: " + pbe.getKeyID() + " using key id: " + pkCache.getPrivateKey().getKeyID() + " to decrypt");
            InputStream clearText = pbe.getDataStream(
                    new JcePublicKeyDataDecryptorFactoryBuilder().setProvider(Constants.SPONGY_CASTLE).build(pkCache.getPrivateKey())
            );
            JcaPGPObjectFactory plainFact = new JcaPGPObjectFactory(clearText);
            Object message = plainFact.nextObject();
            if (message instanceof PGPCompressedData) {
                Log.d(TAG, "Message is compressed data");
                PGPCompressedData cData = (PGPCompressedData) message;
                JcaPGPObjectFactory pgpFact = new JcaPGPObjectFactory(cData.getDataStream());
                message = pgpFact.nextObject();
            }
            if (message instanceof PGPLiteralData) {
                Log.d(TAG, "Message is literal data.");
                PGPLiteralData literal = (PGPLiteralData) message;
                return new BufferedInputStream(literal.getInputStream());
            } else if (message instanceof PGPOnePassSignatureList) {
                Log.e(TAG, "Message contains a signed message not literal data!");
            } else {
                Log.e(TAG, "Message contains an unknown type of data!");
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        throw new IllegalArgumentException("Unable to decrypt message.");
    }

    public InputStream decryptMessageWithSymKey(
            InputStream buffer
    ) {
        // stub method
        return buffer;
    }

    public BufferedInputStream encryptMessageWithSymKey(
            InputStream buffer
    ) {
        // just a stub
        return (BufferedInputStream) buffer;
    }

    public ByteArrayOutputStream encryptMessageWithPubKey(
            InputStream input,
            PGPPublicKey encKey
    )
            throws IOException, PGPException
    {
        Log.d(TAG, "perform encrypt action on message");
        byte[] clearData = Util.inputStreamToOutputStream(input).toByteArray();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream bOut = new ByteArrayOutputStream();
        PGPCompressedDataGenerator comData = new PGPCompressedDataGenerator(
                PGPCompressedDataGenerator.ZIP);
        PGPLiteralDataGenerator lData = new PGPLiteralDataGenerator();
        OutputStream pOut = lData.open(
                comData.open(bOut), PGPLiteralData.BINARY, Constants.TAG, clearData.length, new Date());
        pOut.write(clearData);
        comData.close();
        pOut.close();

        PGPEncryptedDataGenerator encGen = new PGPEncryptedDataGenerator(
                new JcePGPDataEncryptorBuilder(PGPEncryptedData.CAST5).setWithIntegrityPacket(
                        true
                ).setSecureRandom(new SecureRandom()).setProvider(Constants.SPONGY_CASTLE)
        );
        encGen.addMethod(new JcePublicKeyKeyEncryptionMethodGenerator(encKey).setProvider(Constants.SPONGY_CASTLE));
        OutputStream cOut = encGen.open(out, bOut.toByteArray().length);
        cOut.write(bOut.toByteArray());
        cOut.close();
        return out;
    }
}
