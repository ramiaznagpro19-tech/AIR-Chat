package chat.stoat.air;

import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * AIR Chat: minimal secp256k1 implementation (BIP-340 Schnorr signatures and ECDH), used for
 * AIR Chat accounts and for the peer-to-peer rendezvous network (Nostr relays).
 */
public final class Secp256k1 {
    private Secp256k1() {}

    public static final BigInteger P = new BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEFFFFFC2F", 16);
    public static final BigInteger N = new BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141", 16);
    private static final BigInteger GX = new BigInteger("79BE667EF9DCBBAC55A06295CE870B07029BFCDB2DCE28D959F2815B16F81798", 16);
    private static final BigInteger GY = new BigInteger("483ADA7726A3C4655DA4FBFC0E1108A8FD17B448A68554199C47D08FFB10D4B8", 16);
    private static final BigInteger SEVEN = BigInteger.valueOf(7);
    private static final SecureRandom RANDOM = new SecureRandom();

    /** Point in Jacobian coordinates; null means the point at infinity. */
    private static final class Jac {
        final BigInteger x, y, z;
        Jac(BigInteger x, BigInteger y, BigInteger z) { this.x = x; this.y = y; this.z = z; }
    }

    private static final Jac G = new Jac(GX, GY, BigInteger.ONE);

    private static Jac dbl(Jac p) {
        if (p == null || p.y.signum() == 0) return null;
        BigInteger ysq = p.y.multiply(p.y).mod(P);
        BigInteger s = BigInteger.valueOf(4).multiply(p.x).multiply(ysq).mod(P);
        BigInteger m = BigInteger.valueOf(3).multiply(p.x).multiply(p.x).mod(P);
        BigInteger nx = m.multiply(m).subtract(s.shiftLeft(1)).mod(P);
        BigInteger ny = m.multiply(s.subtract(nx)).subtract(BigInteger.valueOf(8).multiply(ysq).multiply(ysq)).mod(P);
        BigInteger nz = p.y.multiply(p.z).shiftLeft(1).mod(P);
        return new Jac(nx, ny, nz);
    }

    private static Jac add(Jac p, Jac q) {
        if (p == null) return q;
        if (q == null) return p;
        BigInteger z1z1 = p.z.multiply(p.z).mod(P);
        BigInteger z2z2 = q.z.multiply(q.z).mod(P);
        BigInteger u1 = p.x.multiply(z2z2).mod(P);
        BigInteger u2 = q.x.multiply(z1z1).mod(P);
        BigInteger s1 = p.y.multiply(q.z).multiply(z2z2).mod(P);
        BigInteger s2 = q.y.multiply(p.z).multiply(z1z1).mod(P);
        if (u1.equals(u2)) {
            if (!s1.equals(s2)) return null;
            return dbl(p);
        }
        BigInteger h = u2.subtract(u1).mod(P);
        BigInteger h2 = h.multiply(h).mod(P);
        BigInteger h3 = h.multiply(h2).mod(P);
        BigInteger r = s2.subtract(s1).mod(P);
        BigInteger u1h2 = u1.multiply(h2).mod(P);
        BigInteger nx = r.multiply(r).subtract(h3).subtract(u1h2.shiftLeft(1)).mod(P);
        BigInteger ny = r.multiply(u1h2.subtract(nx)).subtract(s1.multiply(h3)).mod(P);
        BigInteger nz = h.multiply(p.z).multiply(q.z).mod(P);
        return new Jac(nx, ny, nz);
    }

    private static Jac mul(Jac p, BigInteger k) {
        Jac r = null;
        for (int i = k.bitLength() - 1; i >= 0; i--) {
            r = dbl(r);
            if (k.testBit(i)) r = add(r, p);
        }
        return r;
    }

    /** Affine coordinates {x, y}, or null for infinity. */
    private static BigInteger[] affine(Jac p) {
        if (p == null) return null;
        BigInteger zi = p.z.modInverse(P);
        BigInteger zi2 = zi.multiply(zi).mod(P);
        return new BigInteger[]{p.x.multiply(zi2).mod(P), p.y.multiply(zi2).multiply(zi).mod(P)};
    }

    private static BigInteger[] liftX(BigInteger x) {
        if (x.compareTo(P) >= 0) return null;
        BigInteger c = x.modPow(BigInteger.valueOf(3), P).add(SEVEN).mod(P);
        BigInteger y = c.modPow(P.add(BigInteger.ONE).shiftRight(2), P);
        if (!y.modPow(BigInteger.valueOf(2), P).equals(c)) return null;
        if (y.testBit(0)) y = P.subtract(y);
        return new BigInteger[]{x, y};
    }

    public static byte[] bytes32(BigInteger v) {
        byte[] raw = v.toByteArray();
        byte[] out = new byte[32];
        if (raw.length > 32) System.arraycopy(raw, raw.length - 32, out, 0, 32);
        else System.arraycopy(raw, 0, out, 32 - raw.length, raw.length);
        return out;
    }

