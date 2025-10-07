package bbt.tao.orchestra.agent.model;

import bbt.tao.orchestra.agent.VerificationResult;

public record VerifierOutcome(VerificationResult result, String explanation) {
}
