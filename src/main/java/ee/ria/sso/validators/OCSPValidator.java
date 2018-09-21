package ee.ria.sso.validators;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.Security;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateExpiredException;
import java.security.cert.CertificateNotYetValidException;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.*;

import ee.ria.sso.utils.X509Utils;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NonNull;
import org.apache.commons.lang3.Conversion;
import org.bouncycastle.asn1.DEROctetString;
import org.bouncycastle.asn1.ocsp.OCSPObjectIdentifiers;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.Extensions;
import org.bouncycastle.cert.jcajce.JcaX509CertificateHolder;
import org.bouncycastle.cert.ocsp.BasicOCSPResp;
import org.bouncycastle.cert.ocsp.CertificateID;
import org.bouncycastle.cert.ocsp.OCSPException;
import org.bouncycastle.cert.ocsp.OCSPReq;
import org.bouncycastle.cert.ocsp.OCSPReqBuilder;
import org.bouncycastle.cert.ocsp.OCSPResp;
import org.bouncycastle.cert.ocsp.RevokedStatus;
import org.bouncycastle.cert.ocsp.SingleResp;
import org.bouncycastle.cert.ocsp.UnknownStatus;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.ContentVerifierProvider;
import org.bouncycastle.operator.OperatorCreationException;
import org.bouncycastle.operator.jcajce.JcaContentVerifierProviderBuilder;
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;


/**
 * Created by serkp on 7.10.2017.
 */

@Component
public class OCSPValidator {

    static {
        Security.addProvider(new org.bouncycastle.jce.provider.BouncyCastleProvider());
    }

    private final Logger log = LoggerFactory.getLogger(OCSPValidator.class);

    public void validate(X509Certificate userCert, X509Certificate issuerCert, OCSPConfiguration ocsp) {
        Assert.notNull(userCert, "User certificate cannot be null!");
        Assert.notNull(issuerCert, "Issuer certificate cannot be null!");
        Assert.notNull(ocsp, "OCSP configuration cannot be null!");

        this.log.debug("OCSP certificate validation called for userCert: {}, issuerCert: {}, certID: {}",
            userCert.getSubjectDN().getName(), issuerCert.getSubjectDN().getName(), userCert.getSerialNumber());

        try {
            CertificateID certificateID = this.generateCertificateIdForRequest(userCert, issuerCert);
            DEROctetString nonce = this.generateDerOctetStringForNonce(UUID.randomUUID());

            OCSPResp response = this.sendOCSPReq(buildOCSPReq(certificateID, nonce), ocsp.getServiceUrl());
            BasicOCSPResp basicOCSPResponse = (BasicOCSPResp) response.getResponseObject();

            validateResponseNonce(basicOCSPResponse, nonce);
            validateResponseProducedAt(basicOCSPResponse, ocsp.getAcceptedClockSkew(), ocsp.getResponseLifetime());
            validateResponseSignature(basicOCSPResponse, ocsp.getTrustedCertificates());

            SingleResp singleResponse = getSingleResp(basicOCSPResponse, certificateID);
            org.bouncycastle.cert.ocsp.CertificateStatus status = singleResponse.getCertStatus();

            if (status == org.bouncycastle.cert.ocsp.CertificateStatus.GOOD) {
                return;
            } else if (status instanceof RevokedStatus) {
                throw OCSPValidationException.of(CertificateStatus.REVOKED);
            } else if (status instanceof UnknownStatus) {
                throw OCSPValidationException.of(CertificateStatus.UNKNOWN);
            } else {
                throw new IllegalStateException(String.format("Unknown OCSP certificate status <%s> received", status));
            }
        } catch (OCSPValidationException e) {
            throw e;
        } catch (Exception e) {
            throw OCSPValidationException.of(e);
        }
    }

    /*
     * RESTRICTED METHODS
     */

    private OCSPReq buildOCSPReq(CertificateID certificateID, DEROctetString nonce) throws OCSPException {
        OCSPReqBuilder builder = new OCSPReqBuilder();
        builder.addRequest(certificateID);

        Extension extension = new Extension(OCSPObjectIdentifiers.id_pkix_ocsp_nonce, true, nonce);
        builder.setRequestExtensions(new Extensions(new Extension[] { extension }));

        return builder.build();
    }

