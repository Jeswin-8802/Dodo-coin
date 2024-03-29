package io.mycrypto.core.service.transaction;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import io.mycrypto.core.config.DodoCommonConfig;
import io.mycrypto.core.dto.MakeTransactionDto;
import io.mycrypto.core.dto.UTXODto;
import io.mycrypto.core.dto.WalletInfoDto;
import io.mycrypto.core.entity.Input;
import io.mycrypto.core.entity.Output;
import io.mycrypto.core.entity.ScriptPublicKey;
import io.mycrypto.core.entity.Transaction;
import io.mycrypto.core.exception.MyCustomException;
import io.mycrypto.core.repository.DbName;
import io.mycrypto.core.repository.KeyValueRepository;
import io.mycrypto.core.util.UTXOFilterAlgorithms;
import io.mycrypto.core.util.Utility;
import lombok.extern.slf4j.Slf4j;
import org.apache.logging.log4j.util.Strings;
import org.jetbrains.annotations.NotNull;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.PropertySource;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.ObjectUtils;

import java.io.UnsupportedEncodingException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.SignatureException;
import java.util.*;

import static io.mycrypto.core.repository.DbName.*;

@PropertySource("classpath:config.properties")
@Slf4j
@Service
public class TransactionService {

    @Autowired
    private DodoCommonConfig config;

    @Autowired
    private KeyValueRepository<String, String> rocksDB;

    // AVAILABLE ALGORITHMS --------------------------------------

    // refer : https://www.baeldung.com/cs/subset-of-numbers-closest-to-target
    private static final String OPTIMIZED = "OPTIMIZED"; // Meet In the Middle Approach; Selects the UTXOs whose sum closest represents a target amount
    private static final String HIGHEST_SORTED = "HIGHEST_SORTED"; // trivial
    private static final String LOWEST_SORTED = "LOWEST_SORTED"; // trivial
    private static final String RANDOM = "RANDOM"; // trivial
    private static final List<String> ALLOWED_ALGORITHMS;

    static {
        ALLOWED_ALGORITHMS = List.of(OPTIMIZED, HIGHEST_SORTED, LOWEST_SORTED, RANDOM);
    }
    // -----------------------------------------------------------

    /**
     * Fetches transaction by its ID
     *
     * @param id                      Transaction ID
     * @param searchInTransactionPool Boolean that specifies the location for search
     * @return Transaction Information in JSONObject format
     */
    public JSONObject fetchTransaction(String id, Boolean searchInTransactionPool) throws NullPointerException {
        String json = rocksDB.find(id, searchInTransactionPool ? TRANSACTIONS_POOL : TRANSACTIONS);
        try {
            if (json != null)
                return (JSONObject) new JSONParser().parse(json);
            throw new NullPointerException();
        } catch (ParseException exception) {
            log.error("Error while parsing contents of {} in DB to JSON", id, exception);
        }
        return null;
    }

