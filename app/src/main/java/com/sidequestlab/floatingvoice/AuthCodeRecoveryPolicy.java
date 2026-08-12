package com.sidequestlab.floatingvoice;

/** Pure UI policy for recovering from Telegram's authentication-code stage. */
final class AuthCodeRecoveryPolicy {
    record State(boolean phoneCorrectionAllowed, boolean resendEnabled,
                 long resendWaitSeconds) { }

    private AuthCodeRecoveryPolicy() { }

    static State evaluate(TelegramRepository.AuthStage stage,
                          boolean hasNextCodeType, long resendWaitSeconds) {
        boolean codeStage = stage == TelegramRepository.AuthStage.CODE;
        long wait = Math.max(0L, resendWaitSeconds);
        return new State(codeStage, codeStage && hasNextCodeType && wait == 0L,
                codeStage && hasNextCodeType ? wait : 0L);
    }
}
