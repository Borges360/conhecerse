package com.psicocare.shared;

import tools.jackson.databind.JsonNode;

import java.util.UUID;

public interface PaymentProvider {
    JsonNode findOrCreate(UUID tenant, UUID paymentId, JsonNode request);

    JsonNode canonical(UUID tenant, String providerPaymentId);
}