    /**
     * Performs Transaction
     *
     * @param requestDto Holds all the information necessary for creating a transaction
     * @return Transaction object containing all the transaction information of the current transaction
     */
    public Transaction makeTransaction(MakeTransactionDto requestDto) throws MyCustomException {
        String methodName = "makeTransaction(MakeTransactionDto)";
        WalletInfoDto fromInfo;
        try {
            fromInfo = new ObjectMapper().readValue(requestDto.getFrom(), WalletInfoDto.class);
        } catch (JsonProcessingException e) {
            log.error("Error while parsing contents of wallet to WalletInfoDto.class...");
            throw new MyCustomException("Error while parsing contents of wallet to WalletInfoDto.class...");
        }

        Transaction transaction = new Transaction(fromInfo.getAddress(), requestDto.getTo());

        Boolean processCurrencyInjectionTransaction = Boolean.FALSE;

        // check for if currency injection can be performed
        if (fromInfo.getAddress().equals(config.getAdminAddress()) && config.getInjectIfAdmin() == 1)
            processCurrencyInjectionTransaction = Boolean.TRUE;

        List<Input> inputs = new ArrayList<>();
        List<Output> outputs = new ArrayList<>();
        BigDecimal total;

        if (!processCurrencyInjectionTransaction) {
            List<UTXODto> utxos = selectivelyFetchUTXOs(requestDto.getAmount(), requestDto.getAlgorithm(), fromInfo.getAddress(), ObjectUtils.isEmpty(requestDto.getTransactionFee()) ? config.getTransactionFee() : requestDto.getTransactionFee());

            // Fetching UTXO information from AccountsDB
            // Needed for tracking the UTXO associated with an account; The UTXO used will be removed and the updated json will be put back into the AccountsDB
            JSONObject transactionJSON;
            try {
                transactionJSON = new ObjectMapper().readValue(rocksDB.find(fromInfo.getAddress(), ACCOUNTS), JSONObject.class);
            } catch (JsonProcessingException exception) {
                log.error("An error occurred when formatting to JSON", exception);
                throw new MyCustomException("Parse Error...");
            }

            transaction.setNumInputs(utxos.size());

            for (UTXODto utxoDto : utxos) {
                Input input = new Input();
                input.setTransactionId(utxoDto.getTransactionId());
                input.setVout(utxoDto.getVout());
                String scriptSig = constructScriptSig(fromInfo, "abcdefg");
                input.setScriptSig(scriptSig);
                input.setSize((long) scriptSig.getBytes().length);

                inputs.add(input);
            }

            // removing UTXO used for transaction from wallet
            optimizedVoutRefactoring(transactionJSON, utxos);

            log.info("{}::----------------------- {}", methodName, transactionJSON);
            // saving updated transaction UTXO information in Accounts DB
            if (transactionJSON.isEmpty())
                rocksDB.save(fromInfo.getAddress(), "EMPTY", ACCOUNTS);
            else
                rocksDB.save(fromInfo.getAddress(), transactionJSON.toJSONString(), ACCOUNTS);
            transaction.setInputs(inputs);

            total = new BigDecimal(0);
            // total UTXO available for transaction
            for (UTXODto utxoDto : utxos)
                total = total.add(utxoDto.getAmount());

            log.info("{}::Total ------------------------> {}", methodName, total);

            // construct outputs
            // There are 2 outputs as default; 1 being sent to receiver and the balance that gets credited back to the sender

            // output part 1

            int outputNum = 0;
            outputNum += splitAndConstructOutputs(
                    requestDto,
                    outputs,
                    new ScriptPublicKey(Utility.getHash160FromAddress(requestDto.getTo()), requestDto.getTo()),
                    false,
                    total,
                    0);

            // output part 2

            outputNum += splitAndConstructOutputs(
                    requestDto,
                    outputs,
                    new ScriptPublicKey(fromInfo.getHash160(), fromInfo.getAddress()),
                    true,
                    total,
                    outputNum);

            transaction.setNumOutputs(outputNum);

        } else {
            transaction.setNumInputs(0L);

            Input input = new Input();
            input.setTransactionId("");
            input.setVout(-1L);
            String scriptSig = constructScriptSig(fromInfo, "abcd");
            input.setScriptSig(scriptSig);
            input.setSize((long) scriptSig.getBytes().length);
            inputs.add(input);
            transaction.setInputs(inputs);

            total = requestDto.getAmount();

            int outputNum = 0;
            outputNum += splitAndConstructOutputs(
                    requestDto,
                    outputs,
                    new ScriptPublicKey(Utility.getHash160FromAddress(requestDto.getTo()), requestDto.getTo()),
                    false,
                    total,
                    outputNum);
            transaction.setNumOutputs(outputNum);
        }

        transaction.setOutputs(outputs);
        transaction.setSpent(total);
        transaction.setTransactionFee(ObjectUtils.isEmpty(requestDto.getTransactionFee()) ? config.getTransactionFee() : requestDto.getTransactionFee());
        transaction.setMsg(Strings.isEmpty(requestDto.getMessage()) ? String.format("Transferring %s from %s to %s ...", requestDto.getAmount(), fromInfo.getAddress(), requestDto.getTo()) : requestDto.getMessage());
        transaction.calculateHash();

        saveTransaction(transaction, TRANSACTIONS_POOL);

        // Note: the new transaction will not be added to Accounts DB until it gets mined

        return transaction;
    }