    private OCSPResp sendOCSPReq(OCSPReq request, String url) throws IOException {
        byte[] bytes = request.getEncoded();

        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setRequestProperty("Content-Type", "application/ocsp-request");
        connection.setRequestProperty("Accept", "application/ocsp-response");
        connection.setDoOutput(true);

        this.log.debug("Sending OCSP request to <{}>", url);

        try (DataOutputStream outputStream = new DataOutputStream(new BufferedOutputStream(connection.getOutputStream()))) {
            outputStream.write(bytes);
            outputStream.flush();
        }

        if (connection.getResponseCode() != 200) {
            this.log.error("OCSP request has been failed (HTTP {}) - {}",
                    connection.getResponseCode(), connection.getResponseMessage());
            throw new IllegalStateException(String.format("OCSP request failed with status code %d",
                    connection.getResponseCode()));
        }

        try (InputStream in = (InputStream) connection.getContent()) {
            return new OCSPResp(in);
        }
    }

    private SingleResp getSingleResp(BasicOCSPResp basicOCSPResponse, CertificateID certificateID) {
        Optional<SingleResp> singleResponse = Arrays.stream(basicOCSPResponse.getResponses())
                .filter(singleResp -> singleResp.getCertID().equals(certificateID))
                .findFirst();

        if (!singleResponse.isPresent())
            throw new IllegalStateException("No OCSP response is present");

        return singleResponse.get();
    }

    private CertificateID generateCertificateIdForRequest(X509Certificate userCert, X509Certificate issuerCert)
            throws OperatorCreationException, CertificateEncodingException, OCSPException {
        BigInteger userCertSerialNumber = userCert.getSerialNumber();
        return new CertificateID(
                new JcaDigestCalculatorProviderBuilder().build().get(CertificateID.HASH_SHA1),
                new JcaX509CertificateHolder(issuerCert),
                userCertSerialNumber
        );
    }

    private DEROctetString generateDerOctetStringForNonce(UUID uuid) throws IOException {
        byte[] uuidBytes = Conversion.uuidToByteArray(uuid, new byte[16], 0, 16);
        return new DEROctetString(new DEROctetString(uuidBytes));
    }

    private void validateResponseNonce(BasicOCSPResp response, DEROctetString nonce) {
        Extension extension = response.getExtension(OCSPObjectIdentifiers.id_pkix_ocsp_nonce);
        if (extension == null)
            throw new IllegalStateException("No nonce found in OCSP response");

        DEROctetString receivedNonce = (DEROctetString) extension.getExtnValue();
        if (!nonce.equals(receivedNonce))
            throw new IllegalStateException("Invalid OCSP response nonce");
    }

    private void validateResponseProducedAt(BasicOCSPResp response, long acceptedClockSkew, long responseLifetime) {
        final Instant producedAt = response.getProducedAt().toInstant();
        final Instant now = Instant.now();

        if (producedAt.isBefore(now.minusSeconds(acceptedClockSkew + responseLifetime)))
            throw new IllegalStateException("OCSP response was older than accepted");
        if (producedAt.isAfter(now.plusSeconds(acceptedClockSkew)))
            throw new IllegalStateException("OCSP response cannot be produced in the future");
    }

    private void validateResponseSignature(BasicOCSPResp response, Map<String, X509Certificate> trustedCertificates)
            throws OCSPException, OperatorCreationException, CertificateNotYetValidException, CertificateExpiredException {
        X509Certificate certificate = trustedCertificates.get(getResponderCN(response));
        if (certificate == null) {
            throw new IllegalStateException("OCSP cert not found from setup");
        }
        certificate.checkValidity();

        ContentVerifierProvider verifierProvider = new JcaContentVerifierProviderBuilder()
                .setProvider(BouncyCastleProvider.PROVIDER_NAME)
                .build(certificate.getPublicKey());

        if (!response.isSignatureValid(verifierProvider))
            throw new IllegalStateException("OCSP response signature is not valid");
    }

    private String getResponderCN(BasicOCSPResp response) {
        try {
            return X509Utils.getFirstCNFromX500Name(
                    response.getResponderId().toASN1Primitive().getName()
            );
        } catch (Exception e) {
            throw new IllegalStateException("Unable to find responder CN from OCSP response", e);
        }
    }

    @Data
    @AllArgsConstructor
    public static class OCSPConfiguration {

        @NonNull
        private String serviceUrl;
        @NonNull
        private Map<String, X509Certificate> trustedCertificates;

        private long acceptedClockSkew;
        private long responseLifetime;

    }

}
