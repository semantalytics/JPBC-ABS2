package hk.chriz;

import it.unisa.dia.gas.jpbc.Element;
import it.unisa.dia.gas.plaf.jpbc.pairing.PairingFactory;

import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;

public class ABS {

    private ABSPubKeyComp pubParam;     // Public Key Parameters
    private ABSMasterKeyComp mkParam;   // Master Key Parameters

    public void setup() {

        // Initialise Pairing:
        pubParam = new ABSPubKeyComp();
        pubParam.pairing = PairingFactory.getPairing("a.properties");

        // just for convenience:
        pubParam.G1 = pubParam.pairing.getG1();
        pubParam.G2 = pubParam.pairing.getG2();
        pubParam.Gt = pubParam.pairing.getGT();
        pubParam.Zr = pubParam.pairing.getZr();

        // Set g = random generator:
        pubParam.g = pubParam.G1.newRandomElement();

        // Set master key a0, a, b, c = random:
        mkParam = new ABSMasterKeyComp();
        mkParam.a0 = pubParam.Zr.newRandomElement();
        mkParam.a = pubParam.Zr.newRandomElement();
        mkParam.b = pubParam.Zr.newRandomElement();
        mkParam.c = pubParam.Zr.newRandomElement();

        // Set h_0 to h_tmax = random in G2:
        pubParam.hi = new ArrayList<>();
        for (int i=0; i<pubParam.tmax+1; i++)   // from 0 to t_max totally t_max+1 items
            pubParam.hi.add(pubParam.G2.newRandomElement());

        // Set A0 = h0^a0:
        pubParam.A = new ArrayList<>();
        Element h0_a0 = pubParam.hi.get(0).duplicate();
        h0_a0.powZn(mkParam.a0);
        pubParam.A.add(h0_a0);

        // Set A_1 ~ A_tmax:
        for (int i=1; i<pubParam.hi.size(); i++) {
            Element hi_a = pubParam.hi.get(i).duplicate();
            hi_a.powZn(mkParam.a);
            pubParam.A.add(hi_a);
        }

        // Set B_1 ~ B_tmax:
        pubParam.B = new ArrayList<>();
        pubParam.B.add(null);   // B_0 is nothing
        for (int i=1; i<pubParam.hi.size(); i++) {
            Element hi_b = pubParam.hi.get(i).duplicate();
            hi_b.powZn(mkParam.b);
            pubParam.B.add(hi_b);
        }

        // Set C = g^c (at the end of P.20):
        pubParam.C = pubParam.g.duplicate();
        pubParam.C.powZn(mkParam.c);

        System.out.println("Size of H: "+pubParam.hi.size());
        System.out.println("Size of A: "+pubParam.A.size());
        System.out.println("Size of B: "+pubParam.B.size());

    }

    public ABSPrivKeyComp keygen(String [] attrs) throws NoSuchAlgorithmException {
        ABSPrivKeyComp comp = new ABSPrivKeyComp();

        // Set Kbase = random:
        comp.Kbase = pubParam.G1.newRandomElement();

        // Calculate K0 = Kbase^(1/a0):
        Element inv_a0 = mkParam.a0.duplicate();
        inv_a0.invert();
        comp.K0 = comp.Kbase.duplicate();
        comp.K0.powZn(inv_a0);

        // Calculate Ku (u ∈ attributes):
        comp.Ku = new ArrayList<>();
        comp.attr = new ArrayList<>();
        for (String attr: attrs) {
            // Calculate 1/(a+bu):
            Element hashed = pubParam.Zr.newElement();
            MSP.elementFromString(hashed, attr);    // get hashed attribute (u)
            Element inv_a_bu = mkParam.b.duplicate();
            inv_a_bu.mul(hashed);               // bu
            inv_a_bu.add(mkParam.a);            // a+bu
            inv_a_bu.invert();                  // 1/(a+bu)
            Element ku = comp.Kbase.duplicate();
            ku.powZn(inv_a_bu);                 // Kbase^(1/a+bu)
            comp.Ku.add(ku);
            comp.attr.add(attr);                // for later reference
            System.out.println("Attr = "+attr+" ( u = "+hashed+" ) ( Ku = "+ku+" )");
        }
        System.out.println("Secret Key Ku has "+comp.Ku.size()+" elements.");
        return comp;
    }

