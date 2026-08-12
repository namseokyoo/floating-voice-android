package com.sidequestlab.floatingvoice;

import org.junit.Test;

import static org.junit.Assert.*;

public class AuthCodeRecoveryPolicyTest {
    @Test
    public void codeStageAllowsPhoneCorrectionWithoutPretendingTdlibReturnedToPhoneStage() {
        AuthCodeRecoveryPolicy.State state = AuthCodeRecoveryPolicy.evaluate(
                TelegramRepository.AuthStage.CODE, true, 12L);

        assertTrue(state.phoneCorrectionAllowed());
        assertFalse(state.resendEnabled());
        assertEquals(12L, state.resendWaitSeconds());
    }

    @Test
    public void resendRequiresCodeStageNextTypeAndElapsedTimeout() {
        assertFalse(AuthCodeRecoveryPolicy.evaluate(
                TelegramRepository.AuthStage.CODE, false, 0L).resendEnabled());
        assertFalse(AuthCodeRecoveryPolicy.evaluate(
                TelegramRepository.AuthStage.CODE, true, 1L).resendEnabled());
        assertTrue(AuthCodeRecoveryPolicy.evaluate(
                TelegramRepository.AuthStage.CODE, true, 0L).resendEnabled());
        assertFalse(AuthCodeRecoveryPolicy.evaluate(
                TelegramRepository.AuthStage.PHONE, true, 0L).resendEnabled());
    }
}
