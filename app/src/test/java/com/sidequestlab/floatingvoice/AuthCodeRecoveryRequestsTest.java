package com.sidequestlab.floatingvoice;

import org.drinkless.tdlib.TdApi;
import org.junit.Test;

import static org.junit.Assert.*;

public class AuthCodeRecoveryRequestsTest {
    @Test
    public void resendIsExplicitUserRequest() {
        TdApi.ResendAuthenticationCode request = AuthCodeRecoveryRequests.resend();
        assertTrue(request.reason instanceof TdApi.ResendCodeReasonUserRequest);
    }

    @Test
    public void phoneCorrectionUsesFreshDefaultAuthenticationSettings() {
        TdApi.SetAuthenticationPhoneNumber request =
                AuthCodeRecoveryRequests.changePhone("+821012345678");

        assertEquals("+821012345678", request.phoneNumber);
        assertNotNull(request.settings);
        assertNotNull(request.settings.authenticationTokens);
        assertEquals(0, request.settings.authenticationTokens.length);
    }
}
