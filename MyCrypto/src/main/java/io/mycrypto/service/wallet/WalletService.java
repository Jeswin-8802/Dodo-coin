package io.mycrypto.service.wallet;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mycrypto.dto.SimplifiedWalletInfoDto;
import io.mycrypto.dto.UTXODto;
import io.mycrypto.dto.WalletInfoDto;
import io.mycrypto.dto.WalletListDto;
import io.mycrypto.exception.MyCustomException;
import io.mycrypto.repository.KeyValueRepository;
import io.mycrypto.service.transaction.TransactionService;
import io.mycrypto.util.Utility;
import lombok.extern.slf4j.Slf4j;
import org.bitcoinj.core.Base58;
import org.json.simple.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.math.BigDecimal;
import java.util.*;

@Slf4j
@Service
public class WalletService {
    @Autowired
    KeyValueRepository<String, String> rocksDB;

    @Autowired
    TransactionService transactionService;

    /**
     * fetches All Wallet-Info from "Wallets" DB from which we obtain WalletName and Address;
     * Also calculates the balance in each Wallet from "Accounts" DB for the corresponding Wallet and appends it to the Info being returned
     *
     * @return WalletListDto; A list of Wallets
     */
    public WalletListDto fetchWallets() {
        // fetch list of wallet info to obtain all WalletNames and Addresses
        Map<String, String> info = rocksDB.getList("Wallets");
        if (info == null) {
            log.error("No content found in Wallets DB, Looks like you haven't created a wallet yet...");
            throw new RuntimeException();
        }

        List<SimplifiedWalletInfoDto> response = new ArrayList<>();
        for (Map.Entry<String, String> i : info.entrySet()) {
            // convert from JSON-string to WalletInfoDto to get <Address>
            WalletInfoDto temp = null;
            try {
                temp = new ObjectMapper().readValue(i.getValue(), WalletInfoDto.class);
            } catch (JsonProcessingException e) {
                log.error("Error occurred while trying to parse data from Wallets DB to that of type <WalletInfoDto>...");
                e.printStackTrace();
                throw new RuntimeException(e);
            }

            BigDecimal balance = new BigDecimal("0");

            // get balance from "Accounts" DB
            String transactionDetails = rocksDB.find(temp.getAddress(), "Accounts");
            List<UTXODto> UTXOs = null;
            if (!transactionDetails.equals("EMPTY")) {
                try {
                    UTXOs = transactionService.retrieveAllUTXOs(new ObjectMapper().readValue(transactionDetails, JSONObject.class));
                } catch (JsonProcessingException e) {
                    throw new RuntimeException(e);
                }
            }
            if (!CollectionUtils.isEmpty(UTXOs))
                for (UTXODto utxo : UTXOs)
                    balance = balance.add(utxo.getAmount());

            // construct response
            response.add(SimplifiedWalletInfoDto.builder()
                    .walletName(i.getKey())
                    .address(temp.getAddress())
                    .balance(balance)
                    .build());
        }
        return WalletListDto.builder().wallets(response).build();
    }

    /**
     * Address: analogous to the identity of a wallet on the network;
     * Checks for its validity by checking for valid checksum
     *
     * @param address dodo-coin address
     * @param hash160 hash-160 of the private key associated with a wallet
     * @return Boolean
     */
    public boolean verifyAddress(String address, String hash160) throws MyCustomException {
        String inputs = """
                {
                    "address": "%s",
                    "hash160": "%s"
                }
                """;
        log.info("Inputs:\n{}", Utility.beautify(String.format(inputs, address, hash160)));

        // decode
        byte[] decodedData = Base58.decode(address);
        log.info("Base58 decoded (address) : {}", Utility.bytesToHex(decodedData));

        byte[] hash160FromAddress = new byte[decodedData.length - 5];
        byte[] checksumFromAddress = new byte[4];
        System.arraycopy(decodedData, 1, hash160FromAddress, 0, decodedData.length - 5);
        log.info("Hash160(public-key) from address: {}", Utility.bytesToHex(hash160FromAddress));
        System.arraycopy(decodedData, decodedData.length - 4, checksumFromAddress, 0, 4);
        log.info("checksum from dodo-coin-address: {}", Utility.bytesToHex(checksumFromAddress));

        byte[] checksum = new byte[4];
        try {
            System.arraycopy(Objects.requireNonNull(Utility.getHashSHA256(Utility.getHashSHA256(Utility.hexToBytes(hash160)))), 0, checksum, 0, 4);
        } catch (StringIndexOutOfBoundsException e) {
            log.error("Invalid parameters passed; The length of address and or hash160 is invalid");
            e.printStackTrace();
            throw new MyCustomException("Invalid length for the address and or hash160");
        }
        log.info("checksum from public-key: {}", Utility.bytesToHex(checksum));

        return Arrays.equals(checksumFromAddress, checksum) && hash160.equals(Utility.bytesToHex(hash160FromAddress));
    }
}
