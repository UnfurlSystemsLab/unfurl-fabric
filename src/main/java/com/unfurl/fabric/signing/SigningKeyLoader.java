package com.unfurl.fabric.signing;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * Loads PEM-encoded keys for fabric's signing flow.
 *
 * <p>Private keys are loaded as PKCS#8 (the {@code -----BEGIN PRIVATE KEY-----} PEM block).
 * Public keys are loaded as X.509 SubjectPublicKeyInfo (the {@code -----BEGIN PUBLIC KEY-----}
 * block). Both EC and RSA keys are supported; the algorithm is detected from the PEM header
 * and the key bytes.
 */
public final class SigningKeyLoader {

    private SigningKeyLoader() {
    }

    public static LoadedPrivateKey loadPrivateKey(Path pemFile) {
        try {
            String pem = Files.readString(pemFile, StandardCharsets.UTF_8);
            byte[] der = decodePemBody(pem, "PRIVATE KEY");
            PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(der);

            for (String algorithm : new String[]{"EC", "RSA"}) {
                try {
                    KeyFactory factory = KeyFactory.getInstance(algorithm);
                    PrivateKey privateKey = factory.generatePrivate(spec);
                    return new LoadedPrivateKey(privateKey, algorithm, signatureAlgorithmFor(algorithm));
                } catch (InvalidKeySpecException ignore) {
                    // try next algorithm
                }
            }
            throw new FabricSigningException("private key " + pemFile
                    + " is not a recognized PKCS#8 EC or RSA key");
        } catch (IOException ex) {
            throw new FabricSigningException("unable to read private key " + pemFile
                    + ": " + ex.getMessage(), ex);
        } catch (NoSuchAlgorithmException ex) {
            throw new FabricSigningException("KeyFactory algorithm unavailable on this JVM", ex);
        }
    }

    public static LoadedPublicKey loadPublicKey(Path pemFile) {
        try {
            String pem = Files.readString(pemFile, StandardCharsets.UTF_8);
            byte[] der = decodePemBody(pem, "PUBLIC KEY");
            X509EncodedKeySpec spec = new X509EncodedKeySpec(der);

            for (String algorithm : new String[]{"EC", "RSA"}) {
                try {
                    KeyFactory factory = KeyFactory.getInstance(algorithm);
                    PublicKey publicKey = factory.generatePublic(spec);
                    String sigAlg = signatureAlgorithmFor(algorithm);
                    return new LoadedPublicKey(publicKey, algorithm, sigAlg, fingerprintOf(publicKey));
                } catch (InvalidKeySpecException ignore) {
                    // try next algorithm
                }
            }
            throw new FabricSigningException("public key " + pemFile
                    + " is not a recognized X.509 EC or RSA key");
        } catch (IOException ex) {
            throw new FabricSigningException("unable to read public key " + pemFile
                    + ": " + ex.getMessage(), ex);
        } catch (NoSuchAlgorithmException ex) {
            throw new FabricSigningException("KeyFactory algorithm unavailable on this JVM", ex);
        }
    }

    public static String fingerprintOf(PublicKey publicKey) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(publicKey.getEncoded());
            StringBuilder sb = new StringBuilder("sha256:");
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new FabricSigningException("SHA-256 not available on this JVM", ex);
        }
    }

    private static byte[] decodePemBody(String pem, String label) {
        String header = "-----BEGIN " + label + "-----";
        String footer = "-----END " + label + "-----";
        int begin = pem.indexOf(header);
        int end = pem.indexOf(footer);
        if (begin < 0 || end < 0 || end < begin) {
            throw new FabricSigningException("PEM is missing " + header + " / " + footer + " markers");
        }
        String body = pem.substring(begin + header.length(), end)
                .replaceAll("\\s", "");
        try {
            return Base64.getDecoder().decode(body);
        } catch (IllegalArgumentException ex) {
            throw new FabricSigningException("PEM body is not valid base64: " + ex.getMessage(), ex);
        }
    }

    private static String signatureAlgorithmFor(String keyAlgorithm) {
        return switch (keyAlgorithm) {
            case "EC" -> "SHA256withECDSA";
            case "RSA" -> "SHA256withRSA";
            default -> throw new FabricSigningException("unsupported key algorithm: " + keyAlgorithm);
        };
    }

    public record LoadedPrivateKey(PrivateKey key, String keyAlgorithm, String signatureAlgorithm) {
    }

    public record LoadedPublicKey(PublicKey key, String keyAlgorithm, String signatureAlgorithm, String fingerprint) {
        public LoadedPublicKey {
            // Reject weak RSA keys (under 2048-bit modulus) early so verification reports
            // a clear reason rather than letting an unsafe key into the trust set.
            if (key instanceof RSAPublicKey rsa && rsa.getModulus().bitLength() < 2048) {
                throw new FabricSigningException(
                        "RSA public key " + fingerprint + " has bit length " + rsa.getModulus().bitLength()
                                + "; minimum supported is 2048");
            }
            if (key instanceof ECPublicKey ec && ec.getParams().getOrder().bitLength() < 256) {
                throw new FabricSigningException(
                        "EC public key " + fingerprint + " has order bit length "
                                + ec.getParams().getOrder().bitLength() + "; minimum supported is 256");
            }
        }
    }
}