    public Transaction addEarlyAdopterReward(WalletInfoDto info) throws MyCustomException {
        Transaction transaction = new Transaction(config.getAdminAddress(), info.getAddress());

        Input input = new Input();
        input.setTransactionId("");
        input.setVout(-1L);
        String scriptSig = constructScriptSig(info, "Early Adopter Reward Dodos");
        input.setScriptSig(scriptSig);
        input.setSize((long) scriptSig.getBytes().length);
        transaction.setInputs(List.of(input));
        transaction.setNumInputs(1);

        List<Output> outputs = new ArrayList<>();
        for (int i = 0; i < config.getDefaultOutputDivisions(); i++) {
            Output output = new Output();
            output.setScriptPubKey(new ScriptPublicKey(info.getHash160(), info.getAddress()));
            output.setAmount(config.getBlockReward().divide(new BigDecimal(config.getDefaultOutputDivisions())));
            output.setN((long) i);
            outputs.add(output);
        }
        transaction.setOutputs(outputs);
        transaction.setNumOutputs(config.getDefaultOutputDivisions());

        transaction.setSpent(new BigDecimal("-1.0").multiply(config.getBlockReward()));
        transaction.setTransactionFee(config.getTransactionFee());
        transaction.setMsg("Early adopters' reward dodos");
        transaction.calculateHash();

        saveTransaction(transaction, TRANSACTIONS);
        saveTransactionToWalletIfTransactionPointsToWalletOwned(transaction);

        return transaction;
    }

    private int splitAndConstructOutputs(MakeTransactionDto requestDto, List<Output> outputs, ScriptPublicKey scriptPublicKey, Boolean isOutputToSender, BigDecimal total, int currentOutputNum) {
        int outputNum = currentOutputNum;

        String outputPartsInfo = isOutputToSender ? requestDto.getReceivingOutputParts() : requestDto.getToOutputParts();

        if (!Strings.isEmpty(outputPartsInfo) && outputPartsInfo.contains(":")) {
            List<Double> amountRatios = new ArrayList<>(Arrays.stream(outputPartsInfo.split(":")).map(Double::parseDouble).toList());
            Double sum = amountRatios.stream().reduce(0.0, Double::sum);
            amountRatios.replaceAll(aDouble -> aDouble / sum);
            for (int i = 0; i < amountRatios.size(); i++) {
                Output output = new Output();
                output.setScriptPubKey(scriptPublicKey);
                output.setAmount(requestDto.getAmount().multiply(BigDecimal.valueOf(amountRatios.get(i))));
                output.setN((long) (i + currentOutputNum));
                outputs.add(output);
            }
            outputNum += amountRatios.size();
        } else {
            int loopCount = Strings.isBlank(outputPartsInfo) ? (isOutputToSender ? config.getDefaultOutputDivisions() : 1) : (int) Double.parseDouble(outputPartsInfo);
            BigDecimal amount = isOutputToSender ?
                    (total.subtract(requestDto.getAmount()).subtract(ObjectUtils.isEmpty(requestDto.getTransactionFee()) ? config.getTransactionFee() : requestDto.getTransactionFee())).divide(BigDecimal.valueOf(loopCount)) : // (total - amount - transactionFee) / num_parts
                    requestDto.getAmount().divide(BigDecimal.valueOf(loopCount));
            for (int i = 0; i < loopCount; i++) {
                Output output = new Output();
                output.setScriptPubKey(scriptPublicKey);
                output.setAmount(amount);
                output.setN((long) (i + currentOutputNum));
                outputs.add(output);
            }
            outputNum += loopCount;
        }
        return outputNum;
    }

