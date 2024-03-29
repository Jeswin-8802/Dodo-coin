package io.mycrypto.core.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
@Builder
public class WalletUTXOResponseDto {
    @JsonProperty("UTXO")
    List<UTXODto> UTXOs;
    @JsonProperty("total")
    BigDecimal total;
}
