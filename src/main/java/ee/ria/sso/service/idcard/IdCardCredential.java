package ee.ria.sso.service.idcard;

import ee.ria.sso.authentication.AuthenticationType;
import ee.ria.sso.authentication.credential.TaraCredential;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

@Getter
@Setter
@EqualsAndHashCode
@ToString
public class IdCardCredential extends TaraCredential {

    private String email;
    private Boolean emailVerified = false;

    public IdCardCredential(String principalCode, String firstName, String lastName) {
        super(AuthenticationType.IDCard, principalCode, firstName, lastName);
    }

    public IdCardCredential(String principalCode, String firstName, String lastName, String email) {
        super(AuthenticationType.IDCard, principalCode, firstName, lastName);
        this.email = email;
    }
}