    private void optimizedVoutRefactoring(JSONObject transactionJSON, List<UTXODto> utxos) {
        Map<String, List<Long>> utxosSummmary = new HashMap<>();
        for (UTXODto utxo : utxos) {
            utxosSummmary.putIfAbsent(utxo.getTransactionId(), new ArrayList<>());
            utxosSummmary.get(utxo.getTransactionId()).add(utxo.getVout());
        }

        // selectively removing vouts from json field values
        for (String transactionId : utxosSummmary.keySet()) {
            String VOUTs = (String) transactionJSON.get(transactionId);
            if (VOUTs.contains(",")) {
                List<String> voutList = new ArrayList<>(List.of(VOUTs.split(",")));
                for (Long vout : utxosSummmary.get(transactionId))
                    voutList.remove(vout.toString());
                if (CollectionUtils.isEmpty(voutList))
                    transactionJSON.remove(transactionId);
                else
                    transactionJSON.put(transactionId, String.join(",", voutList));
            } else
                transactionJSON.remove(transactionId);
        }
    }

    private String constructScriptSig(WalletInfoDto fromInfo, String dataToSign) throws MyCustomException {
        String methodName = "constructScriptSig(WalletInfoDto, String)";
        String signature;
        try {
            signature = Utility.getSignedData(fromInfo.getPrivateKey(), dataToSign);
        } catch (NoSuchAlgorithmException | SignatureException | InvalidKeyException |
                 UnsupportedEncodingException e) {
            e.printStackTrace();
            throw new MyCustomException("Error Occurred while getting data signed...");
        }
        log.info("{}::Signature ==> {}", methodName, signature);

        return signature + " " + fromInfo.getPublicKey();
    }

    public List<Transaction> retrieveAndDeleteTransactionsFromTransactionsPool() throws MyCustomException {
        // checking for if there exists enough transactions within the Transactions Pool
        if (rocksDB.getCount(TRANSACTIONS_POOL) < config.getLowerLimitCount())
            throw new MyCustomException(String.format("Not enough transactions in the Transactions Pool to mine a Block; Must contain at least %s transactions", config.getLowerLimitCount()));

        // retrieving transactions from the transaction pool
        Map<String, String> transactionsMap = rocksDB.getList(TRANSACTIONS_POOL);

        // converting transactions from JSON String to <Transaction.class>
        List<Transaction> transactions = new ArrayList<>();
        for (String transactionHash : transactionsMap.keySet()) {
            try {
                Transaction tx = new ObjectMapper().readValue(transactionsMap.get(transactionHash), Transaction.class);
                transactions.add(tx);
            } catch (JsonProcessingException exception) {
                log.error("An error occurred when formatting to JSON", exception);
                throw new MyCustomException(String.format("Error while parsing Transaction with id: %s from JSON String to <Transaction.class>", transactionHash));
            }
        }

        // sorting transactions according to transaction fee
        transactions.sort(Comparator.comparing(Transaction::getTransactionFee));

        // Taking atMost N Transactions; N is the Uppermost Limit of transactions in a block
        if (transactions.size() > config.getUpperLimitCount()) {
            long N = new Random().nextLong(config.getLowerLimitCount(), config.getUpperLimitCount()); // to bring in variability
            for (long i = transactions.size() - 1; i >= N; i--)
                transactions.remove((int) i);
        }

        // fetch list of Transaction Details in all Wallet
        // to be used below
        Map<String, String> info = rocksDB.getList(ACCOUNTS);
        if (info == null) {
            log.error("No content found in Accounts DB...");
            throw new MyCustomException("No content found in Accounts DB...");
        }

        // removing the transactions from the Transactions-PoolDB and adding to TransactionsDB
        for (Transaction tx : transactions) {
            rocksDB.delete(tx.getTransactionId(), TRANSACTIONS_POOL);

            // save transaction information to AccountsDB if transaction has your wallet address

            for (Output out : tx.getOutputs()) {
                // If the output is mapped to an address that is owned
                if (info.containsKey(out.getScriptPubKey().getAddress()))
                    addTransactionToAccounts(out.getScriptPubKey().getAddress(), tx.getTransactionId(), out.getN());
            }

            saveTransaction(tx, TRANSACTIONS);
        }

        return transactions;
    }

