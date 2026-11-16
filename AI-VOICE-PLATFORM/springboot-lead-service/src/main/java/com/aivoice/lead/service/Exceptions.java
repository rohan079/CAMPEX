package com.aivoice.lead.service;

public final class Exceptions {

    private Exceptions() {
    }

    public static class InvalidPhoneException extends RuntimeException {
        public InvalidPhoneException(String message) {
            super(message);
        }
    }

    public static class NotFoundException extends RuntimeException {
        public NotFoundException(String message) {
            super(message);
        }
    }

    public static class MessageDeliveryException extends RuntimeException {
        public MessageDeliveryException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * The voice service could not start a call.
     * {@code ambiguous} means the request may still have reached the telephone network,
     * so the call must be reconciled by a person rather than retried.
     */
    public static class VoiceServiceException extends RuntimeException {
        private final boolean ambiguous;
        private final boolean capacityDeferred;

        public VoiceServiceException(String message, boolean ambiguous, Throwable cause) {
            this(message, ambiguous, false, cause);
        }

        public VoiceServiceException(String message, boolean ambiguous, boolean capacityDeferred, Throwable cause) {
            super(message, cause);
            this.ambiguous = ambiguous;
            this.capacityDeferred = capacityDeferred;
        }

        public boolean isAmbiguous() {
            return ambiguous;
        }

        public boolean isCapacityDeferred() {
            return capacityDeferred;
        }
    }

    public static class CampaignAssistException extends RuntimeException {
        private final int status;

        public CampaignAssistException(String message, int status, Throwable cause) {
            super(message, cause);
            this.status = status;
        }

        public int status() {
            return status;
        }
    }
}
