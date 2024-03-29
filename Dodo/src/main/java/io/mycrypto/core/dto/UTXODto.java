package io.mycrypto.core.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Builder
public class UTXODto {
    @JsonProperty("transaction-id")
    String transactionId;
    @JsonProperty("vout")
    Long vout;
    @JsonProperty("amount")
    BigDecimal amount;
}