    /**
     * Creates a coinbase transaction
     *
     * @param info             Holds Wallet Information
     * @param forGenesisBlock  A Boolean value which specifies if the coinbase transaction belongs to a genesis block or a normal block
     * @param transactionsList A list of transactions which is considered when the transaction fee of all transactions in a block needs to be considered when mining a block
     * @return Transaction Object containing information about the coinbase transaction
     */
    public Transaction constructCoinbaseTransaction(WalletInfoDto info, Boolean forGenesisBlock, List<Transaction> transactionsList) throws MyCustomException {
        Transaction coinbase = new Transaction("", info.getAddress());
        coinbase.setNumInputs(0);
        coinbase.setInputs(new ArrayList<>());
        coinbase.setNumOutputs(config.getDefaultOutputDivisions() + (CollectionUtils.isEmpty(transactionsList) ? 0 : 1));

        // construct Script Public Key (Locking Script)
        List<Output> outputList = getOutputs(info, transactionsList);

        // set outputs
        coinbase.setOutputs(outputList);
        coinbase.setSpent(new BigDecimal("0.0")); // 0 as there are no inputs for a coinbase transaction
        coinbase.setMsg(forGenesisBlock ? "The first and only transaction within the genesis block..." : "COINBASE...");
        coinbase.calculateHash(); // calculates and sets transactionId

        saveTransaction(coinbase, TRANSACTIONS);
        // saving to Transactions DB and not to Transactions-Pool DB for the time being until network broadcast has been implemented
        // TODO: save to Transactions-Pool until network broadcast is brought

        saveTransactionToWalletIfTransactionPointsToWalletOwned(coinbase);

        return coinbase;
    }

    @NotNull
    private List<Output> getOutputs(WalletInfoDto info, List<Transaction> transactionsList) {
        ScriptPublicKey script = new ScriptPublicKey(info.getHash160(), info.getAddress());

        List<Output> outputList = new ArrayList<>();
        for (int i = 0; i < config.getDefaultOutputDivisions(); i++) {
            Output output = new Output();
            output.setAmount(config.getBlockReward().divide(new BigDecimal(config.getDefaultOutputDivisions())));
            output.setN((long) i);
            output.setScriptPubKey(script);

            outputList.add(output);
        }

        if (!CollectionUtils.isEmpty(transactionsList)) {
            Output output = new Output();

            // calculate the transaction fee for all the transactions that will be included in the block
            BigDecimal total = new BigDecimal(0);
            for (Transaction tx : transactionsList)
                total = total.add(tx.getTransactionFee());

            output.setAmount(total);
            output.setN((long) outputList.size());
            output.setScriptPubKey(script);

            outputList.add(output);
        }
        return outputList;
    }

    /**
     * @param tx Transaction Object holding all the Transaction Information
     */
    private void saveTransaction(Transaction tx, DbName DB) throws MyCustomException {
        String methodName = "saveTransaction(Transaction, String)";
        String json;
        try {
            ObjectWriter ow = new ObjectMapper().writer().withDefaultPrettyPrinter();
            json = ow.writeValueAsString(tx);
            tx.setSize(new BigInteger(String.valueOf(json.replace(" ", "").length() - "\"size\":null\"weight\": null".length())));
            tx.setWeight(new BigInteger("4").multiply(tx.getSize()).subtract(new BigInteger(String.valueOf(tx.getInputs().size()))));
            json = ow.writeValueAsString(tx);
            log.info("{}::{} ==> \n{}", methodName, tx.getTransactionId(), json);
        } catch (JsonProcessingException exception) {
            log.error("Error occurred while parsing Object(Transaction) to json", exception);
            throw new MyCustomException("Error occurred while parsing Object(Transaction) to json");
        }

        rocksDB.save(tx.getTransactionId(), json, DB);

        // Saving to Transactions DB only when a transaction is present in a block that is mined
    }