    private static BigInteger num(byte[] b) { return new BigInteger(1, b); }

    private static byte[] sha256(byte[]... parts) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (byte[] p : parts) md.update(p);
            return md.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] taggedHash(String tag, byte[]... parts) {
        byte[] t = sha256(tag.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        byte[][] all = new byte[parts.length + 2][];
        all[0] = t;
        all[1] = t;
        System.arraycopy(parts, 0, all, 2, parts.length);
        return sha256(all);
    }

    /** Turns any 32 bytes into a valid private key. */
    public static byte[] privateKeyFromSeed(byte[] seed) {
        BigInteger d = num(sha256(seed)).mod(N.subtract(BigInteger.ONE)).add(BigInteger.ONE);
        return bytes32(d);
    }

    public static byte[] randomPrivateKey() {
        byte[] seed = new byte[32];
        RANDOM.nextBytes(seed);
        return privateKeyFromSeed(seed);
    }

    /** x-only public key (32 bytes). */
    public static byte[] publicKey(byte[] privateKey) {
        BigInteger d = num(privateKey);
        if (d.signum() <= 0 || d.compareTo(N) >= 0) throw new IllegalArgumentException("Invalid private key");
        return bytes32(affine(mul(G, d))[0]);
    }

    public static byte[] signSchnorr(byte[] msg32, byte[] privateKey) {
        byte[] aux = new byte[32];
        RANDOM.nextBytes(aux);
        return signSchnorr(msg32, privateKey, aux);
    }

    public static byte[] signSchnorr(byte[] msg32, byte[] privateKey, byte[] aux32) {
        BigInteger d0 = num(privateKey);
        if (d0.signum() <= 0 || d0.compareTo(N) >= 0) throw new IllegalArgumentException("Invalid private key");
        BigInteger[] pAff = affine(mul(G, d0));
        BigInteger d = pAff[1].testBit(0) ? N.subtract(d0) : d0;
        byte[] px = bytes32(pAff[0]);
        byte[] t = bytes32(d.xor(num(taggedHash("BIP0340/aux", aux32))));
        BigInteger k0 = num(taggedHash("BIP0340/nonce", t, px, msg32)).mod(N);
        if (k0.signum() == 0) throw new IllegalStateException("Bad nonce");
        BigInteger[] rAff = affine(mul(G, k0));
        BigInteger k = rAff[1].testBit(0) ? N.subtract(k0) : k0;
        byte[] rx = bytes32(rAff[0]);
        BigInteger e = num(taggedHash("BIP0340/challenge", rx, px, msg32)).mod(N);
        byte[] sig = new byte[64];
        System.arraycopy(rx, 0, sig, 0, 32);
        System.arraycopy(bytes32(k.add(e.multiply(d)).mod(N)), 0, sig, 32, 32);
        return sig;
    }

    public static boolean verifySchnorr(byte[] msg32, byte[] publicKey, byte[] sig) {
        try {
            if (publicKey.length != 32 || sig.length != 64) return false;
            BigInteger[] pAff = liftX(num(publicKey));
            if (pAff == null) return false;
            BigInteger r = num(Arrays.copyOfRange(sig, 0, 32));
            BigInteger s = num(Arrays.copyOfRange(sig, 32, 64));
            if (r.compareTo(P) >= 0 || s.compareTo(N) >= 0) return false;
            BigInteger e = num(taggedHash("BIP0340/challenge", Arrays.copyOfRange(sig, 0, 32), publicKey, msg32)).mod(N);
            Jac pj = new Jac(pAff[0], pAff[1], BigInteger.ONE);
            Jac rj = add(mul(G, s), mul(pj, N.subtract(e)));
            BigInteger[] rAff = affine(rj);
            if (rAff == null) return false;
            if (rAff[1].testBit(0)) return false;
            return rAff[0].equals(r);
        } catch (RuntimeException ex) {
            return false;
        }
    }

    /** ECDH: x coordinate of privateKey * lift_x(publicKey). */
    public static byte[] sharedSecret(byte[] privateKey, byte[] publicKey) {
        BigInteger[] pAff = liftX(num(publicKey));
        if (pAff == null) throw new IllegalArgumentException("Invalid public key");
        BigInteger[] s = affine(mul(new Jac(pAff[0], pAff[1], BigInteger.ONE), num(privateKey)));
        if (s == null) throw new IllegalArgumentException("Invalid shared point");
        return bytes32(s[0]);
    }

    public static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(String.format("%02x", x & 0xff));
        return sb.toString();
    }

    public static byte[] unhex(String s) {
        int len = s.length();
        if (len % 2 != 0) throw new IllegalArgumentException("Odd hex length");
        byte[] out = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            out[i / 2] = (byte) Integer.parseInt(s.substring(i, i + 2), 16);
        }
        return out;
    }
}
