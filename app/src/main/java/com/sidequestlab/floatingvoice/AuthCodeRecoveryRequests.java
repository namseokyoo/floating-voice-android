package com.sidequestlab.floatingvoice;

import org.drinkless.tdlib.TdApi;

/** Constructs the two TDLib requests exposed by authentication-code recovery UI. */
final class AuthCodeRecoveryRequests {
    private AuthCodeRecoveryRequests() { }

    static TdApi.ResendAuthenticationCode resend() {
        return new TdApi.ResendAuthenticationCode(new TdApi.ResendCodeReasonUserRequest());
    }

    static TdApi.SetAuthenticationPhoneNumber changePhone(String phoneNumber) {
        TdApi.PhoneNumberAuthenticationSettings settings =
                new TdApi.PhoneNumberAuthenticationSettings();
        settings.authenticationTokens = new String[0];
        return new TdApi.SetAuthenticationPhoneNumber(phoneNumber, settings);
    }
}