    /**
     * Adds transaction information to associated accounts into DB to keep track of UTXOs
     *
     * @param address Wallet Address
     * @param txId    Transaction ID
     * @param vout    The VOUT value for the Output in a Transaction
     */
    void addTransactionToAccounts(String address, String txId, Long vout) throws MyCustomException {
        String existingTransactions = rocksDB.find(address, ACCOUNTS);

        JSONObject transactions;
        if (!existingTransactions.equals("EMPTY")) {
            try {
                transactions = new ObjectMapper().readValue(existingTransactions, JSONObject.class);
            } catch (JsonProcessingException exception) {
                log.error("An error occurred when formatting to JSON", exception);
                throw new MyCustomException("Error while casting transaction UTXO data from Accounts DB to JSON Object...");
            }
        } else {
            transactions = new JSONObject();
        }

        if (transactions.containsKey(txId))
            transactions.put(txId, ((String) transactions.get(txId)).concat("," + vout.toString()));
        else
            transactions.put(txId, vout.toString());
        rocksDB.save(address, transactions.toJSONString(), ACCOUNTS);
    }


    /**
     * Retrieves all UTXOs linked to a WaLLet
     *
     * @param transactions A list of all (transactionId, VOUT) for a given wallet
     * @return A list of UTXOs liked to a given Wallet
     */
    public List<UTXODto> retrieveAllUTXOs(JSONObject transactions, DbName db) throws JsonProcessingException, MyCustomException {
        List<UTXODto> result = new ArrayList<>();
        for (Object txId : transactions.keySet()) {
            String transaction = rocksDB.find((String) txId, db);
            if (transaction == null) {
                log.error("Could not find transaction {} obtained from Account DB in {}} DB", txId, db);
                throw new MyCustomException(String.format("Transactions present in wallet not found in %s DB...", db));
            }

            List<Output> outputs = new ObjectMapper().readValue(transaction, Transaction.class).getOutputs();
            BigDecimal amount = null;

            String outN = ((String) transactions.get(txId));

            // getting the vout
            for (Output output : outputs) {
                if (outN.contains(",")) {
                    for (String n : outN.split(",")) {
                        if (output.getN() == Long.parseLong(n)) {
                            amount = output.getAmount();
                            result.add(UTXODto.builder()
                                    .transactionId((String) txId)
                                    .vout(Long.parseLong(n))
                                    .amount(amount)
                                    .build()
                            );
                            break;
                        }
                    }
                } else {
                    if (output.getN() == Long.parseLong(outN)) {
                        amount = output.getAmount();
                        result.add(UTXODto.builder()
                                .transactionId((String) txId)
                                .vout(Long.parseLong(outN))
                                .amount(amount)
                                .build()
                        );
                        break;
                    }
                }
            }
        }
        return result;
    }

