package io.mycrypto.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
@Builder
public class WalletUTXOResponseDto {
    @JsonProperty("UTXO")
    List<WalletUTXODto> UTXOs;
    @JsonProperty("dodo-coin-balance")
    BigDecimal total;
}