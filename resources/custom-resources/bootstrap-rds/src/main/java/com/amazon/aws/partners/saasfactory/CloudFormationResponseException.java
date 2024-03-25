package com.amazon.aws.partners.saasfactory;

public class CloudFormationResponseException extends RuntimeException {

    public CloudFormationResponseException() {
        super();
    }

    public CloudFormationResponseException(String message) {
        super(message);
    }

    public CloudFormationResponseException(String message, Throwable cause) {
        super(message, cause);
    }
}