    /**
     * Selectively Fetches UTXOs from a wallet for a transaction based on a specific predetermined algorithm
     *
     * @param amount         Amount to transact
     * @param algorithm      The algorithm used to select suitable UTXOs for the transaction <br><br>
     *                       Available options:<br>
     *                       1 = <b>OPTIMAL</b><br>
     *                       2 = <b>HIGHEST_SORTED</b><br>
     *                       3 = <b>LOWEST_SORTED</b><br>
     *                       4 = <b>RANDOM</b><br>
     * @param walletAddress  Points to the Wallet from which the UTXOs are to be selected
     * @param transactionFee The transaction fee that will be charged for the transaction;
     *                       This will be added to the amount when taking into consideration the selection of UTXOs for the transaction
     * @return List of UTXOs selected for a particular transaction
     */
    public List<UTXODto> selectivelyFetchUTXOs(BigDecimal amount, Integer algorithm, String walletAddress, BigDecimal transactionFee) throws MyCustomException {
        if (amount.equals(new BigDecimal(0)))
            throw new MyCustomException(String.format("Amount to start a transaction must be greater than the transaction fee set (%s)", config.getTransactionFee()));

        // transaction data for given wallet
        String transactions = rocksDB.find(walletAddress, ACCOUNTS);
        if (transactions.equals("EMPTY"))
            throw new MyCustomException(String.format("No transaction data found for wallet with address: %s", walletAddress));

        List<UTXODto> allUTXOs;
        try {
            allUTXOs = retrieveAllUTXOs(new ObjectMapper().readValue(transactions, JSONObject.class), TRANSACTIONS); // Note: for a UTXO to be used, it must have been mined
        } catch (JsonProcessingException exception) {
            log.error("Error while parsing transaction info in DB to <Transaction.class> OR utxo info to JSON", exception);
            throw new MyCustomException("Error while parsing transaction info in DB to <Transaction.class> OR utxo info to JSON");
        }
        if (CollectionUtils.isEmpty(allUTXOs))
            throw new MyCustomException("Wallet is empty...");

        String alg = getString(algorithm);

        // checking for if the wallet contains enough balance to transact the given amount
        BigDecimal total = new BigDecimal(0);
        for (UTXODto utxo : allUTXOs)
            total = total.add(utxo.getAmount());
        if (total.compareTo(amount.add(transactionFee)) < 0)
            throw new MyCustomException(String.format("Not enough balance to make up an amount (may or may not include transaction fee) >= %s; current balance: %s", amount.add(transactionFee), total));
        if (allUTXOs.size() == 1)
            return allUTXOs;

        List<UTXODto> filteredUTXOs = null;
        switch (Objects.requireNonNull(alg)) {
            case OPTIMIZED ->
                    filteredUTXOs = UTXOFilterAlgorithms.meetInTheMiddleSelectionAlgorithm(allUTXOs, amount.add(transactionFee));
            case HIGHEST_SORTED ->
                    filteredUTXOs = UTXOFilterAlgorithms.selectUTXOsInSortedOrder(allUTXOs, amount.add(transactionFee), Boolean.TRUE);
            case LOWEST_SORTED ->
                    filteredUTXOs = UTXOFilterAlgorithms.selectUTXOsInSortedOrder(allUTXOs, amount.add(transactionFee), Boolean.FALSE);
            case RANDOM ->
                    filteredUTXOs = UTXOFilterAlgorithms.selectRandomizedUTXOs(allUTXOs, amount.add(transactionFee));
        }

        return filteredUTXOs;
    }

    @NotNull
    private static String getString(Integer algorithm) throws MyCustomException {
        String alg;
        switch (algorithm) {
            case 1 -> alg = OPTIMIZED;
            case 2 -> alg = HIGHEST_SORTED;
            case 3 -> alg = LOWEST_SORTED;
            case 4 -> alg = RANDOM;
            default -> throw new MyCustomException("Invalid algorithm type passed");
        }

        // checking for allowed algorithms
        if (!ALLOWED_ALGORITHMS.contains(alg))
            throw new MyCustomException(String.format("Algorithm %s not present in ALLOWED_LIST of algorithms...", alg));
        return alg;
    }

    private void saveTransactionToWalletIfTransactionPointsToWalletOwned(Transaction tx) throws MyCustomException {
        // fetch list of Transaction Details in all Wallet
        Map<String, String> utxoInfo = rocksDB.getList(ACCOUNTS);
        if (CollectionUtils.isEmpty(utxoInfo)) {
            log.error("No content found in Accounts DB...");
            throw new MyCustomException("No content found in Accounts DB...");
        }

        for (Output out : tx.getOutputs()) {
            // If the output is mapped to an address that is owned
            if (utxoInfo.containsKey(out.getScriptPubKey().getAddress()))
                addTransactionToAccounts(out.getScriptPubKey().getAddress(), tx.getTransactionId(), out.getN());
        }
    }
}