    public ABSSignatureComp sign (String message,
                                  ABSPrivKeyComp privKey) throws NoSuchAlgorithmException {
        ABSSignatureComp comp = new ABSSignatureComp();
        MSP msp = MSP.getInstance(message, privKey.attr, pubParam.Zr);
        final int l = msp.M.length;
        final int t = msp.M[0].length;

        // Pick random r0 ... rl:
        ArrayList<Element> r = new ArrayList<>();
        for (int i=0; i<l+1; i++)
            r.add(pubParam.Zr.newRandomElement());

        // Check if r0 ← Zp* :
        /*
        if (r.get(0).toBigInteger().gcd(RR).compareTo(BigInteger.ONE) != 0) {
            System.err.println("WARNING: r0 is NOT coprime of RR!");
            System.exit(-1);
        }
         */

        // Calculate Y and W:
        comp.Y = privKey.Kbase.duplicate();
        comp.Y.powZn(r.get(0));
        comp.W = privKey.K0.duplicate();
        comp.W.powZn(r.get(0));

        // Calculate Si for each l:
        comp.Si = new ArrayList<>();
        comp.Si.add(null);                                    // S0 does not exist
        for (int i=1; i<l+1; i++) {
            Element cgur = pubParam.g.duplicate();
            cgur.powZn(msp.mu);                               // g^µ
            cgur.mul(pubParam.C);                             // C * g^µ
            cgur.powZn(r.get(i));                             // (Cg^µ)^ri
            Element kur = privKey.Ku.get(i-1).duplicate();    // K_u(i)
            kur.powZn(r.get(0));                              // K_u(i)^r0
            comp.Si.add(kur.mul(cgur));                       // K_u(i)^r0 * (Cg^µ)^ri
            System.out.println("S"+i+" = "+comp.Si.get(i)+" ( Ku = "+privKey.Ku.get(i-1)+" )");
        }

        // Calculate Pj for each t (each item multiply from 1 to l):
        comp.Pj = new ArrayList<>();
        comp.Pj.add(null);                                          // P0 does not exist
        for (int j=1; j<t+1; j++) {
            Element end = pubParam.G2.newZeroElement();
            for (int i=1; i<l+1; i++) {
                Element base = pubParam.A.get(j).duplicate();       // A_j
                Element bj_ui = pubParam.B.get(j).duplicate();      // B_j
                bj_ui.powZn(msp.u.get(i-1));                        // B_j^(u(i))
                base.mul(bj_ui);                                    // A_j * B_j^(u(i))
                Element exp = r.get(i).duplicate();                 // r_i
                exp.mul(msp.M[i-1][j-1]);                           // M_ij * r_i
                base.powZn(exp);                                    // (A_j * B_j^(u(i)))^(M_ij * r_i)
                end.mul(base);                                      // Π (A_j * B_j^(u(i)))^(M_ij * r_i)
                System.out.printf("M[%d][%d]=%d , ", (i-1), (j-1), msp.M[i-1][j-1]);
                System.out.println("u("+i+") = "+msp.u.get(i-1));
            }
            comp.Pj.add(end);
            System.out.println("-> P"+j+" = "+end);
        }
        return comp;
    }

    public boolean verify (String message,
                           String [] attrs, ABSSignatureComp sign) throws NoSuchAlgorithmException {
        ArrayList<String> attrList = new ArrayList<>();
        Collections.addAll(attrList, attrs);
        MSP msp = MSP.getInstance(message, attrList, pubParam.Zr);
        final int l = msp.M.length;
        final int t = msp.M[0].length;

        // check if e(W,A0) != e(Y, h0):
        Element e_Y_h0 = pubParam.pairing.pairing(sign.Y, pubParam.hi.get(0));
        Element e_W_A0 = pubParam.pairing.pairing(sign.W, pubParam.A.get(0));
        if (!e_Y_h0.isEqual(e_W_A0)) {
            System.out.println("e(W,A0) != e(Y, h0)");
            return false;
        }

        // check if empty Y:
        if (sign.Y.isEqual(pubParam.G1.newZeroElement())) {
            System.out.println("Zero element of Y");
            return false;
        }

        // check j elements (∀j ∈ [t]):
        for (int j=1; j<t+1; j++) {
            Element lhs = pubParam.Gt.newZeroElement();
            for (int i=1; i<l+1; i++) {
                Element a = sign.Si.get(i).duplicate();                 // S_i
                Element b = pubParam.B.get(j).duplicate();              // B_j
                b.powZn(msp.u.get(i-1));                                // B_j^(u(i))
                b.mul(pubParam.A.get(j));                               // A_j * B_j^(u(i))
                // XXX BUG: pow() does not support negative BigInteger, use Zr.newElement() instead XXX
                b.powZn(pubParam.Zr.newElement(msp.M[i-1][j-1]));       // (A_j * B_j^(u(i)))^(Mij)
                //b.pow(BigInteger.valueOf(msp.M[i-1][j-1]));             // (A_j * B_j^(u(i)))^(Mij)
                lhs.mul(pubParam.pairing.pairing(a, b));                // Π e(S_i, (A_j * B_j^(u(i)))^(Mij))
            }

            Element cgu = pubParam.g.duplicate();                               // g
            cgu.powZn(msp.mu);                                                  // g^µ
            cgu.mul(pubParam.C);                                                // C * g^µ
            Element rhs = pubParam.pairing.pairing(cgu, sign.Pj.get(j));        // e(Cg^µ, Pj)
            if (j == 1) {
                rhs.mul(pubParam.pairing.pairing(sign.Y, pubParam.hi.get(j)));  // e(Y, hj) * e(Cg^µ, Pj)
            }

            if (!lhs.isEqual(rhs)) {
                System.out.println("Mismatch for j = "+j+" case...");
                return false;
            }
        }
        return true;
    }

}